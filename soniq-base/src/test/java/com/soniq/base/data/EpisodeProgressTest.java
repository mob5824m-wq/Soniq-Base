package com.soniq.base.data;

import com.soniq.base.model.MediaItem;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM tests for the pure episode-progress rules: the text codec must survive
 * hostile titles, and resumable / finished must match the shared track guards.
 */
public final class EpisodeProgressTest {

    /** The codec's field / row separators, built without literal control chars. */
    private static final char FIELD = (char) 1;
    private static final char ROW = (char) 2;

    private static EpisodeProgress.Entry entry(String identity, String title,
                                               int positionMs, int durationMs,
                                               boolean played) {
        return new EpisodeProgress.Entry(identity, title, "Show", "https://x/y.jpg",
                positionMs, durationMs, played, 1_700_000_000_000L);
    }

    @Test
    public void codecRoundTripsSeparatorsAndBackslashes() {
        List<EpisodeProgress.Entry> rows = new ArrayList<>();
        rows.add(entry("episode:a1",
                "Title with " + FIELD + " separator and " + ROW + " row",
                61_000, 1_800_000, false));
        rows.add(entry("episode:a2", "Backslash \\ path", 0, 0, true));
        rows.add(entry("episode:a3", "Trailing backslash \\", 5_000, 10_000, false));
        String encoded = EpisodeProgress.encode(rows);
        List<EpisodeProgress.Entry> decoded = EpisodeProgress.decode(encoded);
        Assert.assertEquals(3, decoded.size());
        Assert.assertEquals(rows.get(0).identity, decoded.get(0).identity);
        Assert.assertEquals(rows.get(0).title, decoded.get(0).title);
        Assert.assertEquals(61_000, decoded.get(0).positionMs);
        Assert.assertEquals(1_800_000, decoded.get(0).durationMs);
        Assert.assertFalse(decoded.get(0).played);
        Assert.assertEquals(rows.get(1).title, decoded.get(1).title);
        Assert.assertTrue(decoded.get(1).played);
        Assert.assertEquals(rows.get(2).title, decoded.get(2).title);
    }

    @Test
    public void decodeSkipsCorruptRowsInsteadOfThrowing() {
        List<EpisodeProgress.Entry> rows = new ArrayList<>();
        rows.add(entry("episode:good", "Fine", 1_000, 2_000, false));
        String encoded = EpisodeProgress.encode(rows);
        // A truncated row (non-numeric field) and a foreign row are dropped.
        String hostile = encoded
                + ROW + "episode:bad" + FIELD + "Title" + FIELD + "Show" + FIELD
                + "thumb" + FIELD + "NaN" + FIELD + "100" + FIELD + "0" + FIELD + "1"
                + ROW + "only-two-fields";
        List<EpisodeProgress.Entry> decoded = EpisodeProgress.decode(hostile);
        Assert.assertEquals(1, decoded.size());
        Assert.assertEquals("episode:good", decoded.get(0).identity);
        Assert.assertTrue(EpisodeProgress.decode("").isEmpty());
        Assert.assertTrue(EpisodeProgress.decode(null).isEmpty());
    }

    @Test
    public void resumableRequiresTheSameGuardsAsTrackResume() {
        // Too early to count as a listen.
        Assert.assertFalse(EpisodeProgress.isResumable(5_000, 1_800_000));
        // Inside the end guard: this episode is done, start it over.
        Assert.assertFalse(EpisodeProgress.isResumable(1_795_000, 1_800_000));
        // Comfortably mid-episode.
        Assert.assertTrue(EpisodeProgress.isResumable(600_000, 1_800_000));
        // Unknown duration: only the entry guard applies.
        Assert.assertTrue(EpisodeProgress.isResumable(600_000, 0));
        Assert.assertFalse(EpisodeProgress.isResumable(5_000, 0));
    }

    @Test
    public void finishedMeansInsideTheEndGuard() {
        Assert.assertFalse(EpisodeProgress.isFinished(0, 1_800_000));
        Assert.assertFalse(EpisodeProgress.isFinished(1_000, 0));
        Assert.assertFalse(EpisodeProgress.isFinished(1_780_000, 1_800_000));
        Assert.assertTrue(EpisodeProgress.isFinished(1_795_000, 1_800_000));
        Assert.assertTrue(EpisodeProgress.isFinished(1_800_000, 1_800_000));
    }

    @Test
    public void entryRebuildsAPlayableEpisodeItem() {
        EpisodeProgress.Entry row = entry("episode:xyz", "Ep title", 90_000, 1_800_000, false);
        MediaItem item = row.toMediaItem();
        Assert.assertNotNull(item);
        Assert.assertTrue(item.isEpisode());
        Assert.assertFalse(item.isBrowsable());
        Assert.assertTrue(item.isPlayable());
        Assert.assertEquals("episode:xyz", item.identity());
        Assert.assertEquals("Ep title", item.title);
        Assert.assertEquals(1_800L, item.durationSec);
    }

    @Test
    public void entryWithForeignIdentityRebuildsNothing() {
        // A corrupt row whose identity is not an episode must not become a
        // half-item that the player cannot resolve.
        Assert.assertNull(entry("track:abc", "Title", 0, 0, false).toMediaItem());
        Assert.assertNull(entry("episode:", "Title", 0, 0, false).toMediaItem());
        Assert.assertNull(entry("", "Title", 0, 0, false).toMediaItem());
    }
}
