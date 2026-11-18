package com.waslora.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class AudioConverter {
    public interface Callback {
        void onProgress(String message);
        void onComplete(File file);
        void onError(String message);
    }

    private static final ConcurrentHashMap<String, Mp3Session> MP3_SESSIONS = new ConcurrentHashMap<>();
    private static final int MP3_BITRATE_KBPS = 192;

    private AudioConverter() {}

    public static void convert(Context context, Uri source, String format, Callback callback) {
        new Thread(() -> {
            if ("mp3".equalsIgnoreCase(format)) {
                if (!(context instanceof MainActivity)) {
                    callback.onError("تعذر تشغيل محرك MP3 في هذه الشاشة.");
                    return;
                }
                convertToMp3((MainActivity) context, source, callback);
                return;
            }
            try {
                File input = source.getScheme() == null || "file".equalsIgnoreCase(source.getScheme()) ? new File(source.getPath()) : null;
                String baseName = input == null ? "audio-" + System.currentTimeMillis() : stripExtension(input.getName());
                File directory = DirectDownloader.downloadsDirectory(context);
                if (!directory.exists() && !directory.mkdirs()) throw new Exception("تعذر إنشاء مجلد الملفات.");
                File destination = unique(directory, baseName + ".m4a");
                extractAac(context, source, destination, callback);
                validateM4a(destination);
                callback.onComplete(destination);
            } catch (Exception e) {
                callback.onError(messageFor(e));
            }
        }, "Waslora-audio-convert").start();
    }

    public static void onEncodedChunk(String jobId, String encoded, boolean finished) {
        Mp3Session session = MP3_SESSIONS.get(jobId);
        if (session != null) session.accept(encoded, finished);
    }

    public static void onEncoderError(String jobId, String message) {
        Mp3Session session = MP3_SESSIONS.get(jobId);
        if (session != null) session.fail(message == null || message.isEmpty() ? "تعذر ترميز ملف MP3." : message);
    }

    public static void onPcmAck(String jobId) {
        Mp3Session session = MP3_SESSIONS.get(jobId);
        if (session != null) session.acknowledgePcm();
    }

    private static void convertToMp3(MainActivity activity, Uri source, Callback callback) {
        File input = source.getScheme() == null || "file".equalsIgnoreCase(source.getScheme()) ? new File(source.getPath()) : null;
        String baseName = input == null ? "audio-" + System.currentTimeMillis() : stripExtension(input.getName());
        File directory = DirectDownloader.downloadsDirectory(activity);
        if (!directory.exists() && !directory.mkdirs()) { callback.onError("تعذر إنشاء مجلد الملفات."); return; }
        File output = unique(directory, baseName + ".mp3");
        String id = UUID.randomUUID().toString();
        Mp3Session session;
        try {
            session = new Mp3Session(id, activity, output, callback);
            MP3_SESSIONS.put(id, session);
            decodeToMp3(activity, source, session);
        } catch (Exception e) {
            Mp3Session found = MP3_SESSIONS.get(id);
            if (found != null) found.fail(messageFor(e));
            else callback.onError(messageFor(e));
        }
    }

    private static void decodeToMp3(Context context, Uri source, Mp3Session session) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        boolean decoderStarted = false;
        try {
            setDataSource(context, extractor, source);
            int audioTrack = findAudioTrack(extractor);
            if (audioTrack < 0) throw new Exception("لم أجد مسارًا صوتيًا داخل الفيديو.");
            MediaFormat inputFormat = extractor.getTrackFormat(audioTrack);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new Exception("تعذر تحديد ترميز الصوت داخل الفيديو.");
            int fallbackRate = getInt(inputFormat, MediaFormat.KEY_SAMPLE_RATE, 44100);
            int fallbackChannels = getInt(inputFormat, MediaFormat.KEY_CHANNEL_COUNT, 2);
            extractor.selectTrack(audioTrack);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();
            decoderStarted = true;

            boolean inputEnded = false;
            boolean outputEnded = false;
            boolean encoderStarted = false;
            int sampleRate = fallbackRate;
            int channels = fallbackChannels;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastProgress = 0;

            while (!outputEnded) {
                if (!inputEnded) {
                    int inputIndex = decoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        if (inputBuffer == null) throw new Exception("تعذر قراءة إطار صوتي.");
                        inputBuffer.clear();
                        int size = extractor.readSampleData(inputBuffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            long time = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputIndex, 0, size, Math.max(0, time), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat decodedFormat = decoder.getOutputFormat();
                    sampleRate = getInt(decodedFormat, MediaFormat.KEY_SAMPLE_RATE, fallbackRate);
                    channels = getInt(decodedFormat, MediaFormat.KEY_CHANNEL_COUNT, fallbackChannels);
                    pcmEncoding = getInt(decodedFormat, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                    ensureSupportedRate(sampleRate);
                    session.startEncoder(sampleRate, channels == 1 ? 1 : 2, MP3_BITRATE_KBPS);
                    encoderStarted = true;
                } else if (outputIndex >= 0) {
                    if (info.size > 0) {
                        if (!encoderStarted) {
                            ensureSupportedRate(sampleRate);
                            session.startEncoder(sampleRate, channels == 1 ? 1 : 2, MP3_BITRATE_KBPS);
                            encoderStarted = true;
                        }
                        ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                        if (outputBuffer == null) throw new Exception("تعذر قراءة الصوت بعد فك ترميزه.");
                        byte[] pcm = PcmSamples.toPcm16(outputBuffer, info.offset, info.size, pcmEncoding, channels);
                        if (pcm.length > 0) session.pushPcm(pcm);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outputIndex, false);
                    if (eos) outputEnded = true;
                    long now = System.currentTimeMillis();
                    if (now - lastProgress > 900) {
                        session.progress("جارٍ استخراج الصوت وترميزه إلى MP3…");
                        lastProgress = now;
                    }
                }
            }
            if (!encoderStarted) {
                ensureSupportedRate(sampleRate);
                session.startEncoder(sampleRate, channels == 1 ? 1 : 2, MP3_BITRATE_KBPS);
            }
            session.finishEncoder();
        } finally {
            if (decoder != null) {
                if (decoderStarted) try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private static void ensureSupportedRate(int rate) throws Exception {
        int[] supported = {48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000};
        for (int value : supported) if (value == rate) return;
        throw new Exception("معدل عينة الصوت " + rate + " غير مدعوم مباشرة بصيغة MP3.");
    }

    private static void extractAac(Context context, Uri source, File output, Callback callback) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            setDataSource(context, extractor, source);
            int selected = findAudioTrack(extractor);
            if (selected < 0) throw new Exception("لم أجد مسارًا صوتيًا داخل الفيديو.");
            MediaFormat audioFormat = extractor.getTrackFormat(selected);
            String mime = audioFormat.getString(MediaFormat.KEY_MIME);
            if (!"audio/mp4a-latm".equalsIgnoreCase(mime)) {
                // M4A is a container, not an AAC-only input requirement. Decode
                // other audio codecs and encode AAC so videos with Opus, Vorbis,
                // AC-3, or PCM tracks also produce a usable .m4a file.
                transcodeToAac(context, source, output, callback, audioFormat);
                return;
            }
            extractor.selectTrack(selected);
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outputTrack = muxer.addTrack(audioFormat);
            muxer.start();
            muxerStarted = true;
            boolean wroteSamples = false;
            int max = audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 1024 * 1024;
            ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(64 * 1024, Math.min(max, 4 * 1024 * 1024)));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long duration = audioFormat.containsKey(MediaFormat.KEY_DURATION) ? audioFormat.getLong(MediaFormat.KEY_DURATION) : -1;
            long lastReport = 0;
            while (true) {
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                long time = extractor.getSampleTime();
                info.set(0, size, time, extractor.getSampleFlags());
                muxer.writeSampleData(outputTrack, buffer, info);
                wroteSamples = true;
                extractor.advance();
                if (duration > 0 && time - lastReport > 2_000_000L) {
                    int percent = (int) Math.min(99, (time * 100) / duration);
                    callback.onProgress("استخراج M4A بسرعة من دون إعادة ترميز… " + percent + "%");
                    lastReport = time;
                }
            }
            if (!wroteSamples) throw new Exception("لم يحتوِ الفيديو على عينات صوتية قابلة للحفظ.");
            muxer.stop();
            muxerStarted = false;
            callback.onProgress("جارٍ إنهاء ملف الصوت…");
        } catch (Exception e) {
            if (output.exists()) output.delete();
            throw e;
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            if (muxer != null) {
                try { if (muxerStarted) muxer.stop(); } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    private static void transcodeToAac(Context context, Uri source, File output,
                                        Callback callback, MediaFormat inputFormat) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean decoderStarted = false;
        boolean encoderStarted = false;
        boolean muxerStarted = false;
        try {
            setDataSource(context, extractor, source);
            int selected = findAudioTrack(extractor);
            if (selected < 0) throw new Exception("لم أجد مسارًا صوتيًا داخل الفيديو.");
            MediaFormat trackFormat = extractor.getTrackFormat(selected);
            String mime = trackFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new Exception("تعذر تحديد ترميز الصوت داخل الفيديو.");
            int sampleRate = getInt(trackFormat, MediaFormat.KEY_SAMPLE_RATE, 44100);
            int sourceChannels = getInt(trackFormat, MediaFormat.KEY_CHANNEL_COUNT, 2);
            int channels = sourceChannels == 1 ? 1 : 2;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            if (sampleRate <= 0) sampleRate = 44100;
            extractor.selectTrack(selected);

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(trackFormat, null, null, 0);
            decoder.start();
            decoderStarted = true;

            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            ArrayDeque<PcmChunk> pending = new ArrayDeque<>();
            MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();
            boolean extractorEnded = false;
            boolean decoderEnded = false;
            boolean encoderInputEnded = false;
            boolean encoderEnded = false;
            boolean wroteSamples = false;
            long encodedFrames = 0;
            long lastProgress = 0;

            while (!encoderEnded) {
                if (!extractorEnded) {
                    int inputIndex = decoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(inputIndex);
                        if (buffer == null) throw new Exception("تعذر قراءة إطار الصوت.");
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            extractorEnded = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, Math.max(0, extractor.getSampleTime()), 0);
                            extractor.advance();
                        }
                    }
                }

                int decodedIndex = decoder.dequeueOutputBuffer(decoderInfo, 10_000);
                if (decodedIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat decodedFormat = decoder.getOutputFormat();
                    sampleRate = getInt(decodedFormat, MediaFormat.KEY_SAMPLE_RATE, sampleRate);
                    sourceChannels = getInt(decodedFormat, MediaFormat.KEY_CHANNEL_COUNT, sourceChannels);
                    channels = sourceChannels == 1 ? 1 : 2;
                    pcmEncoding = getInt(decodedFormat, MediaFormat.KEY_PCM_ENCODING, pcmEncoding);
                    if (!encoderStarted) {
                        encoder = createAacEncoder(sampleRate, channels);
                        encoderStarted = true;
                    }
                } else if (decodedIndex >= 0) {
                    if (decoderInfo.size > 0) {
                        if (!encoderStarted) {
                            encoder = createAacEncoder(sampleRate, channels);
                            encoderStarted = true;
                        }
                        ByteBuffer pcm = decoder.getOutputBuffer(decodedIndex);
                        if (pcm == null) throw new Exception("تعذر قراءة الصوت بعد فك ترميزه.");
                        byte[] bytes = PcmSamples.toPcm16(pcm, decoderInfo.offset, decoderInfo.size, pcmEncoding, sourceChannels);
                        if (bytes.length > 0) pending.add(new PcmChunk(bytes));
                    }
                    if ((decoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decoderEnded = true;
                    decoder.releaseOutputBuffer(decodedIndex, false);
                }

                while (!pending.isEmpty() || (decoderEnded && !encoderInputEnded)) {
                    int inputIndex = encoder.dequeueInputBuffer(0);
                    if (inputIndex < 0) break;
                    ByteBuffer input = encoder.getInputBuffer(inputIndex);
                    if (input == null) throw new Exception("تعذر تجهيز ملف M4A.");
                    input.clear();
                    if (!pending.isEmpty()) {
                        PcmChunk chunk = pending.peek();
                        int count = Math.min(input.remaining(), chunk.data.length - chunk.offset);
                        int frameBytes = channels * 2;
                        count -= count % frameBytes;
                        if (count <= 0) throw new Exception("تعذر محاذاة عينات الصوت لمشفر M4A.");
                        input.put(chunk.data, chunk.offset, count);
                        chunk.offset += count;
                        if (chunk.offset >= chunk.data.length) pending.remove();
                        long pts = encodedFrames * 1_000_000L / sampleRate;
                        encodedFrames += count / frameBytes;
                        encoder.queueInputBuffer(inputIndex, 0, count, Math.max(0, pts), 0);
                    } else {
                        long pts = encodedFrames * 1_000_000L / sampleRate;
                        encoder.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        encoderInputEnded = true;
                    }
                }

                while (true) {
                    int encodedIndex = encoder.dequeueOutputBuffer(encoderInfo, 0);
                    if (encodedIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (encodedIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new Exception("تغيّر تنسيق الصوت أثناء التحويل.");
                        int track = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                        continue;
                    }
                    if (encodedIndex < 0) continue;
                    if (encoderInfo.size > 0) {
                        ByteBuffer encoded = encoder.getOutputBuffer(encodedIndex);
                        if (encoded == null) throw new Exception("تعذر حفظ إطار الصوت.");
                        if (muxerStarted) {
                            encoded.position(encoderInfo.offset);
                            encoded.limit(encoderInfo.offset + encoderInfo.size);
                            muxer.writeSampleData(0, encoded, encoderInfo);
                            wroteSamples = true;
                        }
                    }
                    if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderEnded = true;
                    encoder.releaseOutputBuffer(encodedIndex, false);
                }
                long now = System.currentTimeMillis();
                if (now - lastProgress > 900) {
                    callback.onProgress("جارٍ إعادة ترميز الصوت إلى M4A…");
                    lastProgress = now;
                }
            }
            if (!muxerStarted) throw new Exception("لم ينتج محرك الصوت ملفًا صالحًا.");
            if (!wroteSamples) throw new Exception("لم ينتج محرك M4A عينات صوتية.");
            muxer.stop();
            muxerStarted = false;
            callback.onProgress("جارٍ إنهاء ملف M4A…");
        } catch (Exception e) {
            if (output.exists()) output.delete();
            throw e;
        } finally {
            if (decoder != null) {
                if (decoderStarted) try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
            if (encoder != null) {
                if (encoderStarted) try { encoder.stop(); } catch (Exception ignored) {}
                try { encoder.release(); } catch (Exception ignored) {}
            }
            try { extractor.release(); } catch (Exception ignored) {}
            if (muxer != null) {
                try { if (muxerStarted) muxer.stop(); } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    private static final class PcmChunk {
        final byte[] data;
        int offset;
        PcmChunk(byte[] data) { this.data = data; }
    }

    private static MediaCodec createAacEncoder(int sampleRate, int channels) throws Exception {
        MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
        MediaCodec codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            return codec;
        } catch (Exception error) {
            try { codec.release(); } catch (Exception ignored) {}
            throw error;
        }
    }

    private static void validateM4a(File file) throws Exception {
        if (!file.isFile() || file.length() <= 0) throw new Exception("ملف M4A الناتج فارغ أو غير موجود.");
        MediaExtractor verifier = new MediaExtractor();
        try {
            verifier.setDataSource(file.getAbsolutePath());
            int audioTrack = findAudioTrack(verifier);
            if (audioTrack < 0) throw new Exception("ملف M4A الناتج لا يحتوي على مسار صوت صالح.");
            verifier.selectTrack(audioTrack);
            ByteBuffer sample = ByteBuffer.allocateDirect(256 * 1024);
            int size = verifier.readSampleData(sample, 0);
            if (size <= 0) throw new Exception("ملف M4A الناتج لا يحتوي على عينات صوت قابلة للتشغيل.");
        } catch (Exception error) {
            if (file.exists()) file.delete();
            if (error.getMessage() != null && error.getMessage().startsWith("ملف M4A")) throw error;
            throw new Exception("تعذر التحقق من ملف M4A الناتج؛ أعد التحويل أو جرّب MP3.", error);
        } finally {
            try { verifier.release(); } catch (Exception ignored) {}
        }
    }

    private static void setDataSource(Context context, MediaExtractor extractor, Uri source) throws Exception {
        if (source.getScheme() == null || "file".equalsIgnoreCase(source.getScheme())) extractor.setDataSource(source.getPath());
        else extractor.setDataSource(context, source, null);
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    private static int getInt(MediaFormat format, String key, int fallback) {
        try { return format.containsKey(key) ? format.getInteger(key) : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static String messageFor(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) return "تعذر استخراج الصوت من هذا الفيديو.";
        if (message.contains("No decoder") || message.contains("codec")) return "هذا الفيديو يستخدم ترميزًا لا يدعمه الهاتف.";
        return message;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static File unique(File directory, String name) {
        File output = new File(directory, name);
        if (!output.exists()) return output;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int n = 1;
        do { output = new File(directory, base + " (" + n++ + ")" + extension); }
        while (output.exists());
        return output;
    }

    private static final class Mp3Session {
        final String id;
        final MainActivity activity;
        final File output;
        final File temporary;
        final Callback callback;
        final Object lock = new Object();
        final Semaphore pcmAcknowledged = new Semaphore(0);
        FileOutputStream stream;
        long bytesWritten;
        boolean started;
        boolean completed;
        boolean awaitingPcmAck;

        Mp3Session(String id, MainActivity activity, File output, Callback callback) throws Exception {
            this.id = id; this.activity = activity; this.output = output; this.callback = callback;
            this.temporary = new File(output.getParentFile(), output.getName() + ".part");
            this.stream = new FileOutputStream(temporary);
        }

        void startEncoder(int rate, int channels, int bitrate) throws Exception {
            synchronized (lock) {
                if (started) return;
                started = true;
            }
            activity.startMp3Encoder(id, rate, channels, bitrate);
        }

        void pushPcm(byte[] pcm) throws Exception {
            int maxChunk = 24 * 1024;
            int offset = 0;
            while (offset < pcm.length) {
                awaitPreviousPcm();
                if (isCompleted()) throw new Exception("توقف تحويل MP3 قبل اكتمال الملف.");
                int length = Math.min(maxChunk, pcm.length - offset);
                if ((length & 1) != 0) length--;
                byte[] chunk = new byte[length];
                System.arraycopy(pcm, offset, chunk, 0, length);
                String encoded = Base64.encodeToString(chunk, Base64.NO_WRAP);
                activity.pushMp3Pcm(id, encoded);
                awaitingPcmAck = true;
                offset += length;
            }
        }

        void finishEncoder() throws Exception {
            awaitPreviousPcm();
            if (isCompleted()) throw new Exception("توقف تحويل MP3 قبل اكتمال الملف.");
            activity.finishMp3Encoder(id);
        }

        void acknowledgePcm() { pcmAcknowledged.release(); }

        private void awaitPreviousPcm() throws Exception {
            if (!awaitingPcmAck) return;
            if (!pcmAcknowledged.tryAcquire(90, TimeUnit.SECONDS)) throw new Exception("استغرق ترميز الصوت وقتًا طويلًا؛ أعد المحاولة.");
            awaitingPcmAck = false;
        }

        private boolean isCompleted() { synchronized (lock) { return completed; } }

        void progress(String message) { callback.onProgress(message); }

        void accept(String encoded, boolean finished) {
            synchronized (lock) {
                if (completed) return;
                try {
                    if (encoded != null && !encoded.isEmpty()) {
                        byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                        stream.write(bytes);
                        bytesWritten += bytes.length;
                    }
                    if (!finished) {
                        if (bytesWritten > 0 && (bytesWritten & 0x3ffff) < 16000) callback.onProgress("جارٍ ترميز MP3… " + (bytesWritten / 1024) + " KB");
                        return;
                    }
                    stream.flush(); stream.close(); stream = null;
                    completed = true;
                    MP3_SESSIONS.remove(id);
                    if (bytesWritten == 0) { temporary.delete(); callback.onError("لم ينتج محرك MP3 بيانات صوتية."); return; }
                    if (!temporary.renameTo(output)) { temporary.delete(); callback.onError("تعذر حفظ ملف MP3."); return; }
                    callback.onComplete(output);
                } catch (Exception e) { failLocked(messageFor(e)); }
            }
        }

        void fail(String message) {
            synchronized (lock) { failLocked(message); }
        }

        private void failLocked(String message) {
            if (completed) return;
            completed = true;
            MP3_SESSIONS.remove(id);
            pcmAcknowledged.release();
            if (stream != null) try { stream.close(); } catch (Exception ignored) {}
            stream = null;
            temporary.delete();
            activity.abortMp3Encoder(id);
            callback.onError(message);
        }
    }
}
