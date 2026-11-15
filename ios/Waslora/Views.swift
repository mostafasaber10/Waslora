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
                        Text("نزّل الملفات المباشرة إلى الآيفون")
                            .font(.title2.bold())
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
                                Text(URL(string: task.url)?.lastPathComponent ?? task.url)
                                    .font(.subheadline.weight(.medium)).lineLimit(1)
                                ProgressView(value: task.progress)
                                HStack {
                                    Text(task.state).font(.caption).foregroundStyle(.secondary)
                                    Spacer()
                                    if task.progress < 1 {
                                        Button("إيقاف") { downloads.cancel(task.id) }.font(.caption)
                                    }
                                }
                            }.padding(.vertical, 4)
                        }
                    }
                }
            }
            .navigationTitle("Waslora")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Image(systemName: "arrow.left.arrow.right") .foregroundStyle(.teal) } }
            .onChange(of: downloads.tasks.map(\.state)) { _, states in
                if states.contains(where: { $0.hasPrefix("اكتمل:") }) { library.reload() }
            }
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
                                    .quickLookPreview($previewURL)
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
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button { library.reload() } label: { Image(systemName: "arrow.clockwise") } } }
            .onAppear { library.reload() }
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
                Text("يحفظ الناتج في ملفات Waslora ويمكنك الوصول إليه من تطبيق «الملفات».")
                    .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Spacer()
            }
            .padding(24)
            .navigationTitle("تحويل الصوت")
            .fileImporter(isPresented: $showPicker, allowedContentTypes: [.movie, .video, .mpeg4Movie], allowsMultipleSelection: false) { result in
                switch result {
                case .success(let urls): if let url = urls.first { convert(url) }
                case .failure(let error): message = error.localizedDescription; isError = true
                }
            }
        }
    }

    private func convert(_ url: URL) {
        isConverting = true; isError = false; message = "جارٍ تجهيز الفيديو…"
        Task {
            do {
                let result = try await AudioConverter.toM4A(url)
                await MainActor.run { message = "اكتمل التحويل: \(result.lastPathComponent)"; isConverting = false; library.reload() }
            } catch {
                await MainActor.run { message = error.localizedDescription; isError = true; isConverting = false }
            }
        }
    }
}

struct TransferView: View {
    @EnvironmentObject private var transfer: LocalTransferServer
    @State private var copied = false

    var body: some View {
        NavigationStack {
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
                .buttonStyle(transfer.isRunning ? .bordered : .borderedProminent)
                Text("وصّل الآيفون والكمبيوتر بنفس شبكة Wi‑Fi، ثم افتح العنوان في متصفح الكمبيوتر. الملفات تنتقل مباشرة إلى مجلد Waslora على الآيفون.")
                    .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Spacer()
            }.padding(24).navigationTitle("نقل الملفات")
        }
        .onDisappear { transfer.stop() }
    }
}
