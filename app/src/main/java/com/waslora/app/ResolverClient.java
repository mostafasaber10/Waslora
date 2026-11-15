package com.waslora.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Talks to the optional Waslora Resolver service and saves completed jobs locally. */
public final class ResolverClient {
    public interface Callback {
        void onProgress(String message);
        void onComplete(File file);
        void onError(String message);
    }

    public interface HealthCallback {
        void onResult(boolean ok, String message);
    }

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final long MAX_RESPONSE_BYTES = 512 * 1024;
    private static final long MAX_JOB_WAIT_MS = 8L * 60L * 60L * 1000L;
    private static final long MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L * 1024L;

    private ResolverClient() {}

    public static boolean isPlatformUrl(String rawUrl) {
        try {
            URI uri = new URI(rawUrl == null ? "" : rawUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return false;
            host = host.toLowerCase(Locale.ROOT);
            String[] domains = {"youtube.com", "youtube-nocookie.com", "youtu.be", "facebook.com", "fb.com", "fb.watch", "fb.me", "instagram.com", "instagr.am", "tiktok.com", "twitter.com", "x.com", "vimeo.com", "dailymotion.com", "twitch.tv", "reddit.com", "redd.it", "pinterest.com", "snapchat.com", "soundcloud.com", "tumblr.com", "streamable.com", "bilibili.com", "kick.com"};
            for (String domain : domains) if (host.equals(domain) || host.endsWith("." + domain)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    public static String normalizeEndpoint(String rawEndpoint) throws Exception {
        if (rawEndpoint == null || rawEndpoint.trim().isEmpty()) throw new Exception("اكتب عنوان Resolver أولًا، مثل http://192.168.1.10:8787.");
        URI uri = new URI(rawEndpoint.trim());
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new Exception("عنوان Resolver يجب أن يبدأ بـ http:// أو https:// ويحتوي اسم جهاز أو IP.");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new Exception("احذف بيانات الدخول أو ? و# من عنوان Resolver.");
        }
        String value = uri.toString();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    public static void testHealth(String endpoint, String token, HealthCallback callback) {
        new Thread(() -> {
            try {
                String base = normalizeEndpoint(endpoint);
                HttpURLConnection connection = open(base + "/health", token, "GET");
                int code = connection.getResponseCode();
                String body = readBody(connection, MAX_RESPONSE_BYTES);
                connection.disconnect();
                if (code < 200 || code >= 300) {
                    callback.onResult(false, errorFromResponse(code, body));
                    return;
                }
                JSONObject json = new JSONObject(body);
                callback.onResult(json.optBoolean("ok", false), json.optString("service", "Resolver متصل."));
            } catch (Exception e) {
                callback.onResult(false, userMessage(e));
            }
        }, "Waslora-resolver-health").start();
    }

    public static void download(Context context, String endpoint, String token, String pageUrl, Callback callback) {
        new Thread(() -> {
            try {
                String base = normalizeEndpoint(endpoint);
                callback.onProgress("جارٍ إرسال رابط المنصة إلى Resolver…");
                JSONObject request = new JSONObject();
                request.put("url", pageUrl == null ? "" : pageUrl.trim());
                HttpURLConnection create = open(base + "/v1/jobs", token, "POST");
                create.setDoOutput(true);
                create.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
                create.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = create.getOutputStream()) { output.write(body); }
                int status = create.getResponseCode();
                String responseBody = readBody(create, MAX_RESPONSE_BYTES);
                create.disconnect();
                if (status < 200 || status >= 300) throw new Exception(errorFromResponse(status, responseBody));

                JSONObject created = new JSONObject(responseBody);
                String jobId = created.optString("id", "");
                if (jobId.isEmpty()) throw new Exception("لم يُرجع Resolver رقم مهمة صالحًا.");
                String statusPath = created.optString("statusUrl", "/v1/jobs/" + jobId);
                String statusUrl = sameResolverUrl(base, statusPath);
                long deadline = System.currentTimeMillis() + MAX_JOB_WAIT_MS;
                JSONObject ready = null;
                while (System.currentTimeMillis() < deadline) {
                    HttpURLConnection poll = open(statusUrl, token, "GET");
                    int pollCode = poll.getResponseCode();
                    String pollBody = readBody(poll, MAX_RESPONSE_BYTES);
                    poll.disconnect();
                    if (pollCode < 200 || pollCode >= 300) throw new Exception(errorFromResponse(pollCode, pollBody));
                    JSONObject state = new JSONObject(pollBody);
                    String value = state.optString("status", "");
                    int progress = Math.max(0, Math.min(99, state.optInt("progress", 0)));
                    if ("error".equalsIgnoreCase(value)) throw new Exception(state.optString("error", "تعذر تنزيل الفيديو من Resolver."));
                    if ("ready".equalsIgnoreCase(value)) { ready = state; break; }
                    String speed = state.optString("speed", "");
                    String eta = state.optString("eta", "");
                    String extra = speed.isEmpty() ? "" : " · " + speed;
                    if (!eta.isEmpty()) extra += " · المتبقي " + eta;
                    callback.onProgress("جارٍ تنزيل الفيديو من المنصة… " + progress + "%" + extra);
                    Thread.sleep(900L);
                }
                if (ready == null) throw new Exception("استغرق تنزيل الفيديو وقتًا طويلًا؛ أعد المحاولة.");
                String filePath = ready.optString("fileUrl", "");
                if (filePath.isEmpty()) throw new Exception("اكتملت المهمة بدون رابط ملف.");
                File file = downloadFile(context, base, token, sameResolverUrl(base, filePath), ready, callback);
                callback.onComplete(file);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.onError("تم إيقاف تنزيل Resolver.");
            } catch (Exception e) {
                callback.onError(userMessage(e));
            }
        }, "Waslora-resolver-download").start();
    }

    private static File downloadFile(Context context, String base, String token, String fileUrl, JSONObject metadata, Callback callback) throws Exception {
        HttpURLConnection connection = open(fileUrl, token, "GET");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            String error = readBody(connection, MAX_RESPONSE_BYTES);
            connection.disconnect();
            throw new Exception(errorFromResponse(code, error));
        }
        long total = metadata.optLong("size", -1);
        if (total <= 0) total = connection.getContentLengthLong();
        if (total > MAX_DOWNLOAD_BYTES) { connection.disconnect(); throw new Exception("حجم الفيديو يتجاوز الحد المسموح به على الهاتف."); }
        String name = safeName(metadata.optString("filename", "video-" + System.currentTimeMillis() + ".mp4"));
        if (name.isEmpty()) name = "video-" + System.currentTimeMillis() + ".mp4";
        if (name.lastIndexOf('.') <= 0) name += ".mp4";
        File directory = DirectDownloader.downloadsDirectory(context);
        File target = uniqueFile(directory, name);
        File part = new File(directory, target.getName() + ".part");
        long done = 0;
        try (InputStream input = new BufferedInputStream(connection.getInputStream()); FileOutputStream output = new FileOutputStream(part)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                done += read;
                if (done > MAX_DOWNLOAD_BYTES) throw new Exception("حجم الفيديو يتجاوز الحد المسموح به على الهاتف.");
                output.write(buffer, 0, read);
                int percent = total > 0 ? (int) Math.min(99, done * 100 / total) : 0;
                callback.onProgress("جارٍ حفظ الفيديو على الهاتف… " + percent + "%");
            }
            output.flush();
        } catch (Exception e) {
            part.delete();
            throw e;
        } finally { connection.disconnect(); }
        if (done == 0) { part.delete(); throw new EOFException("لم يصل ملف الفيديو من Resolver."); }
        if (!part.renameTo(target)) { copyFile(part, target); part.delete(); }
        return target;
    }

    private static HttpURLConnection open(String endpoint, String token, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, video/*, */*");
        if (token != null && !token.trim().isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token.trim());
        return connection;
    }

    private static String absolute(String base, String value) throws Exception {
        if (value == null || value.trim().isEmpty()) throw new Exception("رابط Resolver فارغ.");
        URI uri = new URI(value);
        if (uri.isAbsolute()) return uri.toString();
        if (!value.startsWith("/")) value = "/" + value;
        return base + value;
    }

    /** Resolver responses must never redirect the bearer token to another origin. */
    private static String sameResolverUrl(String base, String value) throws Exception {
        String resolved = absolute(base, value);
        URI expected = new URI(base);
        URI actual = new URI(resolved);
        boolean sameHost = expected.getHost() != null && expected.getHost().equalsIgnoreCase(actual.getHost());
        boolean sameScheme = expected.getScheme() != null && expected.getScheme().equalsIgnoreCase(actual.getScheme());
        int expectedPort = expected.getPort() < 0 ? ("https".equalsIgnoreCase(expected.getScheme()) ? 443 : 80) : expected.getPort();
        int actualPort = actual.getPort() < 0 ? ("https".equalsIgnoreCase(actual.getScheme()) ? 443 : 80) : actual.getPort();
        if (!sameHost || !sameScheme || expectedPort != actualPort || actual.getUserInfo() != null) {
            throw new Exception("أعاد Resolver رابطًا من نطاق مختلف؛ أعد المحاولة بعد التحقق من إعداد الخادم.");
        }
        return resolved;
    }

    private static String readBody(HttpURLConnection connection, long maxBytes) throws Exception {
        InputStream source = connection.getErrorStream();
        if (source == null) source = connection.getInputStream();
        try (InputStream input = source; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read; long total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new Exception("رد Resolver كبير جدًا.");
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }

    private static String errorFromResponse(int code, String body) {
        try {
            JSONObject json = new JSONObject(body);
            String message = json.optString("message", "");
            if (!message.isEmpty()) return message;
            String error = json.optString("error", "");
            if (!error.isEmpty()) return error;
        } catch (Exception ignored) {}
        if (code == 401) return "رمز Resolver غير صحيح أو غير موجود.";
        if (code == 429) return "Resolver مشغول حاليًا. أعد المحاولة بعد قليل.";
        return "رد Resolver برمز HTTP " + code + ".";
    }

    private static String userMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return "تعذر الاتصال بـ Resolver. تحقق من العنوان والشبكة.";
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("unknownhost") || lower.contains("failed to connect") || lower.contains("connection refused")) return "تعذر الوصول إلى Resolver. شغّله وتأكد أن الهاتف واللابتوب على نفس الشبكة.";
        if (lower.contains("timeout") || lower.contains("timed out")) return "انتهت مهلة Resolver. تحقق من الإنترنت أو أعد المحاولة.";
        return message;
    }

    private static String safeName(String value) {
        if (value == null) return "";
        String name = value.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private static File uniqueFile(File directory, String name) {
        File file = new File(directory, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int index = 1;
        do { file = new File(directory, stem + " (" + index++ + ")" + extension); } while (file.exists());
        return file;
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(from)); OutputStream output = new FileOutputStream(to)) {
            byte[] buffer = new byte[128 * 1024]; int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
        }
    }
}
