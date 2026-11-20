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
    private static let saveLock = NSLock()

    static var directory: URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let folder = documents.appendingPathComponent("Waslora", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    static func uniqueURL(for name: String, in directory: URL = directory) -> URL {
        let safeName = safeFilename(name)
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

    // All writers share this lock so simultaneous downloads, conversions and
    // transfers with the same filename cannot replace one another.
    static func commitTemporaryFile(_ temporaryURL: URL, named name: String, in directory: URL = directory) throws -> URL {
        saveLock.lock()
        defer { saveLock.unlock() }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = uniqueURL(for: name, in: directory)
        try FileManager.default.moveItem(at: temporaryURL, to: destination)
        return destination
    }

    static func importFile(_ source: URL) throws -> URL {
        let ownsAccess = source.startAccessingSecurityScopedResource()
        defer { if ownsAccess { source.stopAccessingSecurityScopedResource() } }
        let temporary = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: temporary) }
        var coordinationError: NSError?
        var copyError: Error?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: source, options: [], error: &coordinationError) { readableURL in
            do {
                guard try readableURL.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else {
                    throw NSError(domain: "WasloraFiles", code: 1, userInfo: [NSLocalizedDescriptionKey: "اختر ملفًا وليس مجلدًا."])
                }
                try FileManager.default.copyItem(at: readableURL, to: temporary)
            } catch { copyError = error }
        }
        if let coordinationError { throw coordinationError }
        if let copyError { throw copyError }
        return try commitTemporaryFile(temporary, named: source.lastPathComponent)
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
