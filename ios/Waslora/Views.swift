import SwiftUI
import UniformTypeIdentifiers
import QuickLook

struct MainTabsView: View {
    var body: some View {
        TabView {
            DownloadView().tabItem { Label("تنزيل", systemImage: "arrow.down.circle") }
            LibraryView().tabItem { Label("المكتبة", systemImage: "square.grid.2x2") }
            ConvertView().tabItem { Label("تحويل", systemImage: "waveform") }
            TransferView().tabItem { Label("نقل", systemImage: "wifi") }
        }
        .environment(\.layoutDirection, .rightToLeft)
    }
}

struct DownloadView: View {
    @EnvironmentObject private var downloads: DownloadManager
    @EnvironmentObject private var library: LibraryStore
    @State private var link = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(spacing: 12) {
                            Image("BrandMark").resizable().frame(width: 50, height: 50)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                            VStack(alignment: .leading, spacing: 3) {
                                Text("Waslora").font(.title2.bold())
                                Text("TATA STUDIO").font(.caption.weight(.semibold)).tracking(2).foregroundStyle(.secondary)
                            }
                        }.environment(\.layoutDirection, .leftToRight)
                        Text("نزّل الملفات المباشرة إلى الآيفون")
                            .font(.headline)
                        Text("ألصق رابط ملف فيديو أو صوت مباشر تملك حق تنزيله. روابط صفحات YouTube وFacebook وInstagram وTikTok لا تُحوّل إلى ملفات هنا.")
                            .font(.subheadline).foregroundStyle(.secondary)
                        TextField("https://example.com/video.mp4", text: $link)
                            .textInputAutocapitalization(.never)
                            .keyboardType(.URL)
                            .autocorrectionDisabled()
                            .textFieldStyle(.roundedBorder)
                            .environment(\.layoutDirection, .leftToRight)
                        Button {
                            do {
                                try downloads.start(link)
                                link = ""
                                error = nil
                            } catch { self.error = error.localizedDescription }
                        } label: {
                            Label("ابدأ التنزيل", systemImage: "arrow.down.to.line")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding(.vertical, 8)
                } header: { Text("تنزيل مباشر") }

                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }

                Section("التنزيلات") {
                    if downloads.tasks.isEmpty {
                        EmptyStateView(title: "لا توجد تنزيلات", icon: "arrow.down.circle", detail: "ستظهر حالة الملفات هنا.")
                    } else {
                        ForEach(downloads.tasks) { task in
                            VStack(alignment: .leading, spacing: 7) {
                                Text(task.filename ?? URL(string: task.url)?.lastPathComponent ?? task.url)
                                    .font(.subheadline.weight(.medium)).lineLimit(1)
                                ProgressView(value: task.progress)
                                HStack {
                                    Text(task.state).font(.caption).foregroundStyle(.secondary)
                                    Spacer()
                                }
                                HStack(spacing: 16) {
                                    switch task.status {
                                    case .queued, .downloading:
                                        Button("إيقاف مؤقت") { downloads.pause(task.id) }
                                        Button("إلغاء", role: .destructive) { downloads.cancel(task.id) }
                                    case .paused:
                                        Button("استكمال") { downloads.resume(task.id) }
                                        Button("إلغاء", role: .destructive) { downloads.cancel(task.id) }
                                    case .failed, .cancelled:
                                        Button("إعادة المحاولة") { downloads.retry(task.id) }
                                    case .completed:
                                        Text("محفوظ في المكتبة").foregroundStyle(.secondary)
                                    }
                                }.font(.caption).buttonStyle(.borderless)
                            }.padding(.vertical, 4)
                        }
                    }
                }
            }
            .navigationTitle("Waslora")
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Image(systemName: "arrow.left.arrow.right") .foregroundStyle(.teal) } }
            .onChange(of: downloads.tasks.filter { $0.status == .completed }.map(\.id)) { _ in library.reload() }
        }
    }
}

struct LibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var category: MediaCategory = .all
    @State private var previewURL: URL?

    private var filtered: [URL] { library.files.filter { category.matches($0) } }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("النوع", selection: $category) {
                        ForEach(MediaCategory.allCases) { item in Text(item.rawValue).tag(item) }
                    }.pickerStyle(.segmented)
                }
                Section("الملفات المحفوظة") {
                    if filtered.isEmpty {
                        EmptyStateView(title: "لا توجد ملفات هنا", icon: "folder", detail: "نزّل ملفًا أو حوّل فيديو لتراه في المكتبة.")
                    } else {
                        ForEach(filtered, id: \.path) { url in
                            HStack(spacing: 12) {
                                Image(systemName: icon(for: url)).font(.title3).foregroundStyle(.teal).frame(width: 32)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(url.lastPathComponent).font(.subheadline.weight(.medium)).lineLimit(1)
                                    Text(size(for: url)).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 4)
                                ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }
                                    .buttonStyle(.borderless)
                                Button { previewURL = url } label: { Image(systemName: "eye") }
                                    .buttonStyle(.borderless)
                            }
                            .contextMenu {
                                ShareLink(item: url) { Label("مشاركة", systemImage: "square.and.arrow.up") }
                                Button(role: .destructive) { library.delete(url) } label: { Label("حذف", systemImage: "trash") }
                            }
                        }
                    }
                }
            }
            .navigationTitle("المكتبة")
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button { library.reload() } label: { Image(systemName: "arrow.clockwise") } } }
            .onAppear { library.reload() }
            .quickLookPreview($previewURL)
        }
    }

    private func size(for url: URL) -> String {
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        return WasloraFiles.readableSize(Int64(bytes))
    }

    private func icon(for url: URL) -> String {
        switch WasloraFiles.category(for: url) {
        case .video: return "play.rectangle.fill"
        case .audio: return "waveform"
        case .image: return "photo"
        default: return "doc"
        }
    }
}

