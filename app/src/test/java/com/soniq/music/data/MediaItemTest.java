package com.soniq.music.data;

import org.junit.Assert;
import org.junit.Test;

public final class MediaItemTest {

    @Test
    public void artistIdentityAndBrowsability() {
        MediaItem artist = new MediaItem();
        artist.title = "Some Artist";
        artist.collectionType = MediaItem.KIND_ARTIST;
        artist.browseId = "UCabcdefghijklmnopqrstuv";
        Assert.assertTrue(artist.isArtist());
        Assert.assertTrue(artist.isBrowsable());
        Assert.assertFalse(artist.isPlayable());
        Assert.assertEquals("artist:UCabcdefghijklmnopqrstuv", artist.identity());
    }

    @Test
    public void albumIdentityUsesBrowseId() {
        MediaItem album = new MediaItem();
        album.title = "Album";
        album.collectionType = MediaItem.KIND_ALBUM;
        album.browseId = "MPREb_12345";
        Assert.assertTrue(album.isCollection());
        Assert.assertEquals("album:MPREb_12345", album.identity());
    }

    @Test
    public void playlistIdentityNormalisesVLId() {
        MediaItem playlist = new MediaItem();
        playlist.title = "Playlist";
        playlist.collectionType = MediaItem.KIND_PLAYLIST;
        playlist.browseId = "VLabc123";
        Assert.assertEquals("playlist:abc123", playlist.identity());
    }

    @Test
    public void songIsPlayableAndIdentifiedByVideoId() {
        MediaItem song = new MediaItem();
        song.title = "Song";
        song.videoId = "abcdefghijk";
        song.isMusicTrack = true;
        Assert.assertTrue(song.isPlayable());
        Assert.assertFalse(song.isBrowsable());
        Assert.assertEquals("video:abcdefghijk", song.identity());
    }

    @Test
    public void offlineItemIsPlayableWithoutVideoId() {
        MediaItem offline = new MediaItem();
        offline.title = "Offline";
        offline.localUri = "content://media/external/audio/media/1";
        Assert.assertTrue(offline.isPlayable());
        Assert.assertTrue(offline.isOffline());
        Assert.assertEquals("local:content://media/external/audio/media/1", offline.identity());
    }

    @Test
    public void subtitleCombinesAuthorAlbumAndDuration() {
        MediaItem song = new MediaItem();
        song.author = "Artist";
        song.album = "Album";
        song.durationText = "3:21";
        Assert.assertEquals("Artist  •  Album  •  3:21", song.subtitle());
    }
}
