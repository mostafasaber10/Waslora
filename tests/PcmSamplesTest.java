package com.waslora.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class PcmSamplesTest {
    public static void main(String[] args) throws Exception {
        pcm8IsNotMisreadAsFloat();
        pcm16PreservesSignedSamples();
        pcmFloatIsClampedAndConverted();
        multichannelAudioIsDownmixedToStereo();
        System.out.println("PCM conversion tests passed.");
    }

    private static void pcm8IsNotMisreadAsFloat() throws Exception {
        byte[] converted = PcmSamples.toPcm16(ByteBuffer.wrap(new byte[]{0, (byte) 128, (byte) 255}), 0, 3, PcmSamples.PCM_8BIT, 1);
        short[] samples = shorts(converted);
        check(Arrays.equals(samples, new short[]{Short.MIN_VALUE, 0, 32512}), "unsigned PCM8 conversion: " + Arrays.toString(samples));
    }

    private static void pcm16PreservesSignedSamples() throws Exception {
        ByteBuffer source = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(Short.MIN_VALUE).putShort(Short.MAX_VALUE);
        byte[] converted = PcmSamples.toPcm16(ByteBuffer.wrap(source.array()), 0, 4, PcmSamples.PCM_16BIT, 1);
        check(Arrays.equals(shorts(converted), new short[]{Short.MIN_VALUE, Short.MAX_VALUE}), "PCM16 samples should retain their values");
    }

    private static void pcmFloatIsClampedAndConverted() throws Exception {
        ByteBuffer source = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(-1f).putFloat(0.5f).putFloat(1.5f);
        byte[] converted = PcmSamples.toPcm16(ByteBuffer.wrap(source.array()), 0, 12, PcmSamples.PCM_FLOAT, 1);
        check(Arrays.equals(shorts(converted), new short[]{Short.MIN_VALUE, 16384, Short.MAX_VALUE}), "PCM float conversion and clipping");
    }

    private static void multichannelAudioIsDownmixedToStereo() throws Exception {
        ByteBuffer source = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1000).putShort((short) 2000).putShort((short) 3000);
        byte[] converted = PcmSamples.toPcm16(ByteBuffer.wrap(source.array()), 0, 6, PcmSamples.PCM_16BIT, 3);
        short[] samples = shorts(converted);
        check(samples.length == 2 && samples[0] == 2000 && samples[1] == 2000, "multichannel downmix: " + Arrays.toString(samples));
    }

    private static short[] shorts(byte[] bytes) {
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) samples[i] = input.getShort();
        return samples;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
