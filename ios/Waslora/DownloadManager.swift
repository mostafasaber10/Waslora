import Foundation
import Combine
import UniformTypeIdentifiers

enum DownloadStatus: String, Codable {
    case queued, downloading, paused, completed, failed, cancelled

    var isActive: Bool { self == .queued || self == .downloading }
}

struct DownloadRow: Identifiable, Codable {
    let id: UUID
    let url: String
    var progress: Double
    var state: String
    var status: DownloadStatus
    var filename: String?
    fileprivate var taskIdentifier: Int?
}

final class DownloadManager: NSObject, ObservableObject, URLSessionDownloadDelegate {
    static let shared = DownloadManager()
    @Published private(set) var tasks: [DownloadRow] = []

    private let identifier = "com.waslora.ios.background-downloads"
    // All records, task references and disk updates belong to this serial queue.
    private let queue = DispatchQueue(label: "com.waslora.ios.download-state")
    private var records: [DownloadRow] = []
    private var activeTasks: [UUID: URLSessionDownloadTask] = [:]
    private let storageURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Waslora/Downloads", isDirectory: true)
    private lazy var delegateQueue: OperationQueue = {
        let result = OperationQueue()
        result.name = "com.waslora.ios.download-delegate"
        result.maxConcurrentOperationCount = 1
        result.underlyingQueue = queue
        return result
    }()
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: identifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.allowsCellularAccess = true
        configuration.httpMaximumConnectionsPerHost = 4
        return URLSession(configuration: configuration, delegate: self, delegateQueue: delegateQueue)
    }()
    // Accessed only on the main queue, including the application delegate's registration.
    private var completionHandler: (() -> Void)?

    private override init() {
        super.init()
        if let data = try? Data(contentsOf: historyURL),
           let saved = try? JSONDecoder().decode([DownloadRow].self, from: data) {
            records = saved
        }
        try? prepareStorage()
        let savedIDs = Set(records.map(\.id))
        session.getAllTasks { [weak self] liveTasks in
            guard let self else { return }
            self.queue.async { self.restore(liveTasks, savedIDs: savedIDs) }
        }
    }

    func start(_ text: String) throws {
        let url = try Self.validURL(text)
        try queue.sync {
            try prepareStorage()
            let row = DownloadRow(id: UUID(), url: url.absoluteString, progress: 0,
                                  state: "في قائمة التنزيل", status: .queued)
            records.insert(row, at: 0)
            createTask(at: 0, useResumeData: false)
        }
    }

    func pause(_ id: UUID) {
        queue.async {
            guard let index = self.index(for: id), self.records[index].status.isActive,
                  let task = self.activeTasks[id] else { return }
            task.suspend()
            self.records[index].status = .paused
            self.records[index].state = "متوقف مؤقتًا"
            self.persistAndPublish()
        }
    }

    func resume(_ id: UUID) {
        queue.async {
            guard let index = self.index(for: id), self.records[index].status == .paused else { return }
            if let task = self.activeTasks[id] {
                self.records[index].status = .queued
                self.records[index].state = "جارٍ استكمال التنزيل…"
                self.persistAndPublish()
                task.resume()
            } else {
                self.createTask(at: index, useResumeData: true)
            }
        }
    }

    func retry(_ id: UUID) {
        queue.async {
            guard let index = self.index(for: id),
                  [.failed, .cancelled].contains(self.records[index].status) else { return }
            self.createTask(at: index, useResumeData: true)
        }
    }

    func cancel(_ id: UUID) {
        queue.async {
            guard let index = self.index(for: id), self.records[index].status != .completed else { return }
            self.records[index].status = .cancelled
            self.records[index].state = "تم إلغاء التنزيل"
            self.records[index].taskIdentifier = nil
            self.activeTasks.removeValue(forKey: id)?.cancel()
            self.removeResumeData(for: id)
            self.persistAndPublish()
        }
    }

    func setBackgroundCompletionHandler(_ handler: @escaping () -> Void) {
        // UIApplicationDelegate registers on main. The delegate completion also hops
        // to main, so initialization cannot race registration while main is occupied.
        if Thread.isMainThread { completionHandler = handler }
        else { DispatchQueue.main.async { self.completionHandler = handler } }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let index = currentIndex(for: downloadTask), records[index].status.isActive else { return }
        let progress = totalBytesExpectedToWrite > 0
            ? min(1, max(0, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite))) : 0
        records[index].progress = progress
        records[index].status = .downloading
        records[index].state = totalBytesExpectedToWrite > 0
            ? "\(Int(progress * 100))% · \(WasloraFiles.readableSize(totalBytesWritten))"
            : WasloraFiles.readableSize(totalBytesWritten)
        // Progress need not rewrite the history on every network chunk.
        publish()
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let index = currentIndex(for: downloadTask), records[index].status != .cancelled else { return }
        do {
            let response = try Self.validateFile(at: location, response: downloadTask.response)
            var suggested = response.suggestedFilename ?? downloadTask.originalRequest?.url?.lastPathComponent ?? "download"
            if URL(fileURLWithPath: suggested).pathExtension.isEmpty,
               let mime = response.mimeType, let ext = UTType(mimeType: mime)?.preferredFilenameExtension {
                suggested += ".\(ext)"
            }
            // This move must finish before the URLSession callback returns; its
            // temporary download URL is deleted by the system afterwards.
            let destination = try WasloraFiles.commitTemporaryFile(location, named: suggested)
            records[index].filename = destination.lastPathComponent
            records[index].progress = 1
            records[index].status = .completed
            records[index].state = "اكتمل: \(destination.lastPathComponent)"
            removeResumeData(for: records[index].id)
        } catch {
            records[index].status = .failed
            records[index].state = error.localizedDescription
        }
        persistAndPublish()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let index = currentIndex(for: task) else { return }
        let id = records[index].id
        activeTasks.removeValue(forKey: id)
        records[index].taskIdentifier = nil
        if let error, records[index].status != .cancelled && records[index].status != .completed {
            let nsError = error as NSError
            if let data = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data, !data.isEmpty {
                try? data.write(to: resumeURL(for: id), options: .atomic)
            }
            records[index].status = .failed
            records[index].state = "فشل التنزيل: \(error.localizedDescription)"
        }
        persistAndPublish()
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            let handler = self.completionHandler
            self.completionHandler = nil
            handler?()
        }
    }

    private func restore(_ liveTasks: [URLSessionTask], savedIDs: Set<UUID>) {
        var liveIDs = Set<UUID>()
        for case let task as URLSessionDownloadTask in liveTasks {
            let id = task.taskDescription.flatMap(UUID.init(uuidString:)) ?? UUID()
            task.taskDescription = id.uuidString
            liveIDs.insert(id)
            let index: Int
            if let existing = self.index(for: id) { index = existing }
            else {
                guard let url = task.originalRequest?.url ?? task.currentRequest?.url else { task.cancel(); continue }
                records.insert(DownloadRow(id: id, url: url.absoluteString, progress: 0,
                                           state: "جارٍ استعادة التنزيل…", status: .queued), at: 0)
                index = 0
            }
            if let current = activeTasks[id], current.taskIdentifier != task.taskIdentifier {
                // getAllTasks is a snapshot; a user retry may have replaced it
                // while the asynchronous snapshot was in flight.
                continue
            }
            guard ![.completed, .cancelled, .failed].contains(records[index].status) else {
                // A completion delegate may already have processed this snapshot.
                if task.state != .completed { task.cancel() }
                continue
            }
            records[index].taskIdentifier = task.taskIdentifier
            activeTasks[id] = task
            if records[index].status == .paused {
                if task.state != .suspended { task.suspend() }
                records[index].state = "متوقف مؤقتًا"
            } else {
                records[index].status = .queued
                records[index].state = "جارٍ استكمال التنزيل…"
                task.resume()
            }
        }
        for index in records.indices where savedIDs.contains(records[index].id) {
            if records[index].status.isActive && !liveIDs.contains(records[index].id) && activeTasks[records[index].id] == nil {
                records[index].status = .failed
                records[index].state = "توقف التنزيل. اضغط إعادة المحاولة لاستكماله أو البدء من جديد."
                records[index].taskIdentifier = nil
            }
        }
        persistAndPublish()
    }

    private func createTask(at index: Int, useResumeData: Bool) {
        let id = records[index].id
        guard let url = try? Self.validURL(records[index].url) else {
            records[index].status = .failed
            records[index].state = "أدخل رابط ملف HTTPS صالحًا."
            persistAndPublish()
            return
        }
        let task: URLSessionDownloadTask
        if useResumeData, let data = try? Data(contentsOf: resumeURL(for: id)), !data.isEmpty {
            task = session.downloadTask(withResumeData: data)
            records[index].state = "جارٍ محاولة استكمال التنزيل…"
        } else {
            task = session.downloadTask(with: url)
            records[index].progress = 0
            records[index].state = useResumeData ? "جارٍ إعادة التنزيل من البداية…" : "في قائمة التنزيل"
        }
        task.taskDescription = id.uuidString
        records[index].taskIdentifier = task.taskIdentifier
        records[index].filename = nil
        records[index].status = .queued
        activeTasks[id] = task
        removeResumeData(for: id)
        persistAndPublish()
        task.resume()
    }

    private func currentIndex(for task: URLSessionTask) -> Int? {
        let id = task.taskDescription.flatMap(UUID.init(uuidString:)) ?? UUID()
        if let index = index(for: id) {
            // Ignore late callbacks from a cancelled task after a new retry has
            // already taken ownership of the same history row.
            return records[index].taskIdentifier == task.taskIdentifier ? index : nil
        }
        // Background events can arrive before getAllTasks completes, including
        // when history was lost. Adopt the task immediately so its temporary file
        // is still saved within didFinishDownloadingTo.
        guard let url = task.originalRequest?.url ?? task.currentRequest?.url else { return nil }
        task.taskDescription = id.uuidString
        records.insert(DownloadRow(id: id, url: url.absoluteString, progress: 0,
                                   state: "جارٍ استعادة التنزيل…", status: .queued,
                                   taskIdentifier: task.taskIdentifier), at: 0)
        if let downloadTask = task as? URLSessionDownloadTask { activeTasks[id] = downloadTask }
        return 0
    }

    private func index(for id: UUID) -> Int? { records.firstIndex(where: { $0.id == id }) }
    private var historyURL: URL { storageURL.appendingPathComponent("history.json") }
    private func resumeURL(for id: UUID) -> URL { storageURL.appendingPathComponent("\(id.uuidString).resume") }
    private func removeResumeData(for id: UUID) { try? FileManager.default.removeItem(at: resumeURL(for: id)) }

    private func prepareStorage() throws {
        try FileManager.default.createDirectory(at: storageURL, withIntermediateDirectories: true)
        var folder = storageURL
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try folder.setResourceValues(values)
    }

    private func persistAndPublish() {
        do {
            try prepareStorage()
            try JSONEncoder().encode(records).write(to: historyURL, options: .atomic)
        } catch {
            // A history failure must not discard a successfully downloaded file.
            // Keep the current state visible; recovery is best effort if storage is full.
        }
        publish()
    }

    private func publish() {
        let snapshot = records
        DispatchQueue.main.async { self.tasks = snapshot }
    }

    private static func validURL(_ text: String) throws -> URL {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: value), let url = components.url,
              components.scheme?.lowercased() == "https", let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil else {
            throw failure("أدخل رابط ملف HTTPS صالحًا؛ روابط صفحات المنصات لا تُحوّل إلى فيديو في نسخة الآيفون الحالية.")
        }
        return url
    }

    private static func validateFile(at url: URL, response: URLResponse?) throws -> HTTPURLResponse {
        guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else {
            throw failure("لم يُرجع الرابط ملفًا قابلًا للتنزيل. تحقق من الرابط وحاول مرة أخرى.")
        }
        let mime = response.mimeType?.lowercased() ?? ""
        guard !mime.hasPrefix("text/"), !mime.contains("json"), !mime.contains("xml"),
              !mime.contains("mpegurl"), !mime.contains("dash") else {
            throw failure("الرابط صفحة ويب أو قائمة بث؛ أدخل رابط ملف فيديو أو صوت مباشر.")
        }
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        guard let size = attributes[.size] as? NSNumber, size.int64Value > 0 else {
            throw failure("الملف المستلم فارغ؛ لم يكتمل التنزيل.")
        }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        if let prefix = try handle.read(upToCount: 512),
           let text = String(data: prefix, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
           ["<!doctype html", "<html", "<head", "<body", "#extm3u"].contains(where: { text.hasPrefix($0) }) {
            throw failure("الرابط أعاد صفحة أو قائمة بث بدل ملف فيديو مباشر.")
        }
        return response
    }

    private static func failure(_ message: String) -> NSError {
        NSError(domain: "WasloraDownload", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}