private struct EmptyStateView: View {
    let title: String
    let icon: String
    let detail: String
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon).font(.title).foregroundStyle(.secondary)
            Text(title).font(.subheadline.weight(.semibold))
            Text(detail).font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }.frame(maxWidth: .infinity).padding(.vertical, 20)
    }
}

struct ConvertView: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var showPicker = false
    @State private var isConverting = false
    @State private var message = "اختر فيديو محفوظًا على الهاتف لاستخراج الصوت بصيغة M4A."
    @State private var isError = false
    @State private var convertedURL: URL?
    @State private var previewURL: URL?

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Image(systemName: "waveform.circle.fill").font(.system(size: 62)).foregroundStyle(.teal)
                Text("استخراج الصوت").font(.title2.bold())
                Text(message).multilineTextAlignment(.center).foregroundStyle(isError ? .red : .secondary)
                if isConverting { ProgressView("جارٍ التحويل…") }
                Button { showPicker = true } label: {
                    Label("اختيار فيديو", systemImage: "video.badge.plus").frame(maxWidth: .infinity)
                }.buttonStyle(.borderedProminent).disabled(isConverting)
                if let convertedURL {
                    HStack {
                        Button { previewURL = convertedURL } label: { Label("تشغيل الصوت", systemImage: "play.fill") }
                        ShareLink(item: convertedURL) { Label("مشاركة", systemImage: "square.and.arrow.up") }
                    }.buttonStyle(.bordered)
                }
                Text("يحفظ الناتج في ملفات Waslora ويمكنك الوصول إليه من تطبيق «الملفات».")
                    .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Spacer()
            }
            .padding(24)
            .navigationTitle("تحويل الصوت")
            .quickLookPreview($previewURL)
            .fileImporter(isPresented: $showPicker, allowedContentTypes: [.movie, .video, .mpeg4Movie], allowsMultipleSelection: false) { result in
                switch result {
                case .success(let urls): if let url = urls.first { convert(url) }
                case .failure(let error): message = error.localizedDescription; isError = true
                }
            }
        }
    }

    private func convert(_ url: URL) {
        isConverting = true; isError = false; convertedURL = nil; message = "جارٍ تجهيز الفيديو…"
        Task {
            do {
                let result = try await AudioConverter.toM4A(url)
                await MainActor.run { message = "اكتمل التحويل: \(result.lastPathComponent)"; convertedURL = result; isConverting = false; library.reload() }
            } catch {
                await MainActor.run { message = error.localizedDescription; isError = true; isConverting = false }
            }
        }
    }
}

struct TransferView: View {
    @EnvironmentObject private var transfer: LocalTransferServer
    @EnvironmentObject private var library: LibraryStore
    @State private var copied = false
    @State private var showPicker = false
    @State private var isImporting = false
    @State private var importMessage: String?

    var body: some View {
        NavigationStack {
            ScrollView {
              VStack(spacing: 20) {
                Image(systemName: transfer.isRunning ? "wifi.circle.fill" : "wifi.slash")
                    .font(.system(size: 62)).foregroundStyle(transfer.isRunning ? .teal : .secondary)
                Text(transfer.isRunning ? "صفحة النقل جاهزة" : "نقل محلي مباشر")
                    .font(.title2.bold())
                Text(transfer.message).multilineTextAlignment(.center).foregroundStyle(.secondary)
                if let address = transfer.address {
                    Text(address).font(.footnote.monospaced()).textSelection(.enabled)
                        .padding(12).frame(maxWidth: .infinity).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
                    Button {
                        UIPasteboard.general.string = address
                        copied = true
                    } label: { Label(copied ? "تم النسخ" : "نسخ العنوان", systemImage: "doc.on.doc").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered)
                }
                Button {
                    if transfer.isRunning { transfer.stop() } else { transfer.start() }
                    copied = false
                } label: {
                    Label(transfer.isRunning ? "إيقاف النقل" : "تشغيل صفحة النقل", systemImage: transfer.isRunning ? "stop.fill" : "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                Button { showPicker = true } label: {
                    Label("اختيار ملفات من الآيفون لإرسالها", systemImage: "doc.badge.plus")
                        .frame(maxWidth: .infinity)
                }.buttonStyle(.bordered).disabled(isImporting)
                if isImporting { ProgressView("جارٍ تجهيز الملفات…") }
                if let importMessage { Text(importMessage).font(.footnote).multilineTextAlignment(.center) }
                Text("وصّل الآيفون والكمبيوتر بنفس شبكة Wi‑Fi، ثم افتح العنوان في متصفح الكمبيوتر. الملفات تنتقل مباشرة إلى مجلد Waslora على الآيفون.")
                    .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
              }.padding(24).frame(maxWidth: .infinity)
            }.navigationTitle("نقل الملفات")
                .fileImporter(isPresented: $showPicker, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
                    switch result {
                    case .success(let urls): importFiles(urls)
                    case .failure(let error): importMessage = error.localizedDescription
                    }
                }
        }
        .onDisappear { transfer.stop(); library.reload() }
    }

    private func importFiles(_ urls: [URL]) {
        isImporting = true
        importMessage = nil
        Task {
            let results = await Task.detached(priority: .userInitiated) { () -> [String] in
                urls.map { url in
                    do {
                        let saved = try WasloraFiles.importFile(url)
                        return "جاهز للنقل: \(saved.lastPathComponent)"
                    } catch { return "\(url.lastPathComponent): \(error.localizedDescription)" }
                }
            }.value
            await MainActor.run {
                isImporting = false
                importMessage = results.joined(separator: "\n")
                library.reload()
            }
        }
    }
}
