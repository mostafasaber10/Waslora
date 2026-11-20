import Foundation
import Network
import Darwin
import Combine

final class LocalTransferServer: ObservableObject {
    @Published private(set) var address: String?
    @Published private(set) var isRunning = false
    @Published private(set) var message = "شغّل النقل عندما يكون الكمبيوتر والآيفون على شبكة Wi‑Fi نفسها."

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "com.waslora.ios.local-transfer", qos: .userInitiated)
    // All listener, connection, and file finalization work stays on this serial queue.
    private var connections: [UUID: TransferConnection] = [:]

    func start() {
        queue.async { [weak self] in
            guard let self, self.listener == nil else { return }
            do {
                let token = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
                let directory = WasloraFiles.directory
                let newListener = try NWListener(using: .tcp, on: .any)
                newListener.newConnectionHandler = { [weak self, weak newListener] connection in
                    guard let self, let newListener, self.listener === newListener,
                          self.connections.count < 16 else {
                        connection.cancel()
                        return
                    }
                    let id = UUID()
                    let transfer = TransferConnection(connection: connection, token: token,
                                                      directory: directory, queue: self.queue) { [weak self] in
                        self?.connections.removeValue(forKey: id)
                    }
                    self.connections[id] = transfer
                    transfer.start()
                }
                newListener.stateUpdateHandler = { [weak self, weak newListener] state in
                    guard let self, let newListener, self.listener === newListener else { return }
                    switch state {
                    case .ready:
                        guard let port = newListener.port, let ip = Self.localIPv4() else {
                            self.stopOnQueue(message: "تعذر العثور على عنوان Wi‑Fi. تحقق من اتصال الآيفون والكمبيوتر بالشبكة نفسها.")
                            return
                        }
                        self.publish(address: "http://\(ip):\(port.rawValue)/\(token)/",
                                     message: "افتح الرابط في متصفح الكمبيوتر المتصل بالشبكة نفسها.")
                    case .failed(let error):
                        self.stopOnQueue(message: "تعذر تشغيل النقل: \(error.localizedDescription)")
                    case .cancelled:
                        self.stopOnQueue(message: "تم إيقاف النقل المحلي.")
                    default:
                        break
                    }
                }
                self.listener = newListener
                self.publish(address: nil, message: "جارٍ تشغيل صفحة النقل المحلية…")
                newListener.start(queue: self.queue)
            } catch {
                self.publish(address: nil, message: "تعذر تشغيل النقل المحلي: \(error.localizedDescription)")
            }
        }
    }

    func stop() {
        queue.async { [weak self] in self?.stopOnQueue(message: "تم إيقاف النقل المحلي.") }
    }

    private func stopOnQueue(message: String) {
        listener?.newConnectionHandler = nil
        listener?.stateUpdateHandler = nil
        listener?.cancel()
        listener = nil
        let active = Array(connections.values)
        connections.removeAll()
        active.forEach { $0.cancel() }
        publish(address: nil, message: message)
    }

    private func publish(address: String?, message: String) {
        DispatchQueue.main.async { [weak self] in
            self?.address = address
            self?.isRunning = address != nil
            self?.message = message
        }
    }

    private static func localIPv4() -> String? {
        var firstAddress: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&firstAddress) == 0, let firstAddress else { return nil }
        defer { freeifaddrs(firstAddress) }
        var current: UnsafeMutablePointer<ifaddrs>? = firstAddress
        while let item = current {
            let interface = String(cString: item.pointee.ifa_name)
            if interface == "en0", let address = item.pointee.ifa_addr,
               Int32(address.pointee.sa_family) == AF_INET {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                let result = getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
                if result == 0 { return String(cString: host) }
            }
            current = item.pointee.ifa_next
        }
        return nil
    }
}

