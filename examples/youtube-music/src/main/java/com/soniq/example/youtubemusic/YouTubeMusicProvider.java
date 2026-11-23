package com.soniq.example.youtubemusic;

import android.content.Context;

import com.soniq.base.model.MediaItem;
import com.soniq.base.model.StreamSource;
import com.soniq.base.provider.MusicProvider;

import java.io.IOException;
import java.util.List;

/**
 * Adapts the YouTube Music InnerTube client ({@link YouTubeMusicApi}) to the
 * framework's {@link MusicProvider} SPI. This is the only class the framework
 * sees; everything YouTube-specific stays behind it.
 */
public final class YouTubeMusicProvider implements MusicProvider {

    public static final String ID = "youtube-music";
    public static final String MUSIC_SERVICE = "music.youtube.com";
    private static final String ORIGIN_PREFIX = "https://music.youtube.com/watch?v=";

    private final YouTubeMusicApi api;

    public YouTubeMusicProvider(Context context) {
        this.api = new YouTubeMusicApi(context);
    }

    /** Exposed for app-level settings (preferred InnerTube client) and diagnostics. */
    public YouTubeMusicApi api() {
        return api;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "YouTube Music";
    }

    @Override
    public String tagline() {
        return "Streamed from YouTube Music · public access only · no sign-in";
    }

    @Override
    public List<MediaItem> search(String query, String filter) throws IOException {
        return api.search(query, modeFor(filter));
    }

    @Override
    public List<MediaItem> quickPicks() throws IOException {
        // "official audio" keeps YouTube results to proper tracks rather than
        // music videos, interviews and fan uploads.
        return api.search("popular music official audio", YouTubeMusicApi.MODE_SONGS);
    }

    @Override
    public List<MediaItem> browse(MediaItem item) throws IOException {
        if (item == null) {
            throw new IOException("Nothing to browse.");
        }
        return item.isArtist() ? api.browseArtist(item) : api.browseCollection(item);
    }

    @Override
    public StreamSource resolve(MediaItem track) throws IOException {
        if (track == null || track.id.length() == 0) {
            throw new IOException("This track has no video id.");
        }
        return api.resolve(track.id);
    }

    @Override
    public String musicService() {
        return MUSIC_SERVICE;
    }

    @Override
    public String originUrl(MediaItem track) {
        return track != null && track.id.length() > 0 ? ORIGIN_PREFIX + track.id : "";
    }

    /** Maps the framework's standard filters onto this API's search modes. */
    public static String modeFor(String filter) {
        if (FILTER_ARTISTS.equals(filter)) {
            return YouTubeMusicApi.MODE_ARTISTS;
        }
        if (FILTER_ALBUMS.equals(filter)) {
            return YouTubeMusicApi.MODE_ALBUMS;
        }
        if (FILTER_PLAYLISTS.equals(filter)) {
            return YouTubeMusicApi.MODE_PLAYLISTS;
        }
        return YouTubeMusicApi.MODE_SONGS;
    }

    /** Placeholder track for a watch link; the resolver fills in the real metadata. */
    public static MediaItem trackForVideoId(String videoId) {
        MediaItem item = new MediaItem();
        item.id = videoId;
        item.kind = MediaItem.KIND_TRACK;
        item.title = "Opening link…";
        item.thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        return item;
    }

    /** Placeholder playlist for a {@code list=} link; ids are kept in browse ("VL…") form. */
    public static MediaItem playlistForId(String playlistId) {
        MediaItem item = new MediaItem();
        item.id = playlistId.startsWith("VL") ? playlistId : "VL" + playlistId;
        item.kind = MediaItem.KIND_PLAYLIST;
        item.title = "YouTube playlist";
        return item;
    }
}
