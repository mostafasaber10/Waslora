import AVFoundation
import Foundation

enum AudioConverter {
    static func toM4A(_ source: URL) async throws -> URL {
        let ownsAccess = source.startAccessingSecurityScopedResource()
        defer { if ownsAccess { source.stopAccessingSecurityScopedResource() } }
        let asset = AVURLAsset(url: source)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        guard !tracks.isEmpty else {
            throw NSError(domain: "WasloraAudio", code: 1, userInfo: [NSLocalizedDescriptionKey: "لم أجد مسارًا صوتيًا داخل الفيديو."])
        }
        guard let exporter = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw NSError(domain: "WasloraAudio", code: 2, userInfo: [NSLocalizedDescriptionKey: "لا يدعم iPhone تحويل هذا الفيديو إلى M4A."])
        }
        guard exporter.supportedFileTypes.contains(.m4a) else {
            throw NSError(domain: "WasloraAudio", code: 2, userInfo: [NSLocalizedDescriptionKey: "صيغة الصوت داخل هذا الفيديو غير مدعومة للتحويل إلى M4A."])
        }
        let name = source.deletingPathExtension().lastPathComponent + ".m4a"
        let temporaryURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("m4a")
        defer { try? FileManager.default.removeItem(at: temporaryURL) }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            exporter.outputURL = temporaryURL
            exporter.outputFileType = .m4a
            exporter.shouldOptimizeForNetworkUse = false
            exporter.exportAsynchronously {
                switch exporter.status {
                case .completed: continuation.resume()
                case .failed, .cancelled:
                    continuation.resume(throwing: exporter.error ?? NSError(domain: "WasloraAudio", code: 3, userInfo: [NSLocalizedDescriptionKey: "تعذر تحويل الصوت."]))
                default:
                    continuation.resume(throwing: NSError(domain: "WasloraAudio", code: 4, userInfo: [NSLocalizedDescriptionKey: "لم يكتمل تحويل الصوت."]))
                }
            }
        }
        let output = AVURLAsset(url: temporaryURL)
        let outputTracks = try await output.loadTracks(withMediaType: .audio)
        let duration = try await output.load(.duration)
        let bytes = try temporaryURL.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard !outputTracks.isEmpty, duration.seconds.isFinite, duration.seconds > 0, bytes > 0 else {
            throw NSError(domain: "WasloraAudio", code: 5, userInfo: [NSLocalizedDescriptionKey: "تعذر إنشاء ملف صوت صالح للتشغيل. جرّب فيديو آخر."])
        }
        return try WasloraFiles.commitTemporaryFile(temporaryURL, named: name)
    }
}
