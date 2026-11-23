package com.soniq.base.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.soniq.base.R;
import com.soniq.base.data.ImageLoader;
import com.soniq.base.data.Library;
import com.soniq.base.model.MediaItem;
import com.soniq.base.model.StreamSource;
import com.soniq.base.SoniqApp;
import com.soniq.base.data.ScrobbleQueue;
import com.soniq.base.provider.MusicProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Foreground music playback service.
 *
 * <p>Replaces the Kobo suite's {@code Arc7AudioService}. Modern Android needs a
 * declared {@code mediaPlayback} foreground service type, a notification channel,
 * immutable PendingIntents, an {@link AudioFocusRequest} and a
 * {@link MediaSession}; the old service predates all four.
 */
public final class PlaybackService extends Service {

    public static final String ACTION_PLAY = "com.soniq.base.action.PLAY";
    public static final String ACTION_PAUSE = "com.soniq.base.action.PAUSE";
    public static final String ACTION_TOGGLE = "com.soniq.base.action.TOGGLE";
    public static final String ACTION_NEXT = "com.soniq.base.action.NEXT";
    public static final String ACTION_PREVIOUS = "com.soniq.base.action.PREVIOUS";
    public static final String ACTION_STOP = "com.soniq.base.action.STOP";
    public static final String ACTION_MEDIA_BUTTON = "com.soniq.base.action.MEDIA_BUTTON";

    public static final int NOTIFICATION_ID = 4101;
    private static final String CHANNEL_ID = "soniq_playback";
    private static final String WAKE_LOCK_TAG = "SoniqMusic:Playback";
    private static final String WIFI_LOCK_TAG = "SoniqMusic:Stream";

    /** UI callbacks, always delivered on the main thread. */
    public interface Listener {
        void onPlaybackChanged();
    }

    public final class LocalBinder extends Binder {
        public PlaybackService service() {
            return PlaybackService.this;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final PlaybackQueue queue = new PlaybackQueue();
    private final List<Listener> listeners = new ArrayList<>();
    private final LocalBinder binder = new LocalBinder();

    /** The app's music source; resolves tracks to playable streams off the main thread. */
    private MusicProvider provider;
    private Library library;

    private MediaPlayer player;
    private MediaSession session;
    private NotificationManager notificationManager;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private AudioFocusRequest focusRequest;
    private AudioManager.OnAudioFocusChangeListener focusListener;

    private boolean prepared;
    private boolean playWhenReady = true;
    private boolean resolving;
    private boolean foreground;
    private boolean focusHeld;
    private boolean pausedByFocusLoss;
    private int generation;
    private String status = "";

    private Bitmap artwork;
    private String artworkKey = "";

    private Equalizer equalizer;
    private int eqPreset;
    private String[] eqPresetNames;

    private int sleepTimerMinutes;
    private long sleepEndAt;
    private Runnable sleepRunnable;

    private ScrobbleQueue scrobbles;
    private boolean tickerRunning;
    private long lastTickRealtime;
    /** Smooths MediaPlayer's position (stuck 0, stale readings, in-flight seeks). */
    private final PlaybackPosition position = new PlaybackPosition();

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    };

    /* ================================================================ lifecycle */

