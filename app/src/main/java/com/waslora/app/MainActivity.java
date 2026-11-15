package com.waslora.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String APP_ASSET_BASE = "https://appassets.waslora.local/";
    private static final String PREFS = "waslora";
    private static final String PREF_RESOLVER_ENDPOINT = "resolver_endpoint";
    private static final String PREF_RESOLVER_TOKEN = "resolver_token";
    private static final int REQUEST_PICK_VIDEO = 101;
    private static final int REQUEST_NOTIFICATIONS = 102;
    private static final int REQUEST_PICK_TRANSFER_FILES = 103;
    private WebView webView;
    private volatile boolean pageReady;
    private volatile Uri pendingSharedVideo;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        requestNotificationPermissionIfNeeded();
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isAppAssetUrl(request.getUrl());
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!isAppAssetUrl(uri)) {
                    return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null, new ByteArrayInputStream(new byte[0]));
                }
                return openAppAsset(uri.getPath());
            }

            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                handleShareIntent(getIntent());
                refreshTransferStatus();
            }
        });
        webView.addJavascriptInterface(new Bridge(), "Waslora");
        setContentView(webView);
        webView.loadUrl(APP_ASSET_BASE + "index.html");
    }

    private static boolean isAppAssetUrl(Uri uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "appassets.waslora.local".equalsIgnoreCase(uri.getHost());
    }

    private WebResourceResponse openAppAsset(String path) {
        if (path == null || !path.startsWith("/") || path.contains("..") || path.indexOf('/', 1) >= 0) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
        }
        String assetPath = path.substring(1);
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            headers.put("X-Content-Type-Options", "nosniff");
            headers.put("Content-Security-Policy", "default-src 'self'; script-src 'self'; worker-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            return new WebResourceResponse(assetMimeType(assetPath), "UTF-8", 200, "OK", headers, getAssets().open(assetPath));
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
        }
    }

    private static String assetMimeType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_TRANSFER_FILES && resultCode == RESULT_OK && data != null) {
            importPhoneFilesForTransfer(data);
            return;
        }
        if (requestCode != REQUEST_PICK_VIDEO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) {}
        String format = getSharedPreferences("waslora", MODE_PRIVATE).getString("audio_format", "m4a");
        AudioConverter.convert(this, uri, format, new AudioConverter.Callback() {
            @Override public void onProgress(String message) { emit("convert-progress", json("message", message)); }
            @Override public void onComplete(File file) { emitConvertComplete(file); }
            @Override public void onError(String message) { emit("convert-error", json("message", message)); }
        });
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void handleShareIntent(Intent intent) {
        if (!pageReady || intent == null || !(Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) return;
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null) {
            ClipData clip = intent.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                try { text = clip.getItemAt(0).coerceToText(this); }
                catch (Exception ignored) {}
            }
        }
        if (text != null) {
            String sharedUrl = extractSharedUrl(text.toString());
            if (sharedUrl != null) {
                String encoded = JSONObject.quote(sharedUrl);
                webView.evaluateJavascript("document.getElementById('videoUrl').value=" + encoded + ";document.getElementById('videoUrl').dispatchEvent(new Event('input'));document.querySelector('[data-screen=download]').click();document.getElementById('downloadForm').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));", null);
                emit("notice", json("message", "تم استلام الرابط من قائمة المشاركة وبدأ التنزيل."));
            } else {
                emit("notice", json("message", "لم أجد رابطًا داخل المحتوى المُشارَك."));
            }
        }
        Uri stream = null;
        try { stream = intent.getParcelableExtra(Intent.EXTRA_STREAM); }
        catch (Exception ignored) {}
        if (stream == null && Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            try {
                ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (streams != null && !streams.isEmpty()) stream = streams.get(0);
            } catch (Exception ignored) {}
        }
        if (stream != null) {
            pendingSharedVideo = stream;
            emit("shared-video", json("message", "استُلم الفيديو. اختر الصيغة ثم اضغط تحويل الفيديو المستلَم."));
            try { getContentResolver().takePersistableUriPermission(stream, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
        }
    }

    private static String extractSharedUrl(String text) {
        Matcher matcher = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!matcher.find()) return null;
        return matcher.group().replaceAll("[.,!?;:)\\]]+$", "");
    }

    private void refreshTransferStatus() {
        if (webView == null) return;
        LocalTransferService.Status status = LocalTransferService.status(this);
        JSONObject payload = new JSONObject();
        try {
            payload.put("running", status.running);
            payload.put("url", status.url == null ? "" : status.url);
            payload.put("message", status.message == null ? "" : status.message);
        } catch (Exception ignored) {}
        emit("transfer-status", payload.toString());
    }

    private void emit(String event, String payload) {
        WebView view = webView;
        if (view == null || !pageReady) return;
        String eventJson = JSONObject.quote(event);
        view.post(() -> view.evaluateJavascript("window.WasloraUI&&window.WasloraUI.onEvent(" + eventJson + "," + payload + ");", null));
    }

    void startMp3Encoder(String jobId, int sampleRate, int channels, int bitrate) {
        String script = "window.WasloraUI&&window.WasloraUI.startMp3Encoder(" + JSONObject.quote(jobId) + "," + sampleRate + "," + channels + "," + bitrate + ");";
        if (!evaluateOnPage(script)) AudioConverter.onEncoderError(jobId, "تعذر تشغيل محرك MP3 في هذه الشاشة.");
    }

    void pushMp3Pcm(String jobId, String base64) {
        String script = "window.WasloraUI&&window.WasloraUI.pushMp3Pcm(" + JSONObject.quote(jobId) + "," + JSONObject.quote(base64) + ");";
        if (!evaluateOnPage(script)) AudioConverter.onEncoderError(jobId, "توقف اتصال محرك MP3.");
    }

    void finishMp3Encoder(String jobId) {
        String script = "window.WasloraUI&&window.WasloraUI.finishMp3Encoder(" + JSONObject.quote(jobId) + ");";
        if (!evaluateOnPage(script)) AudioConverter.onEncoderError(jobId, "تعذر إنهاء ملف MP3.");
    }

    void abortMp3Encoder(String jobId) {
        evaluateOnPage("window.WasloraUI&&window.WasloraUI.abortMp3Encoder(" + JSONObject.quote(jobId) + ");");
    }

    private boolean evaluateOnPage(String script) {
        WebView view = webView;
        return view != null && view.post(() -> view.evaluateJavascript(script, null));
    }

    private static String json(String key, String value) {
        JSONObject object = new JSONObject();
        try { object.put(key, value); } catch (Exception ignored) {}
        return object.toString();
    }

    private final class Bridge {
        @JavascriptInterface
        public void downloadFromUrl(String url) {
            final String value = url == null ? "" : url.trim();
            if (value.isEmpty()) {
                emit("download-error", json("message", "أدخل رابط الفيديو أولًا."));
                return;
            }
            if (!DirectDownloader.isLikelyDirectFileUrl(value)) {
                LocalPlatformDownloader.download(MainActivity.this, value, new LocalPlatformDownloader.Callback() {
                    @Override public void onProgress(String message) { emit("download-message", json("message", message)); }
                    @Override public void onComplete(File file) { emitDownloadComplete(file); }
                    @Override public void onError(String message) { emit("download-error", json("message", message)); }
                });
                return;
            }
            DirectDownloader.download(MainActivity.this, value, directDownloadCallback());
        }

        @JavascriptInterface
        public String getResolverSettings() {
            SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
            JSONObject result = new JSONObject();
            try {
                String endpoint = preferences.getString(PREF_RESOLVER_ENDPOINT, "");
                result.put("endpoint", endpoint);
                result.put("configured", !endpoint.trim().isEmpty());
                result.put("tokenConfigured", !preferences.getString(PREF_RESOLVER_TOKEN, "").trim().isEmpty());
            } catch (Exception ignored) {}
            return result.toString();
        }

        @JavascriptInterface
        public void saveResolverSettings(String endpoint, String token) {
            String normalized = "";
            String value = endpoint == null ? "" : endpoint.trim();
            if (!value.isEmpty()) {
                try { normalized = ResolverClient.normalizeEndpoint(value); }
                catch (Exception e) {
                    emit("resolver-settings-error", json("message", e.getMessage()));
                    return;
                }
            }
            String cleanToken = token == null ? "" : token.trim();
            if (cleanToken.length() > 512) {
                emit("resolver-settings-error", json("message", "رمز Resolver طويل جدًا."));
                return;
            }
            SharedPreferences existing = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (cleanToken.isEmpty() && normalized.equals(existing.getString(PREF_RESOLVER_ENDPOINT, ""))) {
                cleanToken = existing.getString(PREF_RESOLVER_TOKEN, "");
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_RESOLVER_ENDPOINT, normalized)
                    .putString(PREF_RESOLVER_TOKEN, cleanToken)
                    .apply();
            emit("resolver-settings-saved", json("message", normalized.isEmpty()
                    ? "تم مسح إعدادات Resolver. روابط الملفات المباشرة ما زالت تعمل."
                    : "تم حفظ إعدادات Resolver."));
        }

        @JavascriptInterface
        public void testResolver(String endpoint, String token) {
            final String value = endpoint == null ? "" : endpoint.trim();
            String cleanToken = token == null ? "" : token.trim();
            try {
                String normalized = ResolverClient.normalizeEndpoint(value);
                SharedPreferences existing = getSharedPreferences(PREFS, MODE_PRIVATE);
                if (cleanToken.isEmpty() && normalized.equals(existing.getString(PREF_RESOLVER_ENDPOINT, ""))) {
                    cleanToken = existing.getString(PREF_RESOLVER_TOKEN, "");
                }
            } catch (Exception ignored) {}
            final String resolverToken = cleanToken;
            ResolverClient.testHealth(value, resolverToken, (ok, message) -> {
                JSONObject payload = new JSONObject();
                try { payload.put("ok", ok); payload.put("message", message == null ? "" : message); }
                catch (Exception ignored) {}
                emit("resolver-test", payload.toString());
            });
        }

        @JavascriptInterface
        public void openExternalUrl(String rawUrl) {
            Uri uri = Uri.parse(rawUrl == null ? "" : rawUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                emit("download-error", json("message", "الرابط غير صالح."));
                return;
            }
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                catch (Exception e) { emit("download-error", json("message", "تعذر فتح الرابط في المتصفح أو التطبيق الرسمي.")); }
            });
        }

        @JavascriptInterface
        public String listDownloads() {
            JSONArray array = new JSONArray();
            File[] files = DirectDownloader.downloadsDirectory(MainActivity.this).listFiles();
            if (files == null) return array.toString();
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File file : files) {
                if (!file.isFile() || file.getName().endsWith(".part")) continue;
                PublicFileStore.publishInBackground(MainActivity.this, file);
                JSONObject item = new JSONObject();
                try { item.put("name", file.getName()); item.put("size", file.length()); item.put("modified", file.lastModified()); }
                catch (Exception ignored) {}
                array.put(item);
            }
            return array.toString();
        }

        @JavascriptInterface
        public void openDownload(String name) {
            File file = fileFromDownloads(name);
            if (file == null) { emit("file-error", json("message", "لم أجد هذا الملف داخل Waslora.")); return; }
            new Thread(() -> {
                try {
                    android.net.Uri uri = PublicFileStore.publish(MainActivity.this, file);
                    if (uri == null) throw new Exception("هذا الإصدار من أندرويد لا يسمح بفتح الملف العام مباشرة.");
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.setDataAndType(uri, PublicFileStore.mimeType(file.getName()));
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    runOnUiThread(() -> {
                        try { startActivity(intent); }
                        catch (Exception e) { emit("file-error", json("message", "لا يوجد تطبيق لفتح هذا النوع من الملفات.")); }
                    });
                } catch (Exception e) { emit("file-error", json("message", "تعذر إظهار الملف في مجلد Download/Waslora.")); }
            }, "Waslora-open-file").start();
        }

        @JavascriptInterface
        public void shareDownload(String name) {
            File file = fileFromDownloads(name);
            if (file == null) { emit("file-error", json("message", "لم أجد هذا الملف داخل Waslora.")); return; }
            new Thread(() -> {
                try {
                    android.net.Uri uri = PublicFileStore.publish(MainActivity.this, file);
                    if (uri == null) throw new Exception("الملف العام غير متاح.");
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType(PublicFileStore.mimeType(file.getName()));
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    runOnUiThread(() -> startActivity(Intent.createChooser(intent, "مشاركة الملف")));
                } catch (Exception e) { emit("file-error", json("message", "تعذر مشاركة الملف.")); }
            }, "Waslora-share-file").start();
        }

        @JavascriptInterface
        public void convertDownloaded(String name, String format) {
            getSharedPreferences("waslora", MODE_PRIVATE).edit().putString("audio_format", normalizeFormat(format)).apply();
            File file = new File(DirectDownloader.downloadsDirectory(MainActivity.this), new File(name).getName());
            if (!file.isFile()) { emit("convert-error", json("message", "لم أجد ملف الفيديو في مجلد التنزيلات.")); return; }
            AudioConverter.convert(MainActivity.this, Uri.fromFile(file), normalizeFormat(format), converterCallback());
        }

        @JavascriptInterface
        public void pickVideo(String format) {
            getSharedPreferences("waslora", MODE_PRIVATE).edit().putString("audio_format", normalizeFormat(format)).apply();
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("video/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_PICK_VIDEO);
            });
        }

        @JavascriptInterface
        public void convertShared(String format) {
            Uri source = pendingSharedVideo;
            if (source == null) { emit("convert-error", json("message", "لا يوجد فيديو مُشارَك لتحويله.")); return; }
            String selectedFormat = normalizeFormat(format);
            getSharedPreferences("waslora", MODE_PRIVATE).edit().putString("audio_format", selectedFormat).apply();
            AudioConverter.convert(MainActivity.this, source, selectedFormat, new AudioConverter.Callback() {
                @Override public void onProgress(String message) { emit("convert-progress", json("message", message)); }
                @Override public void onComplete(File file) {
                    pendingSharedVideo = null;
                    emitConvertComplete(file);
                    emit("shared-video-cleared", "{}");
                }
                @Override public void onError(String message) { emit("convert-error", json("message", message)); }
            });
        }

        @JavascriptInterface
        public void startTransfer() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(MainActivity.this, LocalTransferService.class);
                    intent.setAction(LocalTransferService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
                    webView.postDelayed(MainActivity.this::refreshTransferStatus, 700);
                    webView.postDelayed(MainActivity.this::refreshTransferStatus, 1600);
                    webView.postDelayed(MainActivity.this::refreshTransferStatus, 3200);
                    webView.postDelayed(MainActivity.this::refreshTransferStatus, 5000);
                } catch (Exception e) {
                    emit("transfer-status", statusPayload(false, "تعذر تشغيل صفحة النقل: " + e.getMessage()));
                }
            });
        }

        @JavascriptInterface
        public void pickPhoneFilesForTransfer() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_PICK_TRANSFER_FILES);
            });
        }

        @JavascriptInterface
        public void stopTransfer() {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, LocalTransferService.class);
                intent.setAction(LocalTransferService.ACTION_STOP);
                startService(intent);
                webView.postDelayed(MainActivity.this::refreshTransferStatus, 250);
            });
        }

        @JavascriptInterface
        public void refreshTransferStatus() { MainActivity.this.refreshTransferStatus(); }

        @JavascriptInterface
        public void writeMp3Chunk(String jobId, String encoded, boolean finished) {
            AudioConverter.onEncodedChunk(jobId, encoded, finished);
        }

        @JavascriptInterface
        public void failMp3Conversion(String jobId, String message) {
            AudioConverter.onEncoderError(jobId, message);
        }

        @JavascriptInterface
        public void mp3PcmAck(String jobId) {
            AudioConverter.onPcmAck(jobId);
        }

        private AudioConverter.Callback converterCallback() {
            return new AudioConverter.Callback() {
                @Override public void onProgress(String message) { emit("convert-progress", json("message", message)); }
                @Override public void onComplete(File file) { emitConvertComplete(file); }
                @Override public void onError(String message) { emit("convert-error", json("message", message)); }
            };
        }

        private File fileFromDownloads(String name) {
            if (name == null || name.trim().isEmpty()) return null;
            File file = new File(DirectDownloader.downloadsDirectory(MainActivity.this), new File(name).getName());
            return file.isFile() ? file : null;
        }
    }

    private DirectDownloader.Callback directDownloadCallback() {
        return new DirectDownloader.Callback() {
            @Override public void onProgress(long done, long total) {
                JSONObject payload = new JSONObject();
                try { payload.put("done", done); payload.put("total", total); }
                catch (Exception ignored) {}
                emit("download-progress", payload.toString());
            }
            @Override public void onComplete(File file) { emitDownloadComplete(file); }
            @Override public void onError(String message) { emit("download-error", json("message", message)); }
        };
    }

    private ResolverClient.Callback resolverCallback() {
        return new ResolverClient.Callback() {
            @Override public void onProgress(String message) { emit("download-message", json("message", message)); }
            @Override public void onComplete(File file) { emitDownloadComplete(file); }
            @Override public void onError(String message) { emit("download-error", json("message", message)); }
        };
    }

    private void emitDownloadComplete(File file) {
        emitPublishedFile("download-complete", file);
    }

    private void importPhoneFilesForTransfer(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData clip = data.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) { emit("phone-files-imported", json("message", "لم يتم اختيار ملفات.")); return; }
        new Thread(() -> {
            int saved = 0;
            for (Uri uri : uris) {
                try {
                    String displayName = displayName(uri);
                    File directory = DirectDownloader.downloadsDirectory(this);
                    if (!directory.exists() && !directory.mkdirs()) throw new Exception("تعذر إنشاء مجلد Waslora.");
                    File target = uniqueDownloadFile(directory, displayName);
                    try (InputStream input = getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(target)) {
                        if (input == null) throw new Exception("تعذر قراءة الملف المختار.");
                        byte[] buffer = new byte[512 * 1024];
                        int read;
                        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                        output.flush();
                    }
                    PublicFileStore.publish(this, target);
                    saved++;
                } catch (Exception ignored) {}
            }
            emit("phone-files-imported", json("message", saved > 0
                    ? "أصبحت " + saved + " ملفات متاحة في صفحة اللابتوب، وفي Download/Waslora على الهاتف."
                    : "تعذر نسخ الملفات المحددة. جرّب اختيار ملفات من تطبيق الملفات."));
        }, "Waslora-import-phone-files").start();
    }

    private String displayName(Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) {}
        if (name == null || name.trim().isEmpty()) name = "file-" + System.currentTimeMillis();
        return name.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_");
    }

    private static File uniqueDownloadFile(File directory, String name) {
        File file = new File(directory, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int index = 1;
        do { file = new File(directory, stem + " (" + index++ + ")" + extension); } while (file.exists());
        return file;
    }

    private void emitConvertComplete(File file) {
        emitPublishedFile("convert-complete", file);
    }

    private void emitPublishedFile(String event, File file) {
        new Thread(() -> {
            boolean published = false;
            try { published = PublicFileStore.publish(MainActivity.this, file) != null; }
            catch (Exception ignored) {}
            JSONObject payload = new JSONObject();
            try {
                payload.put("name", file.getName());
                payload.put("size", file.length());
                payload.put("published", published);
                payload.put("location", published ? "Download/Waslora" : "ملفات التطبيق");
            } catch (Exception ignored) {}
            emit(event, payload.toString());
        }, "Waslora-publish-complete").start();
    }

    private static String normalizeFormat(String format) { return "mp3".equalsIgnoreCase(format) ? "mp3" : "m4a"; }

    private String statusPayload(boolean running, String message) {
        JSONObject payload = new JSONObject();
        try { payload.put("running", running); payload.put("url", ""); payload.put("message", message); }
        catch (Exception ignored) {}
        return payload.toString();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        pageReady = false;
        if (webView != null) { webView.removeJavascriptInterface("Waslora"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
