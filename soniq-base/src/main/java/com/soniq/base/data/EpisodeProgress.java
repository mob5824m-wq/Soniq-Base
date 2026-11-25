package com.soniq.base.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.soniq.base.model.MediaItem;
import com.soniq.base.playback.PlaybackEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the listener left off in each podcast episode, plus which episodes
 * count as played.
 *
 * <p>One row per episode identity ({@code "episode:<id>"}), stored as a pure
 * text encoding so the resume / played rules are unit-tested on the JVM
 * without Android. The rules reuse the same guards as track resume
 * ({@link PlaybackEngine#MIN_RESUME_MS}, {@link PlaybackEngine#END_GUARD_MS})
 * so "resumable" and "finished" mean one thing across the app.
 *
 * <p>Played is <em>sticky upwards</em>: recording progress only ever sets the
 * flag (when the position reaches the end guard), never clears it — only an
 * explicit {@link #markPlayed(String, boolean) markPlayed(false)} un-marks.
 * This keeps a manual "mark as played" from being undone by the next tick.
 */
public final class EpisodeProgress {

    public static final int MAX_ENTRIES = 200;

    private static final String PREFS = "soniq_episode_progress";
    private static final String KEY_ENTRIES = "entries";
    private static final char FIELD = '\u0001';
    private static final char ROW = '\u0002';

    private final SharedPreferences prefs;

    public EpisodeProgress(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** One episode's saved state. Enough to rebuild a playable {@link MediaItem}. */
    public static final class Entry {
        public final String identity;
        public final String title;
        public final String author;
        public final String thumbnailUrl;
        public final int positionMs;
        public final int durationMs;
        public final boolean played;
        public final long updatedAt;

        public Entry(String identity, String title, String author, String thumbnailUrl,
                     int positionMs, int durationMs, boolean played, long updatedAt) {
            this.identity = identity == null ? "" : identity;
            this.title = title == null ? "" : title;
            this.author = author == null ? "" : author;
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
            this.positionMs = Math.max(0, positionMs);
            this.durationMs = Math.max(0, durationMs);
            this.played = played;
            this.updatedAt = Math.max(0L, updatedAt);
        }

        /**
         * Rebuilds the episode as a playable item. The identity is
         * {@code "episode:<id>"}; anything else (a corrupt row, a different
         * kind) returns null rather than a half-item that cannot resolve.
         */
        public MediaItem toMediaItem() {
            String prefix = MediaItem.KIND_EPISODE + ":";
            if (!identity.startsWith(prefix) || identity.length() == prefix.length()) {
                return null;
            }
            MediaItem item = new MediaItem();
            item.kind = MediaItem.KIND_EPISODE;
            item.id = identity.substring(prefix.length());
            item.title = title;
            item.author = author;
            item.thumbnailUrl = thumbnailUrl;
            if (durationMs > 0) {
                item.setDurationSec(durationMs / 1000L);
            }
            return item;
        }
    }

    /* --------------------------------------------------------- store API */

    /** Entries, most recently updated first. */
    public List<Entry> entries() {
        return decode(prefs.getString(KEY_ENTRIES, ""));
    }

    public Entry get(String identity) {
        if (identity == null || identity.length() == 0) {
            return null;
        }
        for (Entry entry : entries()) {
            if (identity.equals(entry.identity)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Saves the live position for an episode. Played is set (never cleared
     * here) once the position reaches the end guard.
     */
    public void record(MediaItem item, int positionMs, int durationMs, long now) {
        if (item == null || !item.isEpisode() || item.identity().length() == 0) {
            return;
        }
        String identity = item.identity();
        Entry updated = null;
        List<Entry> next = new ArrayList<>();
        for (Entry entry : entries()) {
            if (identity.equals(entry.identity)) {
                boolean played = entry.played || isFinished(positionMs, durationMs);
                updated = new Entry(identity, item.title, item.author, item.bestThumb(),
                        positionMs, durationMs, played, now);
            } else {
                next.add(entry);
            }
        }
        if (updated == null) {
            updated = new Entry(identity, item.title, item.author, item.bestThumb(),
                    positionMs, durationMs, isFinished(positionMs, durationMs), now);
        }
        // Most recently touched first, so "Continue listening" reads in order
        // and pruning drops the stalest episodes.
        next.add(0, updated);
        write(next);
    }

    /**
     * Explicit played / unplayed. Marking played also pins the position at
     * the end so the entry reads as finished everywhere; un-marking keeps the
     * saved position so a re-listen still resumes.
     */
    public void markPlayed(String identity, boolean played) {
        if (identity == null || identity.length() == 0) {
            return;
        }
        List<Entry> next = new ArrayList<>();
        boolean found = false;
        for (Entry entry : entries()) {
            if (identity.equals(entry.identity)) {
                found = true;
                int position = played ? Math.max(entry.positionMs, entry.durationMs)
                        : entry.positionMs;
                next.add(new Entry(entry.identity, entry.title, entry.author,
                        entry.thumbnailUrl, position, entry.durationMs, played,
                        System.currentTimeMillis()));
            } else {
                next.add(entry);
            }
        }
        if (played && !found) {
            // Nothing recorded yet: remember the decision with no position.
            next.add(0, new Entry(identity, "", "", "", 0, 0, true,
                    System.currentTimeMillis()));
        }
        if (found || played) {
            write(next);
        }
    }

    public boolean isPlayed(String identity) {
        Entry entry = get(identity);
        return entry != null && entry.played;
    }

    /**
     * Where to seek when this episode starts: the saved position when it is
     * far enough in and not at the very end. The played flag does not factor
     * in — a finished episode sits at the end guard (so it starts over), and
     * a re-listen that was paused mid-way still resumes there.
     */
    public int resumablePosition(String identity) {
        Entry entry = get(identity);
        if (entry == null) {
            return 0;
        }
        return isResumable(entry.positionMs, entry.durationMs) ? entry.positionMs : 0;
    }

    /** Unfinished episodes with a position worth resuming, most recent first. */
    public List<Entry> inProgress() {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries()) {
            if (!entry.played && isResumable(entry.positionMs, entry.durationMs)) {
                out.add(entry);
            }
        }
        return out;
    }

    public void clear() {
        prefs.edit().remove(KEY_ENTRIES).apply();
    }

    /* ------------------------------------------------------- pure rules */

    /** True when the saved position is far enough in, and not at the very end. */
    public static boolean isResumable(int positionMs, int durationMs) {
        if (positionMs < PlaybackEngine.MIN_RESUME_MS) {
            return false;
        }
        return durationMs <= 0 || positionMs < durationMs - PlaybackEngine.END_GUARD_MS;
    }

    /** True when the position is close enough to the end to count as played. */
    public static boolean isFinished(int positionMs, int durationMs) {
        if (durationMs <= 0 || positionMs <= 0) {
            return false;
        }
        return positionMs >= durationMs - PlaybackEngine.END_GUARD_MS;
    }

    /* ---------------------------------------------------------- encoding */

    static String encode(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (out.length() > 0) {
                out.append(ROW);
            }
            out.append(escape(entry.identity)).append(FIELD)
                    .append(escape(entry.title)).append(FIELD)
                    .append(escape(entry.author)).append(FIELD)
                    .append(escape(entry.thumbnailUrl)).append(FIELD)
                    .append(entry.positionMs).append(FIELD)
                    .append(entry.durationMs).append(FIELD)
                    .append(entry.played ? '1' : '0').append(FIELD)
                    .append(entry.updatedAt);
        }
        return out.toString();
    }

    static List<Entry> decode(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null || raw.length() == 0) {
            return out;
        }
        for (String row : split(raw, ROW)) {
            String[] fields = split(row, FIELD);
            if (fields.length != 8) {
                continue;
            }
            try {
                out.add(new Entry(unescape(fields[0]), unescape(fields[1]),
                        unescape(fields[2]), unescape(fields[3]),
                        Integer.parseInt(fields[4]), Integer.parseInt(fields[5]),
                        "1".equals(fields[6]), Long.parseLong(fields[7])));
            } catch (NumberFormatException ignored) {
                // Skip a corrupt row rather than losing every episode's progress.
            }
        }
        return out;
    }

    /**
     * Backslash-escapes the separators and the backslash itself, so a title
     * that literally contains U+0001 / U+0002 still round-trips.
     */
    static String escape(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == FIELD || c == ROW || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    static String unescape(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                out.append(value.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Splits on unescaped separators, keeping empty fields. */
    private static String[] split(String encoded, char separator) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '\\' && i + 1 < encoded.length()) {
                current.append(c).append(encoded.charAt(++i));
                continue;
            }
            if (c == separator) {
                fields.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private void write(List<Entry> entries) {
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        prefs.edit().putString(KEY_ENTRIES, encode(entries)).apply();
    }
}
