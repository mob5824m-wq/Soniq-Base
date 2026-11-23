package com.soniq.music.ui;

import android.content.Context;
import android.os.Handler;

import com.soniq.music.data.Downloads;
import com.soniq.music.data.Library;
import com.soniq.music.data.MediaItem;
import com.soniq.music.data.Settings;
import com.soniq.music.data.YouTubeMusicApi;
import com.soniq.music.playback.PlaybackService;

import java.util.List;
import java.util.concurrent.ExecutorService;

/** Everything a screen needs from the activity, without owning it. */
public interface Host {

    Context context();

    PlaybackService service();

    Library library();

    Settings settings();

    Downloads downloads();

    YouTubeMusicApi api();

    Handler main();

    ExecutorService io();

    void play(List<MediaItem> items, int index);

    void playSingle(MediaItem item);

    void addToQueue(MediaItem item);

    void quickPlay();

    void openCollection(MediaItem item);

    void openArtist(MediaItem item);

    void openPlayer();

    void openLibrary();

    void openSearch(String query);

    void showQueue();

    void showTrackMenu(MediaItem item);

    void toggleLike(MediaItem item);

    boolean isLiked(MediaItem item);

    void download(MediaItem item);

    void addToPlaylist(MediaItem item);

    void createPlaylist();

    void startRadio(MediaItem item);

    void showToast(String message);

    void requestNotificationPermission();

    void refreshCurrentScreen();
}
