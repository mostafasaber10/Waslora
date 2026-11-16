package com.waslora.app;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class DirectDownloader {
    public interface Callback {
        void onProgress(long done, long total);
        void onComplete(File file);
        void onError(String message);
    }

    /** A cooperative pause gate shared by every connection in one download. */
    public static final class DownloadControl {
        private final Object lock = new Object();
        private volatile boolean paused;
        public void pause() { synchronized (lock) { paused = true; } }
        public void resume() { synchronized (lock) { paused = false; lock.notifyAll(); } }
        public boolean isPaused() { return paused; }
        void awaitRunning() throws InterruptedException {
            synchronized (lock) { while (paused) lock.wait(); }
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 45000;
    // A few larger ranges reduce handshake overhead for multi-gigabyte files
    // while keeping memory use bounded on Android devices.
    private static final int MAX_PARALLEL_RANGES = 6;
    private static final long PARALLEL_THRESHOLD = 16L * 1024L * 1024L;

    private DirectDownloader() {}

    public static boolean isLikelyDirectFileUrl(String value) {
        try {
            URL url = new URL(value == null ? "" : value.trim());
            String scheme = url.getProtocol();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
            String path = url.getPath().toLowerCase(Locale.ROOT);
            return path.matches(".*\\.(mp4|m4v|mov|mkv|webm|3gp|mp3|m4a|aac|wav|ogg|jpg|jpeg|png|gif|webp|heic|heif|bmp|svg|pdf|txt|zip|7z|rar)$");
        } catch (Exception ignored) { return false; }
    }

    public static File downloadsDirectory(Context context) {
        File parent = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (parent == null) parent = new File(context.getFilesDir(), "downloads");
        File directory = new File(parent, "Waslora");
        if (!directory.exists() && !directory.mkdirs()) return new File(context.getFilesDir(), "downloads");
        return directory;
    }

    public static void download(Context context, String rawUrl, Callback callback) {
        download(context, rawUrl, new DownloadControl(), callback);
    }

    public static void download(Context context, String rawUrl, DownloadControl control, Callback callback) {
        final String url = rawUrl == null ? "" : rawUrl.trim();
        new Thread(() -> {
            try {
                URL parsed = validateUrl(url);
                File output = performDownload(context, parsed, control, callback);
                callback.onComplete(output);
            } catch (Exception e) {
                callback.onError(userMessage(e));
            }
        }, "Waslora-download").start();
    }

    private static File performDownload(Context context, URL url, DownloadControl control, Callback callback) throws Exception {
        File directory = downloadsDirectory(context);
        if (!directory.exists() && !directory.mkdirs()) throw new Exception("تعذر إنشاء مجلد التنزيلات.");
        Probe probe = probe(url);
        if (isHtml(probe.contentType)) {
            throw new Exception("هذا رابط صفحة ويب وليس رابط ملف فيديو مباشر. استخدم رابط التنزيل الرسمي للملف.");
        }

        String name = safeName(probe.filename != null ? probe.filename : lastPathSegment(url));
        if (name.isEmpty()) name = "video-" + System.currentTimeMillis() + extensionFor(probe.contentType);
        if (name.lastIndexOf('.') <= 0 && probe.contentType != null) name += extensionFor(probe.contentType);
        File target = uniqueFile(directory, name);
        File part = new File(directory, target.getName() + ".part");
        long total = probe.length;

        if (probe.ranges && total >= PARALLEL_THRESHOLD) {
            try {
                downloadRanges(url, part, total, control, callback);
            } catch (Exception rangeError) {
                if (control.isPaused()) throw rangeError;
                if (part.exists()) part.delete();
                downloadSequential(url, part, control, callback);
            }
        } else {
            downloadSequential(url, part, control, callback);
        }

        if (!part.exists() || part.length() == 0) throw new Exception("لم يصل أي محتوى من رابط التنزيل.");
        if (total > 0 && part.length() != total) throw new Exception("اكتمل اتصال المصدر قبل اكتمال الملف؛ أعد المحاولة على شبكة مستقرة.");
        if (target.exists()) target = uniqueFile(directory, name);
        if (!part.renameTo(target)) {
            copyFile(part, target);
            part.delete();
        }
        return target;
    }

    private static Probe probe(URL url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code >= 200 && code < 400) {
                Probe p = new Probe();
                p.length = connection.getContentLengthLong();
                p.contentType = connection.getContentType();
                p.filename = filenameFromDisposition(connection.getHeaderField("Content-Disposition"));
                p.ranges = "bytes".equalsIgnoreCase(connection.getHeaderField("Accept-Ranges"));
                return p;
            }
        } catch (Exception ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
        return new Probe();
    }

    private static void downloadRanges(URL url, File part, long total, DownloadControl control, Callback callback) throws Exception {
        int count = (int) Math.min(MAX_PARALLEL_RANGES, Math.max(2, (total + PARALLEL_THRESHOLD - 1) / PARALLEL_THRESHOLD));
        long segmentSize = (total + count - 1) / count;
        AtomicLong done = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(count);
        RandomAccessFile preallocate = new RandomAccessFile(part, "rw");
        preallocate.setLength(total);
        preallocate.close();
        Future<?>[] futures = new Future<?>[count];
        ConcurrentLinkedQueue<HttpURLConnection> activeConnections = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < count; i++) {
                final long start = i * segmentSize;
                final long end = Math.min(total - 1, start + segmentSize - 1);
                futures[i] = pool.submit(() -> {
                    HttpURLConnection connection = null;
                    try {
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                        connection.setReadTimeout(2000);
                        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
                        connection.setInstanceFollowRedirects(true);
                        activeConnections.add(connection);
                        int status = connection.getResponseCode();
                        rejectPlatformUrl(connection.getURL());
                        if (isHtml(connection.getContentType())) throw new IllegalStateException("هذا رابط صفحة ويب وليس رابط ملف فيديو مباشر.");
                        if (status != HttpURLConnection.HTTP_PARTIAL) throw new IllegalStateException("range unsupported");
                        try (InputStream input = new BufferedInputStream(connection.getInputStream());
                             RandomAccessFile output = new RandomAccessFile(part, "rw")) {
                            output.seek(start);
                            byte[] buffer = new byte[512 * 1024];
                            int read;
                            long written = 0;
                            while (true) {
                                control.awaitRunning();
                                try { read = input.read(buffer); }
                                catch (SocketTimeoutException timeout) { control.awaitRunning(); continue; }
                                if (read == -1) break;
                                if (written + read > end - start + 1) read = (int) (end - start + 1 - written);
                                output.write(buffer, 0, read);
                                written += read;
                                long now = done.addAndGet(read);
                                callback.onProgress(now, total);
                                if (written >= end - start + 1) break;
                            }
                            if (written != end - start + 1) throw new IllegalStateException("short range");
                        }
                    } catch (SocketTimeoutException timeout) {
                        if (control.isPaused()) {
                            try { control.awaitRunning(); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
                            throw new RuntimeException("range connection expired while paused");
                        }
                        throw new RuntimeException(timeout);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        if (connection != null) { activeConnections.remove(connection); connection.disconnect(); }
                    }
                });
            }
            for (Future<?> future : futures) future.get();
        } catch (Exception e) {
            for (Future<?> future : futures) if (future != null) future.cancel(true);
            for (HttpURLConnection connection : activeConnections) connection.disconnect();
            pool.shutdownNow();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            throw e;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void downloadSequential(URL url, File part, DownloadControl control, Callback callback) throws Exception {
        long existing = part.exists() ? part.length() : 0;
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(2000);
        connection.setInstanceFollowRedirects(true);
        if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
        int status = connection.getResponseCode();
        rejectPlatformUrl(connection.getURL());
        if (isHtml(connection.getContentType())) {
            connection.disconnect();
            throw new Exception("هذا رابط صفحة ويب وليس رابط ملف فيديو مباشر. استخدم رابط ملف متاحًا للتنزيل رسميًا.");
        }
        boolean append = existing > 0 && status == HttpURLConnection.HTTP_PARTIAL;
        if (existing > 0 && !append) existing = 0;
        long total = connection.getContentLengthLong();
        String range = connection.getHeaderField("Content-Range");
        if (range != null && range.contains("/")) {
            try { total = Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim()); }
            catch (NumberFormatException ignored) {}
        } else if (total > 0) total += existing;
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new Exception("رفض مصدر الفيديو طلب التنزيل (HTTP " + status + ").");
        }
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(part, append)) {
            byte[] buffer = new byte[512 * 1024];
            long done = existing;
            int read;
            while (true) {
                control.awaitRunning();
                try { read = input.read(buffer); }
                catch (SocketTimeoutException timeout) {
                    control.awaitRunning();
                    continue;
                }
                if (read == -1) break;
                output.write(buffer, 0, read);
                done += read;
                callback.onProgress(done, total);
            }
            output.flush();
        } finally {
            connection.disconnect();
        }
    }

    private static URL validateUrl(String value) throws Exception {
        if (value.isEmpty()) throw new Exception("أدخل رابط الملف أولًا.");
        URL url = new URL(value);
        String scheme = url.getProtocol().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new Exception("يجب أن يبدأ الرابط بـ http أو https.");
        if (url.getUserInfo() != null) throw new Exception("احذف اسم المستخدم وكلمة المرور من الرابط ثم أعد المحاولة.");
        rejectPlatformUrl(url);
        return url;
    }

    private static void rejectPlatformUrl(URL url) throws Exception {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (isHostOrSubdomain(host, "youtube.com") || isHostOrSubdomain(host, "youtube-nocookie.com") ||
                host.equals("youtu.be") || isHostOrSubdomain(host, "facebook.com") ||
                isHostOrSubdomain(host, "fb.com") || host.equals("fb.watch") ||
                isHostOrSubdomain(host, "instagram.com") || host.equals("instagr.am") ||
                isHostOrSubdomain(host, "twitter.com") || isHostOrSubdomain(host, "x.com") ||
                isHostOrSubdomain(host, "tiktok.com")) {
            throw new Exception("هذا رابط تابع لمنصة فيديو وليس رابط ملف مباشرًا. ألصق رابط صفحة الفيديو ليحاول محرك Waslora تنزيله، أو استخدم رابط الملف المباشر.");
        }
    }

    private static boolean isHostOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static boolean isHtml(String type) {
        return type != null && (type.toLowerCase(Locale.ROOT).contains("text/html") || type.toLowerCase(Locale.ROOT).contains("application/json"));
    }

    private static String filenameFromDisposition(String header) {
        if (header == null) return null;
        String lower = header.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("filename=");
        if (index < 0) return null;
        String value = header.substring(index + 9).trim();
        if (value.startsWith("\"")) {
            int end = value.indexOf('"', 1);
            if (end > 0) value = value.substring(1, end);
        } else {
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) value = value.substring(0, semicolon);
        }
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (Exception ignored) { return value; }
    }

    private static String lastPathSegment(URL url) {
        String path = url.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        try { return URLDecoder.decode(name, "UTF-8"); }
        catch (Exception ignored) { return name; }
    }

    private static String safeName(String value) {
        if (value == null) return "";
        String name = value.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 120) name = name.substring(name.length() - 120);
        return name;
    }

    private static String extensionFor(String contentType) {
        if (contentType == null) return ".mp4";
        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.contains("webm")) return ".webm";
        if (type.contains("quicktime")) return ".mov";
        if (type.contains("matroska")) return ".mkv";
        if (type.contains("3gpp")) return ".3gp";
        return ".mp4";
    }

    private static File uniqueFile(File directory, String name) {
        File file = new File(directory, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int index = 1;
        do { file = new File(directory, stem + " (" + index++ + ")" + extension); }
        while (file.exists());
        return file;
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[512 * 1024]; int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
        }
    }

    private static String userMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return "تعذر تنزيل الملف. تحقق من الرابط والاتصال ثم أعد المحاولة.";
        String lower = m.toLowerCase(Locale.ROOT);
        if (lower.contains("unknownhostexception") || lower.contains("failed to connect")) return "تعذر الوصول إلى مصدر الملف. تحقق من الإنترنت ومن أن الرابط ما زال صالحًا.";
        if (lower.contains("unexpected end of stream") || lower.contains("eofexception") || lower.contains("connection reset")) {
            return "انقطع اتصال مصدر الفيديو قبل اكتمال الملف. تحقق من الرابط والإنترنت ثم أعد المحاولة.";
        }
        if (lower.contains("sockettimeoutexception") || lower.contains("timed out")) return "انتهت مهلة الاتصال بمصدر الفيديو. أعد المحاولة على اتصال إنترنت مستقر.";
        if (lower.contains("sslhandshakeexception") || lower.contains("certpathvalidatorexception")) return "تعذر تأمين الاتصال بمصدر الفيديو. تحقق من الرابط أو جرّب مصدرًا آخر.";
        if (lower.contains("malformedurlexception") || lower.contains("no protocol")) return "الرابط غير مكتمل. أرسله كاملًا مع http:// أو https://.";
        return m;
    }

    private static final class Probe {
        long length = -1;
        boolean ranges;
        String contentType;
        String filename;
    }
}
