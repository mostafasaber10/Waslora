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
import java.util.concurrent.ConcurrentHashMap;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/** Runs yt-dlp and FFmpeg inside the phone; no Resolver server is required. */
public final class LocalPlatformDownloader {
    public interface Callback {
        void onProgress(String message);
        void onComplete(File file);
        void onError(String message);
        void onPaused();
        void onResumed();
    }

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initialized;
    private static final ConcurrentHashMap<String, Job> JOBS = new ConcurrentHashMap<>();

    private LocalPlatformDownloader() {}

    public static String download(Context context, String pageUrl, String quality, Callback callback) {
        Job job = new Job(context.getApplicationContext(), pageUrl == null ? "" : pageUrl.trim(), quality, callback);
        JOBS.put(job.id, job);
        run(job);
        return job.id;
    }

    public static void pause(String id) {
        Job job = JOBS.get(id);
        if (job == null) return;
        synchronized (job.lock) {
            if (job.paused || job.pauseRequested || job.finished) return;
            job.pauseRequested = true;
        }
        YoutubeDL.getInstance().destroyProcessById(job.id);
    }

    public static void resume(String id) {
        Job job = JOBS.get(id);
        if (job == null) return;
        synchronized (job.lock) {
            if (job.finished) return;
            if (job.pauseRequested) {
                job.resumeRequested = true;
                return;
            }
            if (!job.paused) return;
            job.paused = false;
        }
        job.callback.onResumed();
        run(job);
    }

    private static void run(Job job) {
        synchronized (job.lock) {
            if (job.running || job.finished) return;
            job.running = true;
        }
        new Thread(() -> runJob(job), "Waslora-platform-download").start();
    }

    private static void runJob(Job job) {
        try {
            URI parsed = new URI(job.pageUrl);
            if (parsed.getHost() == null || parsed.getUserInfo() != null ||
                    !("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))) {
                throw new Exception("أدخل رابطًا كاملًا يبدأ بـ http أو https.");
            }
            if (!ResolverClient.isPlatformUrl(job.pageUrl)) {
                throw new Exception("أرسل رابط صفحة فيديو عامة من YouTube أو Facebook أو Instagram أو TikTok أو منصة مدعومة.");
            }
            job.callback.onProgress("جارٍ تجهيز محرك التنزيل داخل الهاتف…");
            ensureInitialized(job.context);
            File directory = DirectDownloader.downloadsDirectory(job.context);
            if (!directory.exists() && !directory.mkdirs()) throw new Exception("تعذر إنشاء مجلد التنزيلات.");
            if (job.jobDirectory == null) {
                job.jobDirectory = new File(directory, ".platform-" + job.id);
                if (!job.jobDirectory.mkdirs()) throw new Exception("تعذر إنشاء مجلد مهمة التنزيل.");
                job.before = fileNames(job.jobDirectory);
                String template = new File(job.jobDirectory, "%(title).100B-%(id)s.%(ext)s").getAbsolutePath();
                job.request = new YoutubeDLRequest(job.pageUrl);
                job.request.addOption("--no-playlist");
                job.request.addOption("--no-warnings");
                job.request.addOption("--newline");
                job.request.addOption("--retries", "5");
                job.request.addOption("--fragment-retries", "5");
                job.request.addOption("--concurrent-fragments", "4");
                job.request.addOption("--socket-timeout", "30");
                job.request.addOption("-f", PlatformDownloadQuality.formatSelector(job.quality));
                job.request.addOption("-o", template);
            }
            if (finishPause(job)) return;
            job.callback.onProgress("جارٍ تنزيل الفيديو…");
            YoutubeDLResponseHolder result = execute(job.request, job.id, job.callback);
            boolean restart = false;
            synchronized (job.lock) {
                job.running = false;
                if (job.pauseRequested) {
                    job.pauseRequested = false;
                    if (job.resumeRequested) {
                        job.resumeRequested = false;
                        job.paused = false;
                        restart = true;
                    } else {
                        job.paused = true;
                    }
                } else if (result.exitCode == 0) {
                    File downloaded = newestNewFile(job.jobDirectory, job.before);
                    if (downloaded == null) throw new Exception("انتهى المحرك بدون ملف فيديو. قد يكون الرابط خاصًا أو غير مدعوم.");
                    File output = uniqueFile(directory, downloaded.getName());
                    if (!downloaded.renameTo(output)) throw new Exception("تعذر حفظ ملف الفيديو في مجلد التنزيلات.");
                    job.finished = true;
                    JOBS.remove(job.id);
                    deleteDirectory(job.jobDirectory);
                    job.callback.onComplete(output);
                    return;
                } else {
                    throw new Exception(safeError(result.error));
                }
            }
            if (restart) {
                job.callback.onResumed();
                run(job);
            } else {
                job.callback.onPaused();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!finishPause(job)) fail(job, "تم إيقاف تنزيل الفيديو.");
        } catch (Exception e) {
            if (!finishPause(job)) fail(job, userMessage(e));
        }
    }

    private static boolean finishPause(Job job) {
        boolean restart = false;
        synchronized (job.lock) {
            if (!job.pauseRequested) return false;
            job.pauseRequested = false;
            job.running = false;
            if (job.resumeRequested) {
                job.resumeRequested = false;
                job.paused = false;
                restart = true;
            } else {
                job.paused = true;
            }
        }
        if (restart) {
            job.callback.onResumed();
            run(job);
        } else {
            job.callback.onPaused();
        }
        return true;
    }

    private static void fail(Job job, String message) {
        synchronized (job.lock) {
            if (job.pauseRequested || job.finished) return;
            job.finished = true;
            job.running = false;
        }
        JOBS.remove(job.id);
        if (job.jobDirectory != null) deleteDirectory(job.jobDirectory);
        job.callback.onError(message);
    }

    private static YoutubeDLResponseHolder execute(YoutubeDLRequest request, String jobId, Callback callback) throws Exception {
        final YoutubeDLResponseHolder holder = new YoutubeDLResponseHolder();
        // Each job gets its own yt-dlp identifier.  Reusing one identifier can
        // make concurrent downloads overwrite each other's state/progress.
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

    private static final class Job {
        final String id = "waslora-platform-" + UUID.randomUUID();
        final Context context;
        final String pageUrl;
        final String quality;
        final Callback callback;
        final Object lock = new Object();
        volatile boolean running;
        volatile boolean paused;
        volatile boolean pauseRequested;
        volatile boolean resumeRequested;
        volatile boolean finished;
        File jobDirectory;
        Set<String> before;
        YoutubeDLRequest request;
        Job(Context context, String pageUrl, String quality, Callback callback) {
            this.context = context; this.pageUrl = pageUrl; this.quality = quality; this.callback = callback;
        }
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
