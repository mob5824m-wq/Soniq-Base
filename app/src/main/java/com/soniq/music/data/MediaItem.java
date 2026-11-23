package com.soniq.music.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One entry in the music graph: a playable song, an artist, an album, a playlist
 * or an offline file.
 *
 * <p>The upstream Kobo app used public mutable fields on this model. That shape is
 * preserved here because the InnerTube parser assigns many optional fields while
 * walking deeply nested responses; turning every assignment into a builder call
 * would make the port harder to diff against the original.
 */
public final class MediaItem {

    public static final String KIND_ARTIST = "artist";
    public static final String KIND_ALBUM = "album";
    public static final String KIND_PLAYLIST = "playlist";

    public String videoId = "";
    public String browseId = "";
    public String playlistId = "";
    /** "artist", "album", "playlist" or "" for a plain song. */
    public String collectionType = "";
    public String title = "";
    public String author = "";
    public String channelId = "";
    public String album = "";
    public String durationText = "";
    public long durationSec = 0L;
    public String thumbnailUrl = "";
    public boolean isLive = false;
    public boolean isMusicTrack = false;
    /** Content URI or file path for an already-downloaded track. */
    public String localUri = "";

    public boolean isArtist() {
        return KIND_ARTIST.equals(collectionType) && browseId.matches("UC[A-Za-z0-9_-]{22}");
    }

    public boolean isCollection() {
        boolean knownKind = KIND_ALBUM.equals(collectionType) || KIND_PLAYLIST.equals(collectionType);
        return knownKind && (browseId.length() > 0 || playlistId.length() > 0);
    }

    public boolean isBrowsable() {
        return isArtist() || isCollection();
    }

    public boolean isPlayable() {
        if (isBrowsable()) {
            return false;
        }
        return videoId.length() >= 8 || localUri.length() > 0;
    }

    public boolean isOffline() {
        return localUri.length() > 0;
    }

    /** Stable identity used for liked songs, playlists and de-duplication. */
    public String identity() {
        if (isArtist()) {
            return "artist:" + browseId;
        }
        if (isCollection()) {
            if (KIND_ALBUM.equals(collectionType) && browseId.length() > 0) {
                return "album:" + browseId;
            }
            String playlist = playlistId.length() > 0
                    ? playlistId
                    : browseId.startsWith("VL") ? browseId.substring(2) : browseId;
            return "playlist:" + playlist;
        }
        if (videoId.length() > 0) {
            return "video:" + videoId;
        }
        return localUri.length() > 0 ? "local:" + localUri : "";
    }

    public String bestThumb() {
        if (thumbnailUrl != null && thumbnailUrl.length() > 0) {
            return thumbnailUrl;
        }
        if (videoId.length() >= 8) {
            return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        }
        return "";
    }

    public String subtitle() {
        if (isArtist()) {
            return author.length() > 0 ? "ARTIST · " + author : "ARTIST";
        }
        if (isCollection()) {
            String kind = collectionType.toUpperCase(Locale.US);
            return author.length() > 0 ? kind + " · " + author : kind;
        }
        StringBuilder sb = new StringBuilder();
        if (author.length() > 0) {
            sb.append(author);
        }
        if (album.length() > 0) {
            if (sb.length() > 0) {
                sb.append("  •  ");
            }
            sb.append(album);
        }
        if (durationText.length() > 0) {
            if (sb.length() > 0) {
                sb.append("  •  ");
            }
            sb.append(durationText);
        }
        return sb.toString();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("v", videoId);
            o.put("browse", browseId);
            o.put("playlist", playlistId);
            o.put("channel", channelId);
            o.put("kind", collectionType);
            o.put("t", title);
            o.put("a", author);
            o.put("al", album);
            o.put("d", durationText);
            o.put("sec", durationSec);
            o.put("th", thumbnailUrl);
            o.put("m", isMusicTrack);
            o.put("live", isLive);
            o.put("local", localUri);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static MediaItem fromJson(JSONObject o) {
        MediaItem m = new MediaItem();
        if (o == null) {
            return m;
        }
        m.videoId = o.optString("v", "");
        m.browseId = o.optString("browse", "");
        m.playlistId = o.optString("playlist", "");
        m.channelId = o.optString("channel", "");
        m.collectionType = o.optString("kind", "");
        m.title = o.optString("t", "");
        m.author = o.optString("a", "");
        m.album = o.optString("al", "");
        m.durationText = o.optString("d", "");
        m.durationSec = o.optLong("sec", 0L);
        m.thumbnailUrl = o.optString("th", "");
        m.isMusicTrack = o.optBoolean("m", false);
        m.isLive = o.optBoolean("live", false);
        m.localUri = o.optString("local", "");
        return m;
    }

    public static List<MediaItem> fromJsonArray(JSONArray array) {
        List<MediaItem> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o != null) {
                out.add(fromJson(o));
            }
        }
        return out;
    }

    public static JSONArray toJsonArray(List<MediaItem> items) {
        JSONArray array = new JSONArray();
        if (items == null) {
            return array;
        }
        for (MediaItem item : items) {
            if (item != null) {
                array.put(item.toJson());
            }
        }
        return array;
    }

    public static List<MediaItem> copy(List<MediaItem> source) {
        List<MediaItem> out = new ArrayList<>();
        if (source != null) {
            for (MediaItem item : source) {
                if (item != null) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return title + " (" + identity() + ")";
    }
}