    @Override
    public void onCreate() {
        super.onCreate();
        provider = SoniqApp.providerOf(this);
        library = new Library(this);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        createLocks();
        createMediaSession();
        IntentFilter noisyFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, noisyFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, noisyFilter);
        }
        scrobbles = ScrobbleQueue.get(this);
        scrobbles.flush();
        updateNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_MEDIA_BUTTON.equals(action)) {
            handleMediaButton(intent);
        } else if (ACTION_PLAY.equals(action)) {
            play();
        } else if (ACTION_PAUSE.equals(action)) {
            pause();
        } else if (ACTION_TOGGLE.equals(action)) {
            toggle();
        } else if (ACTION_NEXT.equals(action)) {
            next();
        } else if (ACTION_PREVIOUS.equals(action)) {
            previous();
        } else if (ACTION_STOP.equals(action)) {
            stop();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (!isPlaying() && !resolving) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        generation++;
        releasePlayer();
        releaseLocks();
        abandonFocus();
        if (session != null) {
            session.release();
            session = null;
        }
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Exception ignored) {
        }
        cancelSleepTimer();
        if (sleepRunnable != null) {
            main.removeCallbacks(sleepRunnable);
        }
        if (equalizer != null) {
            try {
                equalizer.release();
            } catch (Exception ignored) {
            }
            equalizer = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.soniq_notification_channel_playback),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.soniq_notification_channel_playback_description));
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(channel);
    }

    private void createLocks() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            wakeLock.setReferenceCounted(false);
        }
        WifiManager wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(WIFI_SERVICE);
        if (wifiManager != null) {
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wifiManager.createWifiLock(mode, WIFI_LOCK_TAG);
            wifiLock.setReferenceCounted(false);
        }
    }

    private void createMediaSession() {
        session = new MediaSession(this, "SoniqMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }

            @Override
            public void onStop() {
                stop();
            }
        });
        session.setActive(true);
    }

    private void handleMediaButton(Intent intent) {
        android.view.KeyEvent event = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent.class)
                : intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null || event.getAction() != android.view.KeyEvent.ACTION_DOWN) {
            return;
        }
        ensureForeground();
        switch (event.getKeyCode()) {
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                toggle();
                break;
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
                play();
                break;
            case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                pause();
                break;
            case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
                next();
                break;
            case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                previous();
                break;
            case android.view.KeyEvent.KEYCODE_MEDIA_STOP:
                stop();
                break;
            default:
                break;
        }
    }

    /* =================================================================== queue */

    public void play(List<MediaItem> items, int startIndex) {
        if (items == null || items.isEmpty()) {
            return;
        }
        queue.setItems(items);
        queue.playAt(Math.max(0, Math.min(startIndex, items.size() - 1)));
        playWhenReady = true;
        prepareCurrent();
    }

    public void playSingle(MediaItem item) {
        if (item == null) {
            return;
        }
        List<MediaItem> single = new ArrayList<>();
        single.add(item);
        play(single, 0);
    }

    public void addToQueue(MediaItem item) {
        if (item == null) {
            return;
        }
        boolean wasEmpty = queue.isEmpty();
        queue.add(item);
        if (wasEmpty) {
            playWhenReady = true;
            prepareCurrent();
        }
        notifyChanged();
    }

    public void addAllToQueue(List<MediaItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        boolean wasEmpty = queue.isEmpty();
        queue.addAll(items);
        if (wasEmpty) {
            playWhenReady = true;
            prepareCurrent();
        }
        notifyChanged();
    }

    public void playQueueItem(int itemIndex) {
        MediaItem item = queue.at(itemIndex);
        if (item == null) {
            return;
        }
        queue.playAt(itemIndex);
        playWhenReady = true;
        prepareCurrent();
    }

    public void playQueueItem(MediaItem item) {
        int index = queue.indexOf(item);
        if (index >= 0) {
            playQueueItem(index);
        }
    }

    public int queueIndexOf(MediaItem item) {
        return queue.indexOf(item);
    }

    public void removeQueueItem(int itemIndex) {
        int current = queue.currentIndex();
        queue.remove(itemIndex);
        if (itemIndex == current) {
            if (queue.isEmpty()) {
                stop();
                return;
            }
            playWhenReady = true;
            prepareCurrent();
        }
        notifyChanged();
    }

    public List<MediaItem> queueItems() {
        return queue.items();
    }

    public List<MediaItem> orderedQueueItems() {
        return queue.ordered();
    }

    public int queueIndex() {
        return queue.currentIndex();
    }

    public int queueSize() {
        return queue.size();
    }

    /* ================================================================ controls */

    public void toggle() {
        if (isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    public void play() {
        playWhenReady = true;
        pausedByFocusLoss = false;
        if (prepared && player != null) {
            if (requestFocus()) {
                startPlaybackNow(player);
            } else {
                status = "Another app owns audio focus";
                notifyChanged();
                updateNotification();
            }
            return;
        }
        if (!resolving) {
            MediaItem item = queue.current();
            if (item != null) {
                prepareCurrent();
            }
        }
    }

    public void pause() {
        pausedByFocusLoss = false;
        playWhenReady = false;
        pauseInternal();
    }

    public void stop() {
        generation++;
        resolving = false;
        playWhenReady = false;
        pausedByFocusLoss = false;
        status = "";
        releasePlayer();
        queue.clear();
        abandonFocus();
        releaseLocks();
        updatePlaybackState();
        notifyChanged();
        if (foreground) {
            foreground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        notificationManager.cancel(NOTIFICATION_ID);
        stopSelf();
    }

    public void next() {
        MediaItem item = queue.next(true);
        if (item == null) {
            status = "End of queue";
            updatePlaybackState();
            updateNotification();
            notifyChanged();
            return;
        }
        playWhenReady = true;
        prepareCurrent();
    }

    public void previous() {
        if (player != null && prepared && getPosition() > 3000) {
            seekTo(0);
            return;
        }
        MediaItem item = queue.previous(true);
        if (item == null) {
            return;
        }
        playWhenReady = true;
        prepareCurrent();
    }

    public void seekTo(int positionMs) {
        if (player == null || !prepared) {
            return;
        }
        int target = Math.max(0, positionMs);
        try {
            player.seekTo(target);
            position.seekTo(target, SystemClock.elapsedRealtime());
            updatePlaybackState();
            notifyChanged();
        } catch (Exception ignored) {
        }
    }

    public void setShuffle(boolean enabled) {
        queue.setShuffle(enabled);
        notifyChanged();
    }

    public boolean isShuffle() {
        return queue.isShuffle();
    }

    public void setRepeat(int mode) {
        queue.setRepeat(mode);
        notifyChanged();
    }

    public int repeatMode() {
        return queue.repeatMode();
    }

    public int cycleRepeat() {
        int mode = queue.cycleRepeat();
        notifyChanged();
        return mode;
    }

    public void setSleepTimer(int minutes) {
        cancelSleepTimer();
        sleepTimerMinutes = Math.max(0, minutes);
        sleepEndAt = 0L;
        if (sleepTimerMinutes == 0) {
            notifyChanged();
            return;
        }
        sleepEndAt = System.currentTimeMillis() + sleepTimerMinutes * 60000L;
        sleepRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying()) {
                    pause();
                }
                status = "Sleep timer ended";
                sleepTimerMinutes = 0;
                sleepEndAt = 0L;
                updateNotification();
                notifyChanged();
            }
        };
        main.postDelayed(sleepRunnable, sleepTimerMinutes * 60000L);
        notifyChanged();
    }

    private void cancelSleepTimer() {
        if (sleepRunnable != null) {
            main.removeCallbacks(sleepRunnable);
            sleepRunnable = null;
        }
    }

    public int remainingSleepSeconds() {
        if (sleepEndAt <= 0L) {
            return 0;
        }
        return Math.max(0, (int) ((sleepEndAt - System.currentTimeMillis()) / 1000L));
    }

    public int sleepTimerMinutes() {
        return sleepTimerMinutes;
    }

    /* ================================================================= getters */

    public MediaItem current() {
        return queue.current();
    }

    public boolean isPlaying() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isPreparing() {
        return resolving;
    }

    public boolean isPrepared() {
        return prepared;
    }

    public String getStatus() {
        return status;
    }

    /**
     * Position for the UI, MediaSession and listen accounting. Uses the player's
     * reading when it is plausible and a wall-clock estimate otherwise, so the
     * seek bar cannot freeze at 0:00 or jump back while a seek is in flight.
     */
    public int getPosition() {
        long now = SystemClock.elapsedRealtime();
        position.setPlaying(isPlaying(), now);
        return position.update(nativePosition(), now, getDuration());
    }

    /** Raw player position, or -1 when no player is prepared or the call fails. */
    private int nativePosition() {
        try {
            return player != null && prepared ? Math.max(0, player.getCurrentPosition()) : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    public int getDuration() {
        try {
            return player != null && prepared ? player.getDuration() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    public boolean isSeekable() {
        return prepared && !resolving;
    }

    public Bitmap artwork() {
        return artwork;
    }

    /* ============================================================== equalizer */

    public String[] equalizerPresets() {
        if (eqPresetNames != null) {
            return eqPresetNames;
        }
        try {
            Equalizer probe = new Equalizer(0, 0);
            short count = probe.getNumberOfPresets();
            String[] names = new String[count];
            for (short i = 0; i < count; i++) {
                names[i] = probe.getPresetName(i);
            }
            probe.release();
            eqPresetNames = names;
        } catch (Exception | OutOfMemoryError error) {
            eqPresetNames = new String[]{"Flat"};
        }
        return eqPresetNames;
    }

    public int equalizerPreset() {
        return eqPreset;
    }

    public void setEqualizerPreset(int index) {
        String[] names = equalizerPresets();
        if (names.length == 0) {
            return;
        }
        eqPreset = Math.max(0, Math.min(index, names.length - 1));
        applyEqualizerPreset();
    }

    private void applyEqualizerPreset() {
        if (equalizer == null) {
            attachEqualizer();
        }
        if (equalizer == null) {
            return;
        }
        try {
            short count = equalizer.getNumberOfPresets();
            if (eqPreset >= 0 && eqPreset < count) {
                equalizer.usePreset((short) eqPreset);
                equalizer.setEnabled(true);
            } else {
                equalizer.setEnabled(false);
            }
        } catch (Exception ignored) {
        }
    }

    private void attachEqualizer() {
        if (player == null || !prepared) {
            return;
        }
        int sessionId;
        try {
            sessionId = player.getAudioSessionId();
        } catch (Exception ignored) {
            return;
        }
        if (sessionId == 0) {
            return;
        }
        if (equalizer != null) {
            try {
                equalizer.release();
            } catch (Exception ignored) {
            }
            equalizer = null;
        }
        try {
            equalizer = new Equalizer(0, sessionId);
            applyEqualizerPreset();
        } catch (Exception | OutOfMemoryError ignored) {
            equalizer = null;
        }
    }

    /* ================================================================== player */

    private void prepareCurrent() {
        generation++;
        final int request = generation;
        releasePlayer();
        final MediaItem item = queue.current();
        if (item == null) {
            status = "";
            updatePlaybackState();
            updateNotification();
            notifyChanged();
            return;
        }
        resolving = true;
        prepared = false;
        status = "Loading " + item.title;
        ensureForeground();
        updatePlaybackState();
        updateNotification();
        updateMetadata();
        notifyChanged();
        loadArtwork(item);
        library.addRecent(item);
        scrobbles.trackChanged(item);

        if (item.localUri.length() > 0) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (request == generation) {
                        startLocal(item, request);
                    }
                }
            });
            return;
        }

        final MediaItem toResolve = item;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final StreamSource track = provider.resolve(toResolve);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (request == generation) {
                                startStream(applyResolvedMetadata(item, track), track, request);
                            }
                        }
                    });
                } catch (final Exception error) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (request == generation) {
                                failCurrent(error);
                            }
                        }
                    });
                }
            }
        });
    }

    private void startLocal(MediaItem item, int request) {
        releasePlayer();
        MediaPlayer mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setAudioAttributes(audioAttributes());
            mediaPlayer.setDataSource(this, Uri.parse(item.localUri));
            attachPlayerListeners(mediaPlayer, request);
            player = mediaPlayer;
            mediaPlayer.prepareAsync();
        } catch (Exception error) {
            player = null;
            failCurrent(error);
        }
    }

    /**
     * Folds the metadata the provider corrected while resolving (title, author,
     * album, duration, artwork) into the queued item, so the notification,
     * player and scrobbles describe the stream that is actually playing.
     * Returns the item to use going forward.
     */
    private MediaItem applyResolvedMetadata(MediaItem item, StreamSource track) {
        if (item == null || track == null) {
            return item;
        }
        MediaItem fixed = item.copy();
        boolean changed = false;
        if (track.title.length() > 0 && !track.title.equals(item.title)) {
            fixed.title = track.title;
            changed = true;
        }
        if (track.author.length() > 0 && !track.author.equals(item.author)) {
            fixed.author = track.author;
            changed = true;
        }
        if (track.album.length() > 0 && !track.album.equals(item.album)) {
            fixed.album = track.album;
            changed = true;
        }
        if (track.durationSec > 0L && track.durationSec != item.durationSec) {
            fixed.setDurationSec(track.durationSec);
            changed = true;
        }
        if (track.artworkUrl.length() > 0 && !track.artworkUrl.equals(item.thumbnailUrl)) {
            fixed.thumbnailUrl = track.artworkUrl;
            changed = true;
        }
        if (!changed || !queue.replaceCurrent(fixed)) {
            return item;
        }
        updateMetadata();
        loadArtwork(fixed);
        notifyChanged();
        return fixed;
    }

    private void startStream(MediaItem item, StreamSource track, int request) {
        if (track == null || !track.hasStream()) {
            failCurrent(new IOException("No playable audio stream was returned."));
            return;
        }
        releasePlayer();
        MediaPlayer mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setAudioAttributes(audioAttributes());
            if (track.hls) {
                mediaPlayer.setDataSource(track.url);
            } else {
                mediaPlayer.setDataSource(this, Uri.parse(track.url), track.headers);
            }
            attachPlayerListeners(mediaPlayer, request);
            player = mediaPlayer;
            mediaPlayer.prepareAsync();
        } catch (Exception error) {
            player = null;
            failCurrent(error);
        }
    }

    private AudioAttributes audioAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private void attachPlayerListeners(final MediaPlayer mediaPlayer, final int request) {
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (player != mediaPlayer || request != generation) {
                    return;
                }
                prepared = true;
                resolving = false;
                status = "";
                attachEqualizer();
                if (playWhenReady && requestFocus()) {
                    startPlaybackNow(mediaPlayer);
                } else {
                    updatePlaybackState();
                    updateMetadata();
                    updateNotification();
                    notifyChanged();
                }
            }
        });
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                if (player == mediaPlayer) {
                    onTrackEnded();
                }
            }
        });
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (player == mediaPlayer) {
                    status = "Playback error (" + what + "/" + extra + ")";
                    updatePlaybackState();
                    updateNotification();
                    notifyChanged();
                    final int failedGeneration = generation;
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (failedGeneration != generation) {
                                return;
                            }
                            MediaItem nextItem = queue.next(true);
                            if (nextItem != null) {
                                prepareCurrent();
                            }
                        }
                    }, 1200L);
                }
                return true;
            }
        });
        mediaPlayer.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
            @Override
            public void onBufferingUpdate(MediaPlayer mp, int percent) {
                // The framework owns buffering; nothing to surface in the UI yet.
            }
        });
    }

    private void startPlaybackNow(MediaPlayer mediaPlayer) {
        if (mediaPlayer == null) {
            return;
        }
        try {
            if (!mediaPlayer.isPlaying()) {
                mediaPlayer.start();
            }
            long now = SystemClock.elapsedRealtime();
            int nativePosition = nativePosition();
            position.anchor(nativePosition > 0 ? nativePosition
                    : position.update(-1, now, getDuration()), now);
            position.setPlaying(true, now);
            acquireLocks();
            scrobbles.playbackStarted(queue.current());
            startTicker();
            updatePlaybackState();
            updateMetadata();
            updateNotification();
            notifyChanged();
        } catch (Exception error) {
            failCurrent(error);
        }
    }

    /* ============================================================== scrobbling */

    /** Feeds real listening time to the scrobble queue about once per second. */
    private final Runnable scrobbleTicker = new Runnable() {
        @Override
        public void run() {
            if (!tickerRunning) {
                return;
            }
            if (!isPlaying()) {
                tickerRunning = false;
                lastTickRealtime = 0L;
                return;
            }
            long now = SystemClock.elapsedRealtime();
            long delta = lastTickRealtime > 0L ? now - lastTickRealtime : 0L;
            lastTickRealtime = now;
            scrobbles.playbackTick(queue.current(), delta, getDuration());
            main.postDelayed(this, 1000L);
        }
    };

    private void startTicker() {
        if (tickerRunning) {
            return;
        }
        tickerRunning = true;
        lastTickRealtime = SystemClock.elapsedRealtime();
        main.postDelayed(scrobbleTicker, 1000L);
    }

    private void stopTicker() {
        tickerRunning = false;
        lastTickRealtime = 0L;
        main.removeCallbacks(scrobbleTicker);
    }

    private void pauseInternal() {
        stopTicker();
        position.setPlaying(false, SystemClock.elapsedRealtime());
        if (player != null && prepared) {
            try {
                player.pause();
            } catch (Exception ignored) {
            }
        }
        releaseLocks();
        updatePlaybackState();
        updateNotification();
        notifyChanged();
    }

    private void onTrackEnded() {
        if (queue.repeatMode() == PlaybackQueue.REPEAT_ONE) {
            try {
                player.seekTo(0);
                player.start();
                position.seekTo(0, SystemClock.elapsedRealtime());
                position.setPlaying(true, SystemClock.elapsedRealtime());
                scrobbles.trackChanged(queue.current());
                scrobbles.playbackStarted(queue.current());
                return;
            } catch (Exception ignored) {
            }
        }
        MediaItem nextItem = queue.next(false);
        if (nextItem == null) {
            playWhenReady = false;
            status = "Queue finished";
            releaseLocks();
            updatePlaybackState();
            updateNotification();
            notifyChanged();
            return;
        }
        prepareCurrent();
    }

    private void failCurrent(Exception error) {
        resolving = false;
        prepared = false;
        String message = error == null ? "Unknown error" : error.getMessage();
        if (message == null) {
            message = error.getClass().getSimpleName();
        }
        status = message;
        updatePlaybackState();
        updateNotification();
        notifyChanged();
    }

    private void releasePlayer() {
        stopTicker();
        position.reset();
        if (equalizer != null) {
            try {
                equalizer.release();
            } catch (Exception ignored) {
            }
            equalizer = null;
        }
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        prepared = false;
    }

    /* ==================================================================== focus */

    private boolean requestFocus() {
        if (focusHeld) {
            return true;
        }
        if (audioManager == null) {
            return true;
        }
        if (focusRequest == null) {
            focusListener = new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    handleFocusChange(change);
                }
            };
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes())
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .setWillPauseWhenDucked(false)
                    .build();
        }
        int result = audioManager.requestAudioFocus(focusRequest);
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focusHeld;
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            try {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } catch (Exception ignored) {
            }
        }
        focusHeld = false;
    }

    private void handleFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                focusHeld = true;
                setPlayerVolume(1f);
                if (pausedByFocusLoss && playWhenReady && player != null && prepared) {
                    pausedByFocusLoss = false;
                    startPlaybackNow(player);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                pausedByFocusLoss = false;
                pause();
                abandonFocus();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                pausedByFocusLoss = true;
                stopTicker();
                position.setPlaying(false, SystemClock.elapsedRealtime());
                if (player != null && prepared) {
                    try {
                        player.pause();
                    } catch (Exception ignored) {
                    }
                }
                releaseLocks();
                updatePlaybackState();
                updateNotification();
                notifyChanged();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                setPlayerVolume(0.2f);
                break;
            default:
                break;
        }
    }

    private void setPlayerVolume(float volume) {
        if (player != null && prepared) {
            try {
                player.setVolume(volume, volume);
            } catch (Exception ignored) {
            }
        }
    }

    private void acquireLocks() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                wakeLock.acquire();
            } catch (Exception ignored) {
            }
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            try {
                wifiLock.acquire();
            } catch (Exception ignored) {
            }
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            try {
                wifiLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    /* ============================================================== media session */

    private void updatePlaybackState() {
        if (session == null) {
            return;
        }
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP;
        int state;
        if (isPlaying()) {
            state = PlaybackState.STATE_PLAYING;
        } else if (resolving) {
            state = PlaybackState.STATE_BUFFERING;
        } else {
            state = PlaybackState.STATE_PAUSED;
        }
        float speed = isPlaying() ? 1f : 0f;
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(state, getPosition(), speed)
                .setActions(actions)
                .build());
    }

    private void updateMetadata() {
        if (session == null) {
            return;
        }
        MediaItem item = queue.current();
        if (item == null) {
            session.setMetadata(null);
            return;
        }
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, item.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, item.author)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item.author)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, item.album);
        int duration = getDuration();
        if (duration > 0) {
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
        } else if (item.durationSec > 0) {
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION, item.durationSec * 1000L);
        }
        if (artwork != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        }
        session.setMetadata(builder.build());
    }

    private void loadArtwork(MediaItem item) {
        final String key = item == null ? "" : item.bestThumb();
        if (key.equals(artworkKey)) {
            return;
        }
        artworkKey = key;
        artwork = null;
        if (key.length() == 0) {
            updateMetadata();
            updateNotification();
            return;
        }
        Bitmap cached = ImageLoader.cached(key);
        if (cached != null) {
            artwork = cached;
            updateMetadata();
            updateNotification();
            notifyChanged();
            return;
        }
        ImageLoader.load(key, new ImageLoader.Callback() {
            @Override
            public void onBitmap(Bitmap bitmap) {
                if (!key.equals(artworkKey)) {
                    return;
                }
                artwork = bitmap;
                updateMetadata();
                updateNotification();
                notifyChanged();
            }
        });
    }

    /* ============================================================ notification */

    private void ensureForeground() {
        if (foreground) {
            return;
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foreground = true;
    }

    private void updateNotification() {
        if (notificationManager == null) {
            return;
        }
        Notification notification = buildNotification();
        if (foreground) {
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        MediaItem item = queue.current();
        String title = item == null ? appLabel() : item.title;
        String subtitle = item == null || item.author.length() == 0
                ? getString(R.string.soniq_notification_default_subtitle) : item.author;

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.soniq_ic_music_note)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setOngoing(isPlaying())
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setContentIntent(contentIntent())
                .setDeleteIntent(servicePendingIntent(ACTION_STOP))
                .addAction(action(R.drawable.soniq_ic_prev, getString(R.string.soniq_action_previous),
                        servicePendingIntent(ACTION_PREVIOUS)))
                .addAction(action(isPlaying() ? R.drawable.soniq_ic_pause : R.drawable.soniq_ic_play,
                        isPlaying() ? getString(R.string.soniq_action_pause)
                                : getString(R.string.soniq_action_play),
                        servicePendingIntent(ACTION_TOGGLE)))
                .addAction(action(R.drawable.soniq_ic_next, getString(R.string.soniq_action_next),
                        servicePendingIntent(ACTION_NEXT)));
        if (status.length() > 0) {
            builder.setSubText(status);
        }
        if (artwork != null) {
            builder.setLargeIcon(artwork);
        }
        if (session != null) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return builder.build();
    }

    /** The host app's label, so the notification says "Soniq Music", not "Soniq Base". */
    private String appLabel() {
        try {
            CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (RuntimeException ignored) {
        }
        return getString(R.string.soniq_app_name);
    }

    private Notification.Action action(int icon, CharSequence title, PendingIntent intent) {
        return new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, icon), title, intent)
                .build();
    }

    private PendingIntent contentIntent() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent == null) {
            intent = new Intent();
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent servicePendingIntent(String action) {
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(action);
        int requestCode = action == null ? 0 : action.hashCode();
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /* ================================================================ listeners */

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        for (Listener listener : new ArrayList<>(listeners)) {
            try {
                listener.onPlaybackChanged();
            } catch (Exception ignored) {
            }
        }
    }
}
