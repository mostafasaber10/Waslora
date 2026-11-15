package com.waslora.app;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.io.IOException;

public final class DirectDownloaderLinkTest {
    public static void main(String[] args) throws Exception {
        Method validate = DirectDownloader.class.getDeclaredMethod("validateUrl", String.class);
        validate.setAccessible(true);
        check(((URL) validate.invoke(null, "https://cdn.example/video.mp4?sig=abc")).getHost().equals("cdn.example"), "direct media URL should be accepted");
        rejects(validate, "https://www.facebook.com/share/v/1QRaD6gwyz/", "Facebook share pages need a direct media URL");
        rejects(validate, "https://youtu.be/example", "YouTube pages must be identified");
        rejects(validate, "http://m.instagram.com/reel/example", "Instagram pages must be identified");
        rejects(validate, "ftp://cdn.example/video.mp4", "unsupported protocols must be rejected");
        Method userMessage = DirectDownloader.class.getDeclaredMethod("userMessage", Exception.class);
        userMessage.setAccessible(true);
        String readable = (String) userMessage.invoke(null, new IOException("unexpected end of stream on com.android.okhttp.Address@123"));
        check(readable.contains("انقطع اتصال") && !readable.contains("com.android.okhttp"), "network errors should be understandable to users");
        System.out.println("Direct-download URL validation tests passed.");
    }

    private static void rejects(Method validate, String url, String reason) throws Exception {
        try {
            validate.invoke(null, url);
            throw new AssertionError(reason);
        } catch (InvocationTargetException expected) {
            if (!(expected.getCause() instanceof Exception)) throw expected;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
