package com.waslora.app;

import android.content.Context;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.net.URI;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/** Runs yt-dlp and FFmpeg inside the phone; no Resolver server is required. */
public final class LocalPlatformDownloader {
    public interface Callback {
        void onProgress(String message);
        void onComplete(File file);
        void onError(String message);
    }

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initialized;

    private LocalPlatformDownloader() {}

    public static void download(Context context, String pageUrl, String quality, Callback callback) {
        new Thread(() -> {
            File jobDirectory = null;
            try {
                URI parsed = new URI(pageUrl == null ? "" : pageUrl.trim());
                if (parsed.getHost() == null || parsed.getUserInfo() != null ||
                        !("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))) {
                    throw new Exception("أدخل رابطًا كاملًا يبدأ بـ http أو https.");
                }
                callback.onProgress("جارٍ تجهيز محرك التنزيل داخل الهاتف…");
                ensureInitialized(context);
                File directory = DirectDownloader.downloadsDirectory(context);
                if (!directory.exists() && !directory.mkdirs()) throw new Exception("تعذر إنشاء مجلد التنزيلات.");
                jobDirectory = new File(directory, ".platform-" + UUID.randomUUID());
                if (!jobDirectory.mkdirs()) throw new Exception("تعذر إنشاء مجلد مهمة التنزيل.");
                Set<String> before = fileNames(jobDirectory);
                String template = new File(jobDirectory, "%(title).100B-%(id)s.%(ext)s").getAbsolutePath();
                YoutubeDLRequest request = new YoutubeDLRequest(pageUrl);
                request.addOption("--no-playlist");
                request.addOption("--no-warnings");
                request.addOption("--newline");
                request.addOption("--retries", "5");
                request.addOption("--fragment-retries", "5");
                request.addOption("--concurrent-fragments", "4");
                request.addOption("--socket-timeout", "30");
                // Do not impose an artificial 2 GB ceiling.  The phone's free
                // storage is the practical limit and yt-dlp can stream large
                // files safely into the per-job directory.
                request.addOption("-f", PlatformDownloadQuality.formatSelector(quality));
                request.addOption("-o", template);
                callback.onProgress("جارٍ استخراج الفيديو على الهاتف…");
                YoutubeDLResponseHolder result = execute(request, callback);
                if (result.exitCode != 0) throw new Exception(safeError(result.error));
                File downloaded = newestNewFile(jobDirectory, before);
                if (downloaded == null) throw new Exception("انتهى المحرك بدون ملف فيديو. قد يكون الرابط خاصًا أو غير مدعوم.");
                File output = uniqueFile(directory, downloaded.getName());
                if (!downloaded.renameTo(output)) throw new Exception("تعذر حفظ ملف الفيديو في مجلد التنزيلات.");
                callback.onComplete(output);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.onError("تم إيقاف تنزيل الفيديو.");
            } catch (Exception e) {
                callback.onError(userMessage(e));
            } finally {
                if (jobDirectory != null) deleteDirectory(jobDirectory);
            }
        }, "Waslora-local-platform-download").start();
    }

    private static YoutubeDLResponseHolder execute(YoutubeDLRequest request, Callback callback) throws Exception {
        final YoutubeDLResponseHolder holder = new YoutubeDLResponseHolder();
        // Each job gets its own yt-dlp identifier.  Reusing one identifier can
        // make concurrent downloads overwrite each other's state/progress.
        String jobId = "waslora-platform-" + UUID.randomUUID();
        com.yausername.youtubedl_android.YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, jobId, new Function3<Float, Long, String, Unit>() {
            @Override public Unit invoke(Float progress, Long eta, String line) {
                int percent = progress == null ? 0 : Math.max(0, Math.min(99, Math.round(progress)));
                String detail = line == null ? "" : line.trim();
                callback.onProgress("جارٍ تنزيل الفيديو على الهاتف… " + percent + "%" + (detail.isEmpty() ? "" : " · " + detail));
                return Unit.INSTANCE;
            }
        });
        holder.exitCode = response.getExitCode();
        holder.error = response.getErr();
        return holder;
    }

    private static void ensureInitialized(Context context) throws YoutubeDLException {
        if (initialized) return;
        synchronized (INIT_LOCK) {
            if (initialized) return;
            YoutubeDL.getInstance().init(context.getApplicationContext());
            FFmpeg.getInstance().init(context.getApplicationContext());
            initialized = true;
        }
    }

    private static Set<String> fileNames(File directory) {
        Set<String> result = new HashSet<>();
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) result.add(file.getName());
        return result;
    }

    private static File newestNewFile(File directory, Set<String> before) {
        File newest = null;
        File[] files = directory.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (!file.isFile() || before.contains(file.getName()) || file.getName().endsWith(".part") || file.length() <= 0) continue;
            if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
        }
        return newest;
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

    private static void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (file.isDirectory()) deleteDirectory(file); else file.delete();
        }
        directory.delete();
    }

    private static String safeError(String error) {
        String value = error == null ? "" : error.replaceAll("\\s+", " ").trim();
        if (value.length() > 700) value = value.substring(value.length() - 700);
        return value.isEmpty() ? "تعذر تنزيل الفيديو من هذا الرابط." : value;
    }

    private static String userMessage(Exception error) {
        String raw = error.getMessage();
        String message = raw == null ? "" : raw;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("sign in") || lower.contains("login") || lower.contains("private")) return "الفيديو خاص أو يتطلب تسجيل الدخول؛ لا يمكن تنزيله بدون بيانات جلسة.";
        if (lower.contains("requested format is not available") || lower.contains("use --list-formats")) return "لم تُتح المنصة صيغة فيديو متوافقة لهذا الرابط. حدّث Waslora ثم أعد المحاولة؛ إذا استمرت المشكلة فقد يكون الفيديو غير متاح للتنزيل.";
        if (lower.contains("unsupported url")) return "رابط المنصة غير مدعوم في إصدار محرك التنزيل الحالي.";
        if (lower.contains("network") || lower.contains("connection") || lower.contains("timeout")) return "تعذر الاتصال بالمنصة. تحقق من الإنترنت ثم أعد المحاولة.";
        if (lower.contains("no such file") || lower.contains("ffmpeg")) return "تعذر تشغيل محرك الفيديو داخل الهاتف. أعد تثبيت النسخة الأخيرة من التطبيق.";
        return message.isEmpty() ? "تعذر تنزيل الفيديو من هذا الرابط." : message;
    }

    private static final class YoutubeDLResponseHolder {
        int exitCode;
        String error;
    }
}