private final class TransferConnection {
    private let connection: NWConnection
    private let token: String
    private let directory: URL
    private let queue: DispatchQueue
    private var onFinish: (() -> Void)?
    private var timeout: DispatchSourceTimer?
    private var buffer = Data()
    private var expectedBytes: Int64 = 0
    private var receivedBytes: Int64 = 0
    private var uploadHandle: FileHandle?
    private var downloadHandle: FileHandle?
    private var downloadBytesRemaining: Int64 = 0
    private var temporaryURL: URL?
    private var uploadName: String?
    private var didRespond = false
    private var finished = false
    private static let headerLimit = 32 * 1024
    private static let uploadLimit: Int64 = 100 * 1024 * 1024 * 1024

    init(connection: NWConnection, token: String, directory: URL, queue: DispatchQueue,
         onFinish: @escaping () -> Void) {
        self.connection = connection
        self.token = token
        self.directory = directory.resolvingSymlinksInPath().standardizedFileURL
        self.queue = queue
        self.onFinish = onFinish
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled:
                self?.cancel()
            default:
                break
            }
        }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.setEventHandler { [weak self] in self?.cancel() }
        timeout = timer
        resetTimeout()
        timer.resume()
        connection.start(queue: queue)
        receive()
    }

    func cancel() {
        guard !finished else { return }
        finished = true
        timeout?.setEventHandler {}
        timeout?.cancel()
        timeout = nil
        cleanupUpload()
        try? downloadHandle?.close()
        downloadHandle = nil
        connection.stateUpdateHandler = nil
        connection.cancel()
        let completion = onFinish
        onFinish = nil
        completion?()
    }

    private func resetTimeout() {
        timeout?.schedule(deadline: .now() + 60)
    }

    private func receive() {
        guard !finished, !didRespond else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, isComplete, error in
            guard let self, !self.finished else { return }
            if error != nil { self.cancel(); return }
            if let data, !data.isEmpty {
                self.resetTimeout()
                if self.uploadHandle != nil { self.consumeUpload(data) }
                else { self.buffer.append(data); self.parseRequestIfReady() }
            }
            // A client may half-close its request side while still awaiting our response.
            guard !self.finished, !self.didRespond else { return }
            if isComplete {
                self.reject(status: 400, reason: "Bad Request", message: "انقطع الطلب قبل اكتمال البيانات.")
            } else {
                self.receive()
            }
        }
    }

    private func parseRequestIfReady() {
        guard !didRespond, !finished else { return }
        guard let marker = buffer.range(of: Data([13, 10, 13, 10])) else {
            if buffer.count > Self.headerLimit {
                reject(status: 431, reason: "Request Header Fields Too Large")
            }
            return
        }
        guard marker.lowerBound <= Self.headerLimit else {
            reject(status: 431, reason: "Request Header Fields Too Large")
            return
        }
        let headerData = buffer.subdata(in: 0..<marker.lowerBound)
        let body = buffer.subdata(in: marker.upperBound..<buffer.endIndex)
        buffer.removeAll(keepingCapacity: false)
        guard let text = String(data: headerData, encoding: .utf8),
              !text.unicodeScalars.contains(where: { $0.value == 0 }) else {
            reject(status: 400, reason: "Bad Request"); return
        }
        let lines = text.components(separatedBy: "\r\n")
        let first = lines.first?.split(separator: " ") ?? []
        guard first.count == 3, first[2] == "HTTP/1.1" || first[2] == "HTTP/1.0" else {
            reject(status: 400, reason: "Bad Request"); return
        }
        let method = String(first[0])
        let target = String(first[1])
        var headers: [String: String] = [:]
        let fieldCharacters = CharacterSet(charactersIn: "!#$%&'*+-.^_\u{0060}|~0123456789abcdefghijklmnopqrstuvwxyz")
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else {
                reject(status: 400, reason: "Bad Request"); return
            }
            let name = String(line[..<colon]).lowercased()
            let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty, name.unicodeScalars.allSatisfy({ fieldCharacters.contains($0) }),
                  !value.unicodeScalars.contains(where: { $0.value < 32 && $0.value != 9 || $0.value == 127 }),
                  headers[name] == nil else {
                reject(status: 400, reason: "Bad Request"); return
            }
            headers[name] = value
        }
        guard first[2] != "HTTP/1.1" || !(headers["host"] ?? "").isEmpty else {
            reject(status: 400, reason: "Bad Request"); return
        }
        // Browser File uploads have a known Content-Length. Reject ambiguous framing.
        guard headers["transfer-encoding"] == nil else {
            reject(status: 501, reason: "Not Implemented", message: "أرسل الملف بطول Content-Length محدد."); return
        }
        guard headers["expect"] == nil else {
            reject(status: 417, reason: "Expectation Failed"); return
        }
        var contentLength: Int64 = 0
        if let length = headers["content-length"] {
            guard !length.isEmpty, length.utf8.allSatisfy({ (48...57).contains($0) }),
                  let parsed = Int64(length) else {
                reject(status: 400, reason: "Bad Request"); return
            }
            contentLength = parsed
        }
        guard target.hasPrefix("/"), !target.hasPrefix("//"),
              let components = URLComponents(string: target),
              components.scheme == nil, components.host == nil, components.fragment == nil,
              let requestPath = components.percentEncodedPath.removingPercentEncoding,
              !requestPath.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) else {
            reject(status: 400, reason: "Bad Request"); return
        }
        let prefix = "/\(token)/"
        guard requestPath.hasPrefix(prefix) else { respond(status: 403, reason: "Forbidden", body: Data()); return }
        let route = String(requestPath.dropFirst(prefix.count))
        if method == "POST" && route == "upload" {
            guard headers["content-length"] != nil else {
                reject(status: 411, reason: "Length Required"); return
            }
            beginUpload(components: components, contentLength: contentLength, initialBody: body)
            return
        }
        guard method == "GET" || method == "HEAD" else {
            reject(status: 405, reason: "Method Not Allowed"); return
        }
        guard contentLength == 0, body.isEmpty else {
            reject(status: 400, reason: "Bad Request"); return
        }
        let headOnly = method == "HEAD"
        if route.isEmpty || route == "index.html" {
            respond(status: 200, reason: "OK", type: "text/html; charset=utf-8",
                    body: Data(Self.page.utf8), headOnly: headOnly); return
        }
        if route == "api/files" { sendFileList(headOnly: headOnly); return }
        if route.hasPrefix("file/") { sendFile(named: String(route.dropFirst(5)), headOnly: headOnly); return }
        respond(status: 404, reason: "Not Found", body: Data())
    }

    private func beginUpload(components: URLComponents, contentLength: Int64, initialBody: Data) {
        let names = components.queryItems?.filter { $0.name == "name" } ?? []
        guard names.count == 1, let value = names.first?.value, !value.isEmpty else {
            reject(status: 400, reason: "Bad Request", message: "filename required"); return
        }
        guard contentLength <= Self.uploadLimit else {
            reject(status: 413, reason: "Payload Too Large"); return
        }
        guard Int64(initialBody.count) <= contentLength else {
            reject(status: 400, reason: "Bad Request"); return
        }
        expectedBytes = contentLength
        uploadName = WasloraFiles.safeFilename(value)
        let temporary = directory.appendingPathComponent(".upload-\(UUID().uuidString).part")
        // O_EXCL prevents an existing path or symlink from being reused.
        let descriptor = Darwin.open(temporary.path, O_CREAT | O_EXCL | O_WRONLY, mode_t(0o600))
        guard descriptor >= 0 else {
            reject(status: 500, reason: "Internal Server Error", message: "تعذر إنشاء الملف المؤقت."); return
        }
        temporaryURL = temporary
        uploadHandle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
        if expectedBytes == 0 { finishUpload() }
        else { consumeUpload(initialBody) }
    }

    private func consumeUpload(_ data: Data) {
        guard !finished, !didRespond, let uploadHandle else { return }
        let remaining = expectedBytes - receivedBytes
        guard Int64(data.count) <= remaining else {
            reject(status: 400, reason: "Bad Request", message: "حجم الملف لا يطابق Content-Length."); return
        }
        do {
            try uploadHandle.write(contentsOf: data)
            receivedBytes += Int64(data.count)
            if receivedBytes == expectedBytes { finishUpload() }
        } catch { reject(status: 500, reason: "Transfer Failed", message: error.localizedDescription) }
    }

    private func finishUpload() {
        guard !finished, !didRespond, let uploadHandle, let temporaryURL, let uploadName else { return }
        do {
            try uploadHandle.synchronize()
            try uploadHandle.close()
            self.uploadHandle = nil
            let saved = try WasloraFiles.commitTemporaryFile(temporaryURL, named: uploadName, in: directory)
            self.temporaryURL = nil
            let payload: [String: Any] = ["ok": true, "name": saved.lastPathComponent, "size": receivedBytes]
            let data = (try? JSONSerialization.data(withJSONObject: payload)) ?? Data("{}".utf8)
            respond(status: 201, reason: "Created", type: "application/json; charset=utf-8", body: data)
        } catch { reject(status: 500, reason: "Transfer Failed", message: error.localizedDescription) }
    }

    private func sendFileList(headOnly: Bool) {
        let keys: Set<URLResourceKey> = [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey]
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles])) ?? []
        let objects: [[String: Any]] = files.compactMap { url in
            guard !url.lastPathComponent.hasSuffix(".part"),
                  let values = try? url.resourceValues(forKeys: keys),
                  values.isRegularFile == true, values.isSymbolicLink != true,
                  let size = values.fileSize else { return nil }
            return ["name": url.lastPathComponent, "size": size]
        }
        let body = (try? JSONSerialization.data(withJSONObject: objects)) ?? Data("[]".utf8)
        respond(status: 200, reason: "OK", type: "application/json; charset=utf-8", body: body, headOnly: headOnly)
    }

    private func sendFile(named name: String, headOnly: Bool) {
        guard !name.isEmpty, name != ".", name != "..", !name.hasPrefix("."),
              !name.hasSuffix(".part"), !name.contains("/"), !name.contains("\\") else {
            respond(status: 404, reason: "Not Found", body: Data()); return
        }
        let candidate = directory.appendingPathComponent(name)
        let url = candidate.resolvingSymlinksInPath()
        guard let values = try? candidate.resourceValues(forKeys: [.isSymbolicLinkKey]),
              values.isSymbolicLink != true,
              url.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL,
              let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
              attributes[.type] as? FileAttributeType == .typeRegular,
              let size = attributes[.size] as? NSNumber,
              let handle = try? FileHandle(forReadingFrom: url) else {
            respond(status: 404, reason: "Not Found", body: Data()); return
        }
        downloadHandle = handle
        downloadBytesRemaining = size.int64Value
        let ascii = name.unicodeScalars.map { CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-").contains($0) ? Character($0) : "_" }
        let encoded = name.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")) ?? "file"
        let header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: \(size.int64Value)\r\nContent-Disposition: attachment; filename=\"\(String(ascii))\"; filename*=UTF-8''\(encoded)\r\n\(Self.commonHeaders)\r\n"
        didRespond = true
        connection.send(content: Data(header.utf8), completion: .contentProcessed { [weak self] error in
            guard let self, !self.finished else { return }
            if error != nil || headOnly { self.cancel(); return }
            self.resetTimeout()
            self.streamFile()
        })
    }

    private func streamFile() {
        guard !finished, let handle = downloadHandle else { return }
        guard downloadBytesRemaining > 0 else {
            connection.send(content: nil, isComplete: true, completion: .contentProcessed { [weak self] _ in self?.cancel() })
            return
        }
        do {
            let count = Int(min(downloadBytesRemaining, 512 * 1024))
            guard let chunk = try handle.read(upToCount: count), !chunk.isEmpty else {
                cancel()
                return
            }
            downloadBytesRemaining -= Int64(chunk.count)
            connection.send(content: chunk, isComplete: false, completion: .contentProcessed { [weak self] error in
                guard let self, !self.finished else { return }
                if error != nil { self.cancel(); return }
                self.resetTimeout()
                self.streamFile()
            })
        } catch { cancel() }
    }

    private static let commonHeaders =
        "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\nConnection: close\r\n"

    private func respond(status: Int, reason: String, type: String = "text/plain; charset=utf-8", body: Data, headOnly: Bool = false) {
        guard !finished, !didRespond else { return }
        didRespond = true
        var response = Data("HTTP/1.1 \(status) \(reason)\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\n\(Self.commonHeaders)\r\n".utf8)
        if !headOnly { response.append(body) }
        connection.send(content: response, isComplete: true, completion: .contentProcessed { [weak self] _ in self?.cancel() })
    }

    private func cleanupUpload() {
        try? uploadHandle?.close()
        uploadHandle = nil
        if let temporaryURL { try? FileManager.default.removeItem(at: temporaryURL) }
        temporaryURL = nil
    }

    private func reject(status: Int, reason: String, message: String = "") {
        cleanupUpload()
        buffer.removeAll(keepingCapacity: false)
        if !didRespond { respond(status: status, reason: reason, body: Data(message.utf8)) }
        else { cancel() }
    }

    private static let page = #"""
    <!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Waslora</title><style>body{font:16px system-ui;background:#f4f6fb;color:#15203b;max-width:760px;margin:24px auto;padding:0 16px}section{background:white;border:1px solid #e2e7f0;border-radius:18px;padding:18px;margin:14px 0}button,.pick,a{background:#27b9aa;color:#082d35;border:0;border-radius:10px;padding:12px 16px;font-weight:700;text-decoration:none;cursor:pointer}input[type=file]{display:none}.row{display:flex;gap:12px;align-items:center;padding:12px 0;border-bottom:1px solid #eee}.meta{flex:1;min-width:0}.meta strong,.meta small{display:block;overflow:hidden;text-overflow:ellipsis}.meta small,#status{color:#68738b;font-size:13px}</style><h1>Waslora · نقل الملفات</h1><section><p>أرسل ملفات من الكمبيوتر إلى الآيفون عبر شبكة Wi-Fi نفسها.</p><label class="pick">اختيار ملفات<input id="files" type="file" multiple></label><p id="status">اختر الملفات لبدء النقل.</p></section><section><h2>ملفات الآيفون</h2><div id="list">جارٍ التحميل…</div></section><script>const base=location.pathname.endsWith('/')?location.pathname:location.pathname+'/';const status=document.getElementById('status');const size=n=>{const u=['بايت','KB','MB','GB','TB'];let i=0;while(n>=1024&&i<u.length-1){n/=1024;i++}return n.toFixed(i?1:0)+' '+u[i]};async function refresh(){try{const r=await fetch(base+'api/files');const files=await r.json();const list=document.getElementById('list');list.replaceChildren();if(!files.length){list.textContent='لا توجد ملفات محفوظة.';return}for(const f of files){const row=document.createElement('div');row.className='row';const meta=document.createElement('div');meta.className='meta';const name=document.createElement('strong');name.textContent=f.name;const bytes=document.createElement('small');bytes.textContent=size(f.size);meta.append(name,bytes);const a=document.createElement('a');a.textContent='تنزيل';a.href=base+'file/'+encodeURIComponent(f.name);a.download=f.name;row.append(meta,a);list.append(row)}}catch(e){document.getElementById('list').textContent='تعذر الاتصال بالآيفون.'}}function upload(file){return new Promise((resolve,reject)=>{const x=new XMLHttpRequest();x.open('POST',base+'upload?name='+encodeURIComponent(file.name));x.setRequestHeader('Content-Type','application/octet-stream');x.upload.onprogress=e=>{if(e.lengthComputable)status.textContent='جارٍ إرسال '+file.name+'… '+Math.floor(e.loaded*100/e.total)+'%'};x.onload=()=>x.status>=200&&x.status<300?resolve():reject(new Error('HTTP '+x.status));x.onerror=()=>reject(new Error('انقطع الاتصال'));x.send(file)})}document.getElementById('files').onchange=async e=>{for(const f of e.target.files){try{await upload(f);status.textContent='تم إرسال '+f.name+' · '+size(f.size)}catch(err){status.textContent='تعذر إرسال '+f.name+': '+err.message;break}}refresh()};refresh();setInterval(refresh,10000);</script></html>
    """#
}
