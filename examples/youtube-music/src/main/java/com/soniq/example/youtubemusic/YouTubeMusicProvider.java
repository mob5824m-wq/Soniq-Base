package com.soniq.example.youtubemusic;

import android.content.Context;

import com.soniq.base.model.MediaItem;
import com.soniq.base.model.StreamSource;
import com.soniq.base.provider.MusicProvider;

import java.io.IOException;
import java.util.List;

/**
 * Adapts two catalogues to the framework's {@link MusicProvider} SPI: the
 * YouTube Music InnerTube client ({@link YouTubeMusicApi}) for songs, artists,
 * albums and playlists, and the public podcast directory
 * ({@link PublicPodcastsApi}) for podcast shows and episodes.
 *
 * <p>This is the only class the framework sees; everything service-specific
 * stays behind it.
 */
public final class YouTubeMusicProvider implements MusicProvider {

    public static final String ID = "youtube-music";
    public static final String MUSIC_SERVICE = "music.youtube.com";
    private static final String ORIGIN_PREFIX = "https://music.youtube.com/watch?v=";
    /** Shown on the Podcasts tab: the catalogue behind {@link PublicPodcastsApi}. */
    private static final String PODCAST_SOURCE = "Apple Podcasts";

    private final YouTubeMusicApi api;
    private final PublicPodcastsApi podcastApi;

    public YouTubeMusicProvider(Context context) {
        this.api = new YouTubeMusicApi(context);
        this.podcastApi = new PublicPodcastsApi();
    }

    /** Exposed for app-level settings (preferred InnerTube client) and diagnostics. */
    public YouTubeMusicApi api() {
        return api;
    }

    /** Exposed for diagnostics: the public podcast directory adapter. */
    public PublicPodcastsApi podcastsApi() {
        return podcastApi;
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
        return "Songs from YouTube Music · podcasts from Apple's public directory · no sign-in";
    }

    @Override
    public List<MediaItem> search(String query, String filter) throws IOException {
        if (FILTER_PODCASTS.equals(filter)) {
            // Podcasts are searched in the public Apple directory, not on
            // YouTube; a songs search here would show music under a
            // "Podcasts" chip. An empty query falls back to a discovery term.
            return podcastApi.searchShows(query, 25);
        }
        return api.search(query, modeFor(filter));
    }

    @Override
    public List<MediaItem> quickPicks() throws IOException {
        // "official audio" keeps YouTube results to proper tracks rather than
        // music videos, interviews and fan uploads.
        return api.search("popular music official audio", YouTubeMusicApi.MODE_SONGS);
    }

    @Override
    public List<MediaItem> podcasts() throws IOException {
        // Apple's public top-podcasts chart, so the Podcasts tab has a real
        // starting point; featuredShows() falls back to a keyword search.
        return podcastApi.featuredShows();
    }

    @Override
    public String podcastSourceName() {
        return PODCAST_SOURCE;
    }

    @Override
    public List<MediaItem> browse(MediaItem item) throws IOException {
        if (item == null) {
            throw new IOException("Nothing to browse.");
        }
        if (item.isPodcast()) {
            // The show's public RSS feed is the episode list.
            return podcastApi.episodes(item);
        }
        return item.isArtist() ? api.browseArtist(item) : api.browseCollection(item);
    }

    @Override
    public StreamSource resolve(MediaItem track) throws IOException {
        if (track != null && track.isEpisode()) {
            // An episode streams straight from the publisher's enclosure URL.
            return podcastApi.resolve(track);
        }
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
        if (track == null || track.isEpisode()) {
            // An episode is not a YouTube video, so there is no watch URL.
            return "";
        }
        return track.id.length() > 0 ? ORIGIN_PREFIX + track.id : "";
    }

    /**
     * YouTube Music returns lyrics separately (and only for some tracks), so the
     * example does not claim to know anything here: the framework searches and
     * reports honestly. A real provider that knows a track is an instrumental
     * should return {@link com.soniq.base.model.LyricsState#INSTRUMENTAL}.
     */
    @Override
    public String lyricsState(MediaItem track) {
        return com.soniq.base.model.LyricsState.UNKNOWN;
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
