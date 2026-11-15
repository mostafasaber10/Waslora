import Foundation

enum MediaCategory: String, CaseIterable, Identifiable {
    case all = "الكل"
    case video = "فيديو"
    case audio = "صوت"
    case image = "صور"
    case other = "ملفات"

    var id: String { rawValue }

    func matches(_ url: URL) -> Bool {
        if self == .all { return true }
        let ext = url.pathExtension.lowercased()
        switch self {
        case .all: return true
        case .video: return ["mp4", "m4v", "mov", "mkv", "webm", "3gp"].contains(ext)
        case .audio: return ["mp3", "m4a", "aac", "wav", "ogg", "flac"].contains(ext)
        case .image: return ["jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp"].contains(ext)
        case .other: return !MediaCategory.video.matches(url) && !MediaCategory.audio.matches(url) && !MediaCategory.image.matches(url)
        }
    }
}

enum WasloraFiles {
    static var directory: URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let folder = documents.appendingPathComponent("Waslora", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    static func uniqueURL(for name: String, in directory: URL = directory) -> URL {
        let safeName = URL(fileURLWithPath: name).lastPathComponent
        let candidate = directory.appendingPathComponent(safeName)
        guard FileManager.default.fileExists(atPath: candidate.path) else { return candidate }
        let ext = candidate.pathExtension
        let stem = candidate.deletingPathExtension().lastPathComponent
        var index = 1
        while true {
            let nextName = ext.isEmpty ? "\(stem) (\(index))" : "\(stem) (\(index)).\(ext)"
            let next = directory.appendingPathComponent(nextName)
            if !FileManager.default.fileExists(atPath: next.path) { return next }
            index += 1
        }
    }

    static func safeFilename(_ value: String) -> String {
        let leaf = URL(fileURLWithPath: value).lastPathComponent
        let clean = leaf.unicodeScalars.map { scalar -> Character in
            CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "/\\:*?\"<>|")).contains(scalar) ? "_" : Character(scalar)
        }
        let result = String(clean).trimmingCharacters(in: .whitespacesAndNewlines)
        return result.isEmpty || result == "." || result == ".." ? "file-\(UUID().uuidString)" : String(result.suffix(120))
    }

    static func category(for url: URL) -> MediaCategory {
        for category in [MediaCategory.video, .audio, .image] where category.matches(url) { return category }
        return .other
    }

    static func readableSize(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}
