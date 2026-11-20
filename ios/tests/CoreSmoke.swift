import AVFoundation
import AudioToolbox
import CoreMedia
import Dispatch
import Foundation

private struct SmokeFailure: Error, CustomStringConvertible {
    let description: String
}

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() { throw SmokeFailure(description: message) }
}

private final class CommitResults: @unchecked Sendable {
    private let lock = NSLock()
    private var outputs: [(Int, URL)] = []
    private var failures: [String] = []

    func append(_ result: Result<(Int, URL), Error>) {
        lock.lock()
        defer { lock.unlock() }
        switch result {
        case .success(let output): outputs.append(output)
        case .failure(let error): failures.append(String(describing: error))
        }
    }

    func snapshot() -> (outputs: [(Int, URL)], failures: [String]) {
        lock.lock()
        defer { lock.unlock() }
        return (outputs, failures)
    }
}

@main
struct CoreSmoke {
    static func main() async throws {
        let workspace = FileManager.default.temporaryDirectory
            .appendingPathComponent("waslora-smoke-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: workspace, withIntermediateDirectories: true)
        // Only remove the unique fixture directory created above, never the app's
        // existing Documents/Waslora directory or any other user's saved files.
        defer { try? FileManager.default.removeItem(at: workspace) }

        try collisionAndPathTests(in: workspace)
        try importTest(in: workspace)
        try await audioTests(in: workspace)
        print("Waslora core smoke checks passed: concurrent saves, safe paths, import integrity, M4A export/decode, invalid media rejection.")
    }

    private static func collisionAndPathTests(in workspace: URL) throws {
        let inbox = workspace.appendingPathComponent("incoming", isDirectory: true)
        let destination = workspace.appendingPathComponent("saved", isDirectory: true)
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let writerCount = 24
        let payloads = (0..<writerCount).map { index in
            Data("writer-\(index):".utf8) + Data(repeating: UInt8(index), count: 4096)
        }
        let sources = (0..<writerCount).map { inbox.appendingPathComponent("\($0).part") }
        for index in sources.indices { try payloads[index].write(to: sources[index]) }

        let results = CommitResults()
        DispatchQueue.concurrentPerform(iterations: writerCount) { index in
            do {
                let url = try WasloraFiles.commitTemporaryFile(sources[index], named: "same-name.mp4", in: destination)
                results.append(.success((index, url)))
            } catch { results.append(.failure(error)) }
        }
        let snapshot = results.snapshot()
        try require(snapshot.failures.isEmpty, "Concurrent save failed: \(snapshot.failures)")
        try require(snapshot.outputs.count == writerCount, "A concurrent save disappeared")
        try require(Set(snapshot.outputs.map { $0.1.path }).count == writerCount, "Concurrent saves reused a destination")
        for (index, url) in snapshot.outputs {
            let actual = try Data(contentsOf: url)
            try require(actual == payloads[index], "Saved bytes were overwritten for writer \(index)")
            try require(!FileManager.default.fileExists(atPath: sources[index].path), "Committed temporary file was not moved")
        }

        let unsafeNames = ["../../escape.mp4", "/tmp/escape.mp4", ".", "..",
                           "folder\\..\\clip?:\"<>|\u{0000}.mp4", "  clean.mp4  "]
        let forbidden = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "/\\:*?\"<>|"))
        for raw in unsafeNames {
            let safe = WasloraFiles.safeFilename(raw)
            try require(!safe.isEmpty && safe != "." && safe != "..", "Unsafe empty or relative filename")
            try require(!safe.unicodeScalars.contains(where: { forbidden.contains($0) }), "Filename contains forbidden characters")
        }
        let traversalSource = inbox.appendingPathComponent("traversal.part")
        let traversalBytes = Data("path containment fixture".utf8)
        try traversalBytes.write(to: traversalSource)
        let contained = try WasloraFiles.commitTemporaryFile(traversalSource, named: "../../escape.mp4", in: destination)
        try require(contained.deletingLastPathComponent().standardizedFileURL == destination.standardizedFileURL,
                    "A filename escaped the destination directory")
        let containedBytes = try Data(contentsOf: contained)
        try require(containedBytes == traversalBytes, "Path sanitization changed file contents")
    }

    private static func importTest(in workspace: URL) throws {
        let source = workspace.appendingPathComponent("waslora-import-\(UUID().uuidString).bin")
        let payload = Data((0..<8192).map { UInt8($0 % 256) })
        try payload.write(to: source)
        let imported = try WasloraFiles.importFile(source)
        defer { try? FileManager.default.removeItem(at: imported) }
        let importedBytes = try Data(contentsOf: imported)
        let originalBytes = try Data(contentsOf: source)
        try require(importedBytes == payload, "Import changed binary file bytes")
        try require(originalBytes == payload, "Import removed or changed the source file")
        try require(imported.lastPathComponent == source.lastPathComponent, "Import lost the original filename")

        do {
            let unexpected = try WasloraFiles.importFile(workspace)
            try? FileManager.default.removeItem(at: unexpected)
            throw SmokeFailure(description: "Import accepted a directory as a regular file")
        } catch is SmokeFailure { throw SmokeFailure(description: "Import accepted a directory as a regular file") }
        catch { /* Expected: directory imports must fail. */ }
    }

    private static func audioTests(in workspace: URL) async throws {
        let source = workspace.appendingPathComponent("waslora-tone-\(UUID().uuidString).wav")
        try pcmTone().write(to: source)
        let converted = try await AudioConverter.toM4A(source)
        defer { try? FileManager.default.removeItem(at: converted) }
        try require(converted.pathExtension == "m4a", "Converter returned an unexpected extension")
        let bytes = try converted.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        try require(bytes > 0, "M4A export is empty")

        let asset = AVURLAsset(url: converted)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        let duration = try await asset.load(.duration)
        try require(!tracks.isEmpty && duration.seconds.isFinite && duration.seconds > 0,
                    "M4A has no usable audio track/duration")
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: tracks[0], outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false
        ])
        try require(reader.canAdd(output), "Cannot configure a decoder for the M4A")
        reader.add(output)
        try require(reader.startReading(), "Cannot start decoding the exported M4A")
        defer { reader.cancelReading() }
        guard let sample = output.copyNextSampleBuffer() else {
            throw SmokeFailure(description: "Exported M4A could not decode an audio sample: \(String(describing: reader.error))")
        }
        try require(CMSampleBufferGetNumSamples(sample) > 0, "Decoded M4A contains no samples")

        let invalid = workspace.appendingPathComponent("waslora-invalid-\(UUID().uuidString).mp4")
        try Data("This fixture is not a media container.".utf8).write(to: invalid)
        do {
            let unexpected = try await AudioConverter.toM4A(invalid)
            try? FileManager.default.removeItem(at: unexpected)
            throw SmokeFailure(description: "Converter reported success for malformed media")
        } catch is SmokeFailure { throw SmokeFailure(description: "Converter reported success for malformed media") }
        catch { /* Expected: malformed inputs must never create a success file. */ }
    }

    private static func pcmTone() -> Data {
        let sampleRate: UInt32 = 44_100
        let frames = Int(sampleRate / 2)
        let byteCount = UInt32(frames * 2)
        var result = Data()
        func ascii(_ value: String) { result.append(contentsOf: value.utf8) }
        func littleEndian<T: FixedWidthInteger>(_ value: T) {
            var encoded = value.littleEndian
            withUnsafeBytes(of: &encoded) { result.append(contentsOf: $0) }
        }
        ascii("RIFF"); littleEndian(UInt32(36) + byteCount); ascii("WAVE")
        ascii("fmt "); littleEndian(UInt32(16)); littleEndian(UInt16(1))
        littleEndian(UInt16(1)); littleEndian(sampleRate); littleEndian(sampleRate * 2)
        littleEndian(UInt16(2)); littleEndian(UInt16(16))
        ascii("data"); littleEndian(byteCount)
        for frame in 0..<frames {
            let sample = Int16(sin(2 * Double.pi * 440 * Double(frame) / Double(sampleRate)) * 12_000)
            littleEndian(sample)
        }
        return result
    }
}
