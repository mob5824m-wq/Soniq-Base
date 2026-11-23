package com.soniq.music.data;

import java.util.LinkedHashMap;
import java.util.Map;

/** A resolved, directly playable audio stream plus the metadata shown in the UI. */
public final class ResolvedTrack {

    public String videoId = "";
    public String title = "";
    public String author = "";
    public String album = "";
    public String artworkUrl = "";
    public long durationSec = 0L;

    public String streamUrl = "";
    public String mimeType = "";
    public int bitrate = 0;
    public boolean hls = false;
    /** InnerTube client that produced the URL; used for diagnostics and the UA header. */
    public String clientKey = "";

    public final Map<String, String> headers = new LinkedHashMap<>();

    public boolean hasStream() {
        return streamUrl != null && streamUrl.length() > 0;
    }

    public String describe() {
        if (!hasStream()) {
            return "no stream";
        }
        if (hls) {
            return "HLS · " + clientKey;
        }
        return (bitrate > 0 ? (bitrate / 1000) + " kbps · " : "") + clientKey;
    }
}
