import Foundation
import Combine

final class LibraryStore: ObservableObject {
    @Published private(set) var files: [URL] = []

    func reload() {
        let folder = WasloraFiles.directory
        let values = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey], options: [.skipsHiddenFiles])) ?? []
        files = values.filter { !$0.lastPathComponent.hasSuffix(".part") }
            .sorted { lhs, rhs in
                let left = (try? lhs.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let right = (try? rhs.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return left > right
            }
    }

    func delete(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
        reload()
    }
}
