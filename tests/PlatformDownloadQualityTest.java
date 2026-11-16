package com.waslora.app;

public final class PlatformDownloadQualityTest {
    public static void main(String[] args) {
        check("bv*+ba/b".equals(PlatformDownloadQuality.formatSelector("best")), "best quality selector");
        check("bv*[height<=1080]+ba/b[height<=1080]".equals(PlatformDownloadQuality.formatSelector("1080")), "1080p selector caps height and keeps audio");
        check("bv*[height<=360]+ba/b[height<=360]".equals(PlatformDownloadQuality.formatSelector("360")), "360p selector");
        check("bv*+ba/b".equals(PlatformDownloadQuality.formatSelector("invalid")), "untrusted selectors fall back safely");
        check("bv*+ba/b".equals(PlatformDownloadQuality.formatSelector("9999")), "unsupported heights fall back safely");
        check(PlatformDownloadQuality.isDirectChoice("720"), "720p is allowlisted");
        check(!PlatformDownloadQuality.isDirectChoice("720]/evil"), "format expressions cannot be injected");
        System.out.println("Platform download quality tests passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
