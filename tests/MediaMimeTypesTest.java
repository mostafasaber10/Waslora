package com.waslora.app;

public final class MediaMimeTypesTest {
    public static void main(String[] args) {
        check("image/jpeg".equals(MediaMimeTypes.forFileName("photo.JPG")), "photos should keep an image MIME type");
        check("image/png".equals(MediaMimeTypes.forFileName("screenshot.png")), "PNG should stay an image");
        check("video/mp4".equals(MediaMimeTypes.forFileName("clip.mp4")), "MP4 should stay a video");
        check("video/quicktime".equals(MediaMimeTypes.forFileName("clip.mov")), "MOV should stay a video");
        check("audio/mpeg".equals(MediaMimeTypes.forFileName("song.mp3")), "MP3 should stay audio");
        check("application/octet-stream".equals(MediaMimeTypes.forFileName("unknown.bin")), "unknown files should remain generic");
        System.out.println("Media MIME type tests passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
