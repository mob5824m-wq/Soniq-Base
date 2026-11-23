package com.soniq.music.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Synced lyrics from the public lrclib.net API. Falls back to plain lyrics. */
public final class LyricsApi {

    private static final String USER_AGENT = "SoniqMusic/1.0 (Android; +https://github.com/mob5824m-wq/Soniq-Base)";

    public List<LyricLine> fetch(String rawTitle, String rawArtist) {
        String title = cleanTitle(rawTitle);
        String artist = rawArtist == null ? "" : rawArtist.replace(" - Topic", "").trim();
        if (title.length() == 0) {
            return new ArrayList<>();
        }
        try {
            String url = "https://lrclib.net/api/search?track_name="
                    + URLEncoder.encode(title, "UTF-8")
                    + (artist.length() > 0
                    ? "&artist_name=" + URLEncoder.encode(artist, "UTF-8") : "");
            String raw = get(url);
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null && item.optString("syncedLyrics", "").length() > 10) {
                    return LyricsParser.parse(item.optString("syncedLyrics"));
                }
            }
            JSONObject first = array.optJSONObject(0);
            if (first != null && first.optString("plainLyrics", "").length() > 0) {
                return LyricsParser.parse(first.optString("plainLyrics"));
            }
        } catch (Exception ignored) {
            // Lyrics are optional; an empty list is rendered as "no lyrics".
        }
        return new ArrayList<>();
    }

    static String cleanTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.replaceAll("(?i)\\((official\\s+video|official\\s+music\\s+video|"
                        + "official\\s+audio|lyrics?|visualizer|hd|4k)\\)", "")
                .replaceAll("(?i)\\[(official\\s+video|official\\s+audio|lyrics?|4k)\\]", "")
                .trim();
    }

    private static String get(String url) throws IOException {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            input = code >= 200 && code < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            if (code < 200 || code >= 400) {
                throw new IOException("HTTP " + code);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > 2 * 1024 * 1024) {
                    break;
                }
            }
            return new String(buffer.toByteArray(), "UTF-8");
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
