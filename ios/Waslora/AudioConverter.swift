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
        let name = source.deletingPathExtension().lastPathComponent + ".m4a"
        let destination = WasloraFiles.uniqueURL(for: name)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            exporter.outputURL = destination
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
        return destination
    }
}
