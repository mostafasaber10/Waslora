import Foundation
import Combine

struct DownloadRow: Identifiable {
    let id: Int
    let url: String
    var progress: Double
    var state: String
}

final class DownloadManager: NSObject, ObservableObject, URLSessionDownloadDelegate {
    static let shared = DownloadManager()
    @Published private(set) var tasks: [DownloadRow] = []

    private let identifier = "com.waslora.ios.background-downloads"
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: identifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.allowsCellularAccess = true
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()
    private var completionHandler: (() -> Void)?

    private override init() {
        super.init()
        _ = session
    }

    func start(_ text: String) throws {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let components = URLComponents(string: value)
        guard let url = components?.url, ["http", "https"].contains(components?.scheme?.lowercased() ?? ""),
              components?.host != nil, components?.user == nil, components?.password == nil else {
            throw NSError(domain: "WasloraDownload", code: 1, userInfo: [NSLocalizedDescriptionKey: "أدخل رابط HTTPS أو HTTP صالحًا."])
        }
        let task = session.downloadTask(with: url)
        DispatchQueue.main.async {
            self.tasks.insert(DownloadRow(id: task.taskIdentifier, url: value, progress: 0, state: "في قائمة التنزيل"), at: 0)
        }
        task.resume()
    }

    func cancel(_ id: Int) {
        session.getAllTasks { tasks in tasks.first(where: { $0.taskIdentifier == id })?.cancel() }
        DispatchQueue.main.async { self.tasks.removeAll { $0.id == id } }
    }

    func setBackgroundCompletionHandler(_ handler: @escaping () -> Void) {
        completionHandler = handler
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let progress = totalBytesExpectedToWrite > 0 ? Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) : 0
        DispatchQueue.main.async {
            guard let index = self.tasks.firstIndex(where: { $0.id == downloadTask.taskIdentifier }) else { return }
            self.tasks[index].progress = progress
            self.tasks[index].state = totalBytesExpectedToWrite > 0
                ? "\(Int(progress * 100))% · \(WasloraFiles.readableSize(totalBytesWritten))"
                : WasloraFiles.readableSize(totalBytesWritten)
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let response = downloadTask.response as? HTTPURLResponse
        let mime = response?.mimeType?.lowercased() ?? ""
        guard let status = response?.statusCode, (200..<300).contains(status),
              !mime.contains("text/html"), !mime.contains("application/json") else {
            update(downloadTask.taskIdentifier, state: "الرابط صفحة ويب أو لم يُرجع ملفًا مباشرًا")
            return
        }
        let suggested = response?.suggestedFilename ?? downloadTask.originalRequest?.url?.lastPathComponent ?? "download"
        let destination = WasloraFiles.uniqueURL(for: WasloraFiles.safeFilename(suggested))
        do {
            try FileManager.default.moveItem(at: location, to: destination)
            update(downloadTask.taskIdentifier, state: "اكتمل: \(destination.lastPathComponent)", progress: 1)
        } catch {
            update(downloadTask.taskIdentifier, state: "تعذر حفظ الملف: \(error.localizedDescription)")
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        update(task.taskIdentifier, state: "فشل التنزيل: \(error.localizedDescription)")
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            self.completionHandler?()
            self.completionHandler = nil
        }
    }

    private func update(_ id: Int, state: String, progress: Double? = nil) {
        DispatchQueue.main.async {
            guard let index = self.tasks.firstIndex(where: { $0.id == id }) else { return }
            self.tasks[index].state = state
            if let progress { self.tasks[index].progress = progress }
        }
    }
}
