package com.waslora.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Converts Android decoder PCM buffers into interleaved signed little-endian PCM16 for the MP3 encoder. */
final class PcmSamples {
    static final int PCM_16BIT = 2;
    static final int PCM_8BIT = 3;
    static final int PCM_FLOAT = 4;

    private PcmSamples() {}

    static byte[] toPcm16(ByteBuffer original, int offset, int size, int encoding, int sourceChannels) throws Exception {
        if (sourceChannels < 1) sourceChannels = 1;
        int bytesPerSample;
        if (encoding == PCM_16BIT) bytesPerSample = 2;
        else if (encoding == PCM_8BIT) bytesPerSample = 1;
        else if (encoding == PCM_FLOAT) bytesPerSample = 4;
        else throw new Exception("صيغة PCM التي أعادها الهاتف غير مدعومة لتحويل MP3.");

        int frameBytes = bytesPerSample * sourceChannels;
        int frames = size / frameBytes;
        int outputChannels = sourceChannels == 1 ? 1 : 2;
        ByteBuffer source = original.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        source.position(offset);
        source.limit(offset + frames * frameBytes);
        ByteBuffer output = ByteBuffer.allocate(frames * outputChannels * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            float left;
            float right;
            if (sourceChannels == 1) {
                left = readSample(source, encoding);
                right = left;
            } else if (sourceChannels == 2) {
                left = readSample(source, encoding);
                right = readSample(source, encoding);
            } else {
                float sumLeft = 0, sumRight = 0;
                int leftCount = 0, rightCount = 0;
                for (int channel = 0; channel < sourceChannels; channel++) {
                    float value = readSample(source, encoding);
                    if ((channel & 1) == 0) { sumLeft += value; leftCount++; }
                    else { sumRight += value; rightCount++; }
                }
                left = leftCount == 0 ? 0 : sumLeft / leftCount;
                right = rightCount == 0 ? left : sumRight / rightCount;
            }
            output.putShort(toShort(left));
            if (outputChannels == 2) output.putShort(toShort(right));
        }
        return output.array();
    }

    private static float readSample(ByteBuffer source, int encoding) {
        if (encoding == PCM_16BIT) return source.getShort() / 32768.0f;
        if (encoding == PCM_FLOAT) return source.getFloat();
        return ((source.get() & 0xff) - 128) / 128.0f;
    }

    private static short toShort(float value) {
        if (Float.isNaN(value)) value = 0;
        value = Math.max(-1.0f, Math.min(1.0f, value));
        int converted = Math.round(value * 32768.0f);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, converted));
    }
}
