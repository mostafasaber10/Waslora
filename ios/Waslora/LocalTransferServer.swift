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
    private var token = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()

    func start() {
        guard listener == nil else { return }
        do {
            token = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
            let newListener = try NWListener(using: .tcp, on: .any)
            newListener.newConnectionHandler = { [weak self] connection in
                guard let self else { connection.cancel(); return }
                TransferConnection(connection: connection, token: self.token, directory: WasloraFiles.directory).start()
            }
            newListener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    guard let port = newListener.port, let ip = Self.localIPv4() else {
                        DispatchQueue.main.async { self.message = "تعذر العثور على عنوان Wi‑Fi. تحقق من اتصال الآيفون والكمبيوتر بالشبكة نفسها." }
                        return
                    }
                    DispatchQueue.main.async {
                        self.address = "http://\(ip):\(port.rawValue)/\(self.token)/"
                        self.isRunning = true
                        self.message = "افتح الرابط في متصفح الكمبيوتر المتصل بالشبكة نفسها."
                    }
                case .failed(let error):
                    DispatchQueue.main.async {
                        self.message = "تعذر تشغيل النقل: \(error.localizedDescription)"
                        self.isRunning = false
                        self.address = nil
                    }
                    newListener.cancel()
                    self.listener = nil
                case .cancelled:
                    DispatchQueue.main.async { self.isRunning = false; self.address = nil }
                default: break
                }
            }
            listener = newListener
            newListener.start(queue: queue)
            DispatchQueue.main.async { self.message = "جارٍ تشغيل صفحة النقل المحلية…" }
        } catch {
            message = "تعذر تشغيل النقل المحلي: \(error.localizedDescription)"
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        address = nil
        isRunning = false
        message = "تم إيقاف النقل المحلي."
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
    private var buffer = Data()
    private var expectedBytes: Int64 = 0
    private var receivedBytes: Int64 = 0
    private var uploadHandle: FileHandle?
    private var temporaryURL: URL?
    private var finalURL: URL?
    private var requestPath = ""

    init(connection: NWConnection, token: String, directory: URL) {
        self.connection = connection
        self.token = token
        self.directory = directory
    }

    func start() {
        connection.start(queue: .global(qos: .userInitiated))
        receive()
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error { self.fail(error.localizedDescription); return }
            if let data, !data.isEmpty {
                if self.uploadHandle != nil { self.consumeUpload(data) }
                else { self.buffer.append(data); self.parseRequestIfReady() }
            }
            if isComplete {
                if self.uploadHandle != nil && self.receivedBytes < self.expectedBytes { self.fail("اكتمل الاتصال قبل نهاية الملف.") }
                else if self.uploadHandle == nil { self.connection.cancel() }
            } else if self.connection.state != .cancelled && !self.didRespond {
                self.receive()
            }
        }
    }

    private var didRespond = false

    private func parseRequestIfReady() {
        guard !didRespond,
              let marker = buffer.range(of: Data([13, 10, 13, 10])) else {
            if buffer.count > 32 * 1024 { respond(status: 431, reason: "Request Header Fields Too Large", body: Data()) }
            return
        }
        let headerData = buffer.subdata(in: 0..<marker.lowerBound)
        let body = buffer.subdata(in: marker.upperBound..<buffer.endIndex)
        buffer.removeAll(keepingCapacity: false)
        guard let text = String(data: headerData, encoding: .utf8) else { respond(status: 400, reason: "Bad Request", body: Data()); return }
        let lines = text.components(separatedBy: "\r\n")
        let first = lines.first?.split(separator: " ") ?? []
        guard first.count >= 2 else { respond(status: 400, reason: "Bad Request", body: Data()); return }
        let method = String(first[0]).uppercased()
        let target = String(first[1])
        let headers = Dictionary(lines.dropFirst().compactMap { line -> (String, String)? in
            guard let colon = line.firstIndex(of: ":") else { return nil }
            return (line[..<colon].lowercased().trimmingCharacters(in: .whitespaces), line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces))
        }, uniquingKeysWith: { _, latest in latest })
        guard let components = URLComponents(string: "http://local\(target)") else { respond(status: 400, reason: "Bad Request", body: Data()); return }
        requestPath = components.percentEncodedPath.removingPercentEncoding ?? components.path
        let prefix = "/\(token)/"
        guard requestPath.hasPrefix(prefix) else { respond(status: 403, reason: "Forbidden", body: Data()); return }
        let route = String(requestPath.dropFirst(prefix.count))

        if method == "GET" && (route.isEmpty || route == "index.html") { respond(status: 200, reason: "OK", type: "text/html; charset=utf-8", body: Data(Self.page.utf8)); return }
        if method == "GET" && route == "api/files" { sendFileList(); return }
        if method == "GET" && route.hasPrefix("file/") { sendFile(named: String(route.dropFirst(5))); return }
        if method == "POST" && route == "upload" { beginUpload(components: components, headers: headers, initialBody: body); return }
        respond(status: 404, reason: "Not Found", body: Data())
    }

    private func beginUpload(components: URLComponents, headers: [String: String], initialBody: Data) {
        guard let value = components.queryItems?.first(where: { $0.name == "name" })?.value, !value.isEmpty else {
            respond(status: 400, reason: "Bad Request", body: Data("filename required".utf8)); return
        }
        guard let contentLength = Int64(headers["content-length"] ?? ""), contentLength >= 0 else {
            respond(status: 411, reason: "Length Required", body: Data()); return
        }
        guard contentLength <= 100 * 1024 * 1024 * 1024 else {
            respond(status: 413, reason: "Payload Too Large", body: Data()); return
        }
        expectedBytes = contentLength
        finalURL = WasloraFiles.uniqueURL(for: WasloraFiles.safeFilename(value), in: directory)
        temporaryURL = finalURL!.appendingPathExtension("part")
        FileManager.default.createFile(atPath: temporaryURL!.path, contents: nil)
        do { uploadHandle = try FileHandle(forWritingTo: temporaryURL!) }
        catch { respond(status: 500, reason: "Internal Server Error", body: Data()); return }
        consumeUpload(initialBody)
        if expectedBytes == 0 { finishUpload() }
    }

    private func consumeUpload(_ data: Data) {
        guard let uploadHandle, receivedBytes < expectedBytes else { return }
        let remaining = expectedBytes - receivedBytes
        let count = min(data.count, Int(min(remaining, Int64(Int.max))))
        do {
            try uploadHandle.write(contentsOf: data.prefix(count))
            receivedBytes += Int64(count)
            if receivedBytes == expectedBytes { finishUpload() }
        } catch { fail(error.localizedDescription) }
    }

    private func finishUpload() {
        guard !didRespond, let uploadHandle, let temporaryURL, let finalURL else { return }
        do {
            try uploadHandle.synchronize()
            try uploadHandle.close()
            self.uploadHandle = nil
            try FileManager.default.moveItem(at: temporaryURL, to: finalURL)
            let payload: [String: Any] = ["ok": true, "name": finalURL.lastPathComponent, "size": receivedBytes]
            let data = (try? JSONSerialization.data(withJSONObject: payload)) ?? Data("{}".utf8)
            respond(status: 201, reason: "Created", type: "application/json; charset=utf-8", body: data)
        } catch { fail(error.localizedDescription) }
    }

    private func sendFileList() {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey], options: [.skipsHiddenFiles])) ?? []
        let objects: [[String: Any]] = files.compactMap { url in
            guard !url.lastPathComponent.hasSuffix(".part"),
                  let values = try? url.resourceValues(forKeys: [.fileSizeKey]),
                  let size = values.fileSize else { return nil }
            return ["name": url.lastPathComponent, "size": size]
        }
        let body = (try? JSONSerialization.data(withJSONObject: objects)) ?? Data("[]".utf8)
        respond(status: 200, reason: "OK", type: "application/json; charset=utf-8", body: body)
    }

    private func sendFile(named value: String) {
        let name = WasloraFiles.safeFilename(value)
        guard let url = try? directory.appendingPathComponent(name).resolvingSymlinksInPath(),
              url.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL,
              let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = attributes[.size] as? NSNumber,
              let handle = try? FileHandle(forReadingFrom: url) else {
            respond(status: 404, reason: "Not Found", body: Data()); return
        }
        let ascii = name.unicodeScalars.map { CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-").contains($0) ? Character($0) : "_" }
        let header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: \(size.int64Value)\r\nContent-Disposition: attachment; filename=\"\(String(ascii))\"\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
        didRespond = true
        connection.send(content: Data(header.utf8), completion: .contentProcessed { [weak self] error in
            guard let self else { try? handle.close(); return }
            if let error { try? handle.close(); self.connection.cancel(); return }
            self.stream(handle)
        })
    }

    private func stream(_ handle: FileHandle) {
        do {
            guard let chunk = try handle.read(upToCount: 512 * 1024), !chunk.isEmpty else {
                try handle.close()
                connection.send(content: nil, isComplete: true, completion: .contentProcessed { [weak self] _ in self?.connection.cancel() })
                return
            }
            connection.send(content: chunk, isComplete: false, completion: .contentProcessed { [weak self] error in
                guard let self else { try? handle.close(); return }
                if error != nil { try? handle.close(); self.connection.cancel() }
                else { self.stream(handle) }
            })
        } catch { try? handle.close(); connection.cancel() }
    }

    private func respond(status: Int, reason: String, type: String = "text/plain; charset=utf-8", body: Data) {
        guard !didRespond else { return }
        didRespond = true
        var response = Data("HTTP/1.1 \(status) \(reason)\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".utf8)
        response.append(body)
        connection.send(content: response, completion: .contentProcessed { [weak self] _ in self?.connection.cancel() })
    }

    private func fail(_ message: String) {
        try? uploadHandle?.close()
        uploadHandle = nil
        if let temporaryURL { try? FileManager.default.removeItem(at: temporaryURL) }
        if !didRespond { respond(status: 500, reason: "Transfer Failed", body: Data(message.utf8)) }
        else { connection.cancel() }
    }

    private static let page = #"""
    <!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Waslora</title><style>body{font:16px system-ui;background:#f4f6fb;color:#15203b;max-width:760px;margin:24px auto;padding:0 16px}section{background:white;border:1px solid #e2e7f0;border-radius:18px;padding:18px;margin:14px 0}button,.pick,a{background:#27b9aa;color:#082d35;border:0;border-radius:10px;padding:12px 16px;font-weight:700;text-decoration:none;cursor:pointer}input[type=file]{display:none}.row{display:flex;gap:12px;align-items:center;padding:12px 0;border-bottom:1px solid #eee}.meta{flex:1;min-width:0}.meta strong,.meta small{display:block;overflow:hidden;text-overflow:ellipsis}.meta small,#status{color:#68738b;font-size:13px}</style><h1>Waslora · نقل الملفات</h1><section><p>أرسل ملفات من الكمبيوتر إلى الآيفون عبر شبكة Wi-Fi نفسها.</p><label class="pick">اختيار ملفات<input id="files" type="file" multiple></label><p id="status">اختر الملفات لبدء النقل.</p></section><section><h2>ملفات الآيفون</h2><div id="list">جارٍ التحميل…</div></section><script>const base=location.pathname.endsWith('/')?location.pathname:location.pathname+'/';const status=document.getElementById('status');const size=n=>{const u=['بايت','KB','MB','GB','TB'];let i=0;while(n>=1024&&i<u.length-1){n/=1024;i++}return n.toFixed(i?1:0)+' '+u[i]};async function refresh(){try{const r=await fetch(base+'api/files');const files=await r.json();const list=document.getElementById('list');list.replaceChildren();if(!files.length){list.textContent='لا توجد ملفات محفوظة.';return}for(const f of files){const row=document.createElement('div');row.className='row';const meta=document.createElement('div');meta.className='meta';const name=document.createElement('strong');name.textContent=f.name;const bytes=document.createElement('small');bytes.textContent=size(f.size);meta.append(name,bytes);const a=document.createElement('a');a.textContent='تنزيل';a.href=base+'file/'+encodeURIComponent(f.name);a.download=f.name;row.append(meta,a);list.append(row)}}catch(e){document.getElementById('list').textContent='تعذر الاتصال بالآيفون.'}}function upload(file){return new Promise((resolve,reject)=>{const x=new XMLHttpRequest();x.open('POST',base+'upload?name='+encodeURIComponent(file.name));x.setRequestHeader('Content-Type','application/octet-stream');x.upload.onprogress=e=>{if(e.lengthComputable)status.textContent='جارٍ إرسال '+file.name+'… '+Math.floor(e.loaded*100/e.total)+'%'};x.onload=()=>x.status>=200&&x.status<300?resolve():reject(new Error('HTTP '+x.status));x.onerror=()=>reject(new Error('انقطع الاتصال'));x.send(file)})}document.getElementById('files').onchange=async e=>{for(const f of e.target.files){try{await upload(f);status.textContent='تم إرسال '+f.name+' · '+size(f.size)}catch(err){status.textContent='تعذر إرسال '+f.name+': '+err.message;break}}refresh()};refresh();setInterval(refresh,10000);</script></html>
    """#
}
