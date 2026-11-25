package com.soniq.base.ui.screens;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.soniq.base.data.EpisodeProgress;
import com.soniq.base.data.Library;
import com.soniq.base.model.MediaItem;
import com.soniq.base.ui.Host;
import com.soniq.base.ui.Theme;
import com.soniq.base.ui.Ui;
import com.soniq.base.ui.views.Chip;
import com.soniq.base.ui.views.TrackRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Podcasts tab: episodes in progress, subscribed shows, and whatever the
 * provider offers under {@link com.soniq.base.provider.MusicProvider#podcasts()}.
 *
 * <p>Every empty state is honest: no subscriptions says how to subscribe, and
 * a provider without a podcast catalogue says so instead of showing nothing.
 */
public final class PodcastsScreen extends Screen {

    private EpisodeProgress progress;
    private LinearLayout continueContainer;
    private LinearLayout showsContainer;
    private LinearLayout discoverContainer;
    private final List<MediaItem> discover = new ArrayList<>();
    private boolean loadingDiscover;
    private boolean discoverLoaded;

    public PodcastsScreen(Host host) {
        super(host);
    }

    @Override
    protected void build(LinearLayout container) {
        if (progress == null) {
            progress = new EpisodeProgress(host.context());
        }
        container.addView(Ui.text(host.context(), "Podcasts", 28f, Theme.TEXT, true));
        container.addView(Ui.text(host.context(),
                "Shows and episodes from " + host.provider().podcastSourceName(),
                12f, Theme.TEXT_DIM, false));

        buildContinue(container);
        buildShows(container);
        buildDiscover(container);
    }

    /** Episodes the listener is part-way through; one tap resumes each. */
    private void buildContinue(LinearLayout container) {
        container.addView(Ui.sectionHeader(host.context(), "Continue"));
        continueContainer = Ui.column(host.context());
        container.addView(continueContainer);
        List<EpisodeProgress.Entry> entries = progress.inProgress();
        if (entries.isEmpty()) {
            container.addView(Ui.caption(host.context(),
                    "Episodes you're part-way through will show up here."));
            return;
        }
        int shown = 0;
        for (EpisodeProgress.Entry entry : entries) {
            final MediaItem item = entry.toMediaItem();
            if (item == null) {
                continue;
            }
            continueContainer.addView(new TrackRow(host.context(), item, shown, true, false,
                    new TrackRow.Callback() {
                        @Override
                        public void onClick(MediaItem clicked, int position) {
                            // prepareCurrent resumes at the saved position.
                            host.playSingle(clicked);
                        }

                        @Override
                        public void onMore(View anchor, MediaItem clicked, int position) {
                            host.showTrackMenu(clicked);
                        }

                        @Override
                        public void onLongClick(MediaItem clicked, int position) {
                            host.showTrackMenu(clicked);
                        }
                    }));
            continueContainer.addView(positionCaption(entry));
            shown++;
            if (shown >= 8) {
                break;
            }
        }
    }

    private TextView positionCaption(EpisodeProgress.Entry entry) {
        String text = entry.durationMs > 0
                ? MediaItem.formatDuration(entry.positionMs) + " of "
                + MediaItem.formatDuration(entry.durationMs)
                : MediaItem.formatDuration(entry.positionMs) + " listened";
        TextView caption = Ui.text(host.context(), text, 11f, Theme.TEXT_FAINT, false);
        caption.setPadding(dp(60), 0, dp(4), dp(6));
        return caption;
    }

    /** Subscribed shows; tapping one browses its episodes. */
    private void buildShows(LinearLayout container) {
        List<MediaItem> shows = host.library().list(Library.PODCASTS);
        container.addView(Ui.sectionHeader(host.context(),
                "Your shows" + (shows.isEmpty() ? "" : " · " + shows.size())));
        showsContainer = Ui.column(host.context());
        container.addView(showsContainer);
        if (shows.isEmpty()) {
            container.addView(Ui.caption(host.context(),
                    "Open a show from Search or Discover, then tap More → Subscribe."));
            Chip find = new Chip(host.context(), "Find podcasts", new Runnable() {
                @Override
                public void run() {
                    host.openSearch("");
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(4);
            showsContainer.addView(find, params);
            return;
        }
        for (int i = 0; i < shows.size(); i++) {
            final MediaItem show = shows.get(i);
            showsContainer.addView(new TrackRow(host.context(), show, i, true, false,
                    new TrackRow.Callback() {
                        @Override
                        public void onClick(MediaItem clicked, int position) {
                            host.openCollection(clicked);
                        }

                        @Override
                        public void onMore(View anchor, MediaItem clicked, int position) {
                            host.showTrackMenu(clicked);
                        }

                        @Override
                        public void onLongClick(MediaItem clicked, int position) {
                            host.addToPlaylist(clicked);
                        }
                    }));
        }
    }

    /** What the provider itself surfaces as podcasts (may honestly be nothing). */
    private void buildDiscover(LinearLayout container) {
        container.addView(Ui.sectionHeader(host.context(), "Discover"));
        discoverContainer = Ui.column(host.context());
        container.addView(discoverContainer);
        if (discoverLoaded) {
            renderDiscover();
            return;
        }
        if (!loadingDiscover) {
            loadDiscover();
        }
        discoverContainer.addView(Ui.caption(host.context(),
                "Looking up podcasts on " + host.provider().podcastSourceName() + "…"));
    }

    private void loadDiscover() {
        loadingDiscover = true;
        runIo(new Runnable() {
            @Override
            public void run() {
                final List<MediaItem> found;
                final Exception failure;
                try {
                    found = host.provider().podcasts();
                    failure = null;
                } catch (Exception error) {
                    failure = error;
                    found = null;
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        loadingDiscover = false;
                        // Cache the answer (including "none"), but let a
                        // failure retry on the next refresh instead of sticking.
                        discoverLoaded = failure == null;
                        discover.clear();
                        if (found != null) {
                            discover.addAll(found);
                        }
                        if (discoverContainer != null) {
                            discoverContainer.removeAllViews();
                            renderDiscover(failure);
                        }
                    }
                });
            }
        });
    }

    private void renderDiscover() {
        renderDiscover(null);
    }

    private void renderDiscover(Exception failure) {
        if (discoverContainer == null) {
            return;
        }
        if (failure != null) {
            discoverContainer.addView(Ui.caption(host.context(),
                    "Podcasts are unavailable: " + failure.getMessage()));
            return;
        }
        if (discover.isEmpty()) {
            // Honest empty state: the source has no podcast catalogue for us.
            discoverContainer.addView(Ui.emptyState(host.context(),
                    "No podcasts from " + host.provider().podcastSourceName(),
                    "This source doesn't offer podcasts right now. "
                            + "Shows you subscribe to still appear above."));
            return;
        }
        for (int i = 0; i < discover.size(); i++) {
            final int index = i;
            final MediaItem item = discover.get(i);
            discoverContainer.addView(new TrackRow(host.context(), item, i, true, false,
                    new TrackRow.Callback() {
                        @Override
                        public void onClick(MediaItem clicked, int position) {
                            if (clicked.isEpisode()) {
                                host.play(discover, index);
                            } else {
                                host.openCollection(clicked);
                            }
                        }

                        @Override
                        public void onMore(View anchor, MediaItem clicked, int position) {
                            host.showTrackMenu(clicked);
                        }

                        @Override
                        public void onLongClick(MediaItem clicked, int position) {
                            host.addToPlaylist(clicked);
                        }
                    }));
        }
    }
}
