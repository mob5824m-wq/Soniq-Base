package com.soniq.music.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects finished listens, submits them to ListenBrainz and keeps an
 * on-disk queue so listens made offline are sent later.
 *
 * <p>Play-time accounting is driven by the playback service on the main
 * thread ({@link #trackChanged}, {@link #playbackStarted}, {@link #playbackTick},
 * {@link #playbackPaused}); all network work happens on one background thread,
 * spaced to respect ListenBrainz' one-call-per-second limit.
 */
public final class ScrobbleQueue {

    private static final String PREFS = "soniq_scrobbles";
    private static final String KEY_PENDING = "pending";
    /** Bound on the offline backlog; oldest entries are dropped beyond this. */
    private static final int MAX_PENDING = 1000;
    /** Listens per import request (server allows 1000; keep requests small). */
    private static final int BATCH_SIZE = 50;
    private static final long REQUEST_SPACING_MS = 1100L;
    private static final int MAX_RATE_LIMIT_RETRIES = 3;

    private static ScrobbleQueue instance;

    public static synchronized ScrobbleQueue get(Context context) {
        if (instance == null) {
            instance = new ScrobbleQueue(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final Settings settings;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private final ArrayList<Scrobble> pending = new ArrayList<>();
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private volatile String lastError = "";
    private ListenBrainzApi api;

    // Play-time accounting; only touched on the main thread.
    private String currentKey = "";
    private long playedMs;
    private long startedAtMs;
    private boolean submitted;

    private ScrobbleQueue(Context context) {
        this.context = context;
        this.settings = new Settings(context);
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (lock) {
            pending.addAll(Scrobble.fromJsonArray(
                    parse(prefs.getString(KEY_PENDING, "[]"))));
        }
    }

    private static JSONArray parse(String text) {
        try {
            return new JSONArray(text);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    /* ======================================================== playback hooks */

    /**
     * A track is about to load: reset the accounting even when it is the same
     * track again (re-playing a song from the queue is a new listen).
     */
    public void trackChanged(MediaItem item) {
        currentKey = item == null ? "" : item.identity();
        playedMs = 0L;
        submitted = false;
        startedAtMs = System.currentTimeMillis();
    }

    /**
     * Playback actually started for {@code item}: announce playing_now. Called
     * for fresh starts and for resume-from-pause, so an unchanged track keeps
     * its accumulated play time.
     */
    public void playbackStarted(MediaItem item) {
        if (item == null) {
            return;
        }
        if (!item.identity().equals(currentKey)) {
            trackChanged(item);
        } else if (playedMs == 0L) {
            // First actual start (not a resume): stamp the real playback start time.
            startedAtMs = System.currentTimeMillis();
        }
        if (!settings.isListenBrainzConfigured() || !settings.announceNowPlaying()
                || !ScrobblePolicy.isSubmittable(item.author, item.title)) {
            return;
        }
        final Scrobble listen = Scrobble.from(item, nowSec(), durationOf(item, 0L), 0L);
        final String token = settings.listenBrainzToken();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    api().submitPlayingNow(token, listen);
                    lastError = "";
                } catch (IOException error) {
                    lastError = describe(error);
                }
            }
        });
        flush();
    }

    /**
     * Called about once a second while playing. Accumulates real listening time
     * (seeking cannot inflate it) and enqueues a listen once the threshold is met.
     *
     * @param deltaMs    wall-clock time since the previous tick
     * @param durationMs track duration from the player, or 0 if unknown
     */
    public void playbackTick(MediaItem item, long deltaMs, long durationMs) {
        if (item == null) {
            return;
        }
        if (!item.identity().equals(currentKey)) {
            trackChanged(item);
        }
        if (submitted || !settings.isListenBrainzConfigured()) {
            return;
        }
        if (deltaMs <= 0L || deltaMs > ScrobblePolicy.MAX_TICK_MS) {
            return;
        }
        if (!ScrobblePolicy.isSubmittable(item.author, item.title)) {
            submitted = true;
            return;
        }
        playedMs += deltaMs;
        if (ScrobblePolicy.shouldScrobble(playedMs, durationOf(item, durationMs))) {
            submitted = true;
            enqueue(Scrobble.from(item, startedAtMs / 1000L,
                    durationOf(item, durationMs), playedMs));
        }
    }

    /** True once the current track has crossed the listen threshold (for a UI badge). */
    public boolean isCurrentTrackScrobbled() {
        return submitted;
    }

    private static long durationOf(MediaItem item, long playerDurationMs) {
        if (playerDurationMs > 0L) {
            return playerDurationMs;
        }
        return item.durationSec > 0L ? item.durationSec * 1000L : 0L;
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }

    /* ================================================================== queue */

    /** Number of listens detected but not yet accepted by ListenBrainz. */
    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** Last submission error, empty when the most recent request succeeded. */
    public String lastError() {
        return lastError;
    }

    public void clearLastError() {
        lastError = "";
    }

    /** Drops every queued listen (Settings → Pending listens, long-press style safety valve). */
    public void clearPending() {
        synchronized (lock) {
            pending.clear();
            persistLocked();
        }
    }

    private void enqueue(Scrobble listen) {
        if (listen == null || !listen.isSubmittable()) {
            return;
        }
        synchronized (lock) {
            pending.add(listen);
            while (pending.size() > MAX_PENDING) {
                pending.remove(0);
            }
            persistLocked();
        }
        flush();
    }

    /** Sends queued listens in the background; safe to call often. */
    public void flush() {
        if (!settings.isListenBrainzConfigured() || pendingCount() == 0) {
            return;
        }
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    drain();
                } finally {
                    flushing.set(false);
                }
            }
        });
    }

    private void drain() {
        String token = settings.listenBrainzToken();
        int rateLimitRetries = 0;
        while (true) {
            final List<Scrobble> batch;
            final boolean single;
            synchronized (lock) {
                if (pending.isEmpty()) {
                    break;
                }
                single = pending.size() == 1;
                int count = Math.min(pending.size(), BATCH_SIZE);
                batch = new ArrayList<>(pending.subList(0, count));
            }
            try {
                if (single) {
                    api().submitSingle(token, batch.get(0));
                } else {
                    api().submitImport(token, batch);
                }
                synchronized (lock) {
                    for (int i = 0; i < batch.size() && !pending.isEmpty(); i++) {
                        pending.remove(0);
                    }
                    persistLocked();
                }
                lastError = "";
                rateLimitRetries = 0;
            } catch (ListenBrainzApi.HttpFailure failure) {
                if (failure.code == 429 && rateLimitRetries < MAX_RATE_LIMIT_RETRIES) {
                    rateLimitRetries++;
                    sleep(failure.retryAfterSec * 1000L);
                    continue;
                }
                lastError = describe(failure);
                break;
            } catch (IOException error) {
                lastError = describe(error);
                break;
            }
            if (pendingCount() > 0) {
                sleep(REQUEST_SPACING_MS);
            }
        }
    }

    private ListenBrainzApi api() {
        if (api == null) {
            api = new ListenBrainzApi(context);
        }
        return api;
    }

    private void persistLocked() {
        prefs.edit().putString(KEY_PENDING, Scrobble.toJsonArray(pending).toString()).apply();
    }

    private static void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(Math.min(millis, 125_000L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(IOException error) {
        if (error == null || error.getMessage() == null) {
            return "ListenBrainz submission failed";
        }
        String message = error.getMessage();
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }
}
