package com.waslora.app;

/** Allowlisted yt-dlp format selectors used by the platform download screen. */
public final class PlatformDownloadQuality {
    private PlatformDownloadQuality() {}

    public static String formatSelector(String quality) {
        if (quality == null || quality.trim().isEmpty() || "best".equalsIgnoreCase(quality.trim())) {
            return "bv*+ba/b";
        }
        final int height;
        try { height = Integer.parseInt(quality.trim()); }
        catch (NumberFormatException ignored) { return "bv*+ba/b"; }
        if (height != 2160 && height != 1440 && height != 1080 && height != 720 && height != 480 && height != 360) {
            return "bv*+ba/b";
        }
        // Use yt-dlp's video-only selector for the first branch.  `bv*` also
        // matches an already muxed video+audio format; combining that with
        // `ba` can make the whole expression fail on extractors that expose
        // only muxed formats.  `bestvideo` keeps the merge deterministic and
        // the `best[...]` branch handles platforms that do not expose
        // separate audio/video streams.
        // If a platform exposes no stream at or below the requested cap,
        // fall back to its best muxable stream instead of failing the job.
        return "bestvideo[height<=" + height + "]+bestaudio/best[height<=" + height + "]/bv*+ba/b";
    }

    public static boolean isDirectChoice(String quality) {
        if (quality == null) return false;
        switch (quality.trim()) {
            case "best": case "2160": case "1440": case "1080": case "720": case "480": case "360":
                return true;
            default: return false;
        }
    }
}
