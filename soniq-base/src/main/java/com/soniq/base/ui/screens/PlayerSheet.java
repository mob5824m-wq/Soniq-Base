package com.soniq.base.ui.screens;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.soniq.base.R;
import com.soniq.base.data.LyricLine;
import com.soniq.base.data.LyricsApi;
import com.soniq.base.data.ScrobbleQueue;
import com.soniq.base.model.MediaItem;
import com.soniq.base.playback.PlaybackQueue;
import com.soniq.base.playback.PlaybackService;
import com.soniq.base.ui.Host;
import com.soniq.base.ui.NetworkImage;
import com.soniq.base.ui.Theme;
import com.soniq.base.ui.Ui;
import com.soniq.base.ui.views.LyricsView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen now-playing sheet with artwork, transport, lyrics and actions. */
public final class PlayerSheet {

    private final Host host;
    private final FrameLayout root;
    private final ScrollView scroll;
    private final NetworkImage art;
    private final TextView title;
    private final TextView artist;
    private final TextView lyricBanner;
    private final TextView positionLabel;
    private final TextView durationLabel;
    private final TextView statusLabel;
    private final SeekBar seekBar;
    private final ImageView playIcon;
    private final ImageView shuffleIcon;
    private final ImageView repeatIcon;
    private final ImageView likeIcon;
    private final LyricsView lyricsView;
    private final LinearLayout lyricsPanel;
    private final List<LyricLine> lyrics = new ArrayList<>();
    private final ExecutorService lyricsExecutor = Executors.newSingleThreadExecutor();
    private final LyricsApi lyricsApi;

    private boolean scrubbing;
    private boolean lyricsVisible;
    private String lyricsKey = "";
    private int lyricsRequest;

    public PlayerSheet(Host host) {
        this.host = host;
        this.lyricsApi = new LyricsApi();
        this.root = new FrameLayout(host.context());
        root.setBackgroundColor(Theme.BG);
        root.setVisibility(View.GONE);

        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF24101C, 0xFF0B0B0F, 0xFF0B0B0F});
        root.setBackground(gradient);

        scroll = new ScrollView(host.context());
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = Ui.column(host.context());
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(Ui.dp(host.context(), 24), Ui.dp(host.context(), 12),
                Ui.dp(host.context(), 24), Ui.dp(host.context(), 32));
        scroll.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = Ui.row(host.context());
        topBar.addView(Ui.iconButton(host.context(), R.drawable.soniq_ic_close,
                Theme.TEXT_DIM, 44, 22, new Runnable() {
                    @Override
                    public void run() {
                        hide();
                    }
                }));
        TextView nowPlaying = Ui.text(host.context(), "NOW PLAYING", 11f,
                Theme.TEXT_DIM, true);
        nowPlaying.setGravity(Gravity.CENTER);
        topBar.addView(nowPlaying, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(Ui.iconButton(host.context(), R.drawable.soniq_ic_more,
                Theme.TEXT_DIM, 44, 20, new Runnable() {
                    @Override
                    public void run() {
                        MediaItem item = service() == null ? null : service().current();
                        if (item != null) {
                            host.showTrackMenu(item);
                        }
                    }
                }));
        column.addView(topBar);

        art = new NetworkImage(host.context());
        art.setRadiusDp(20f);
        int artSize = Ui.dp(host.context(), 300);
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(artSize, artSize);
        artParams.topMargin = Ui.dp(host.context(), 8);
        artParams.bottomMargin = Ui.dp(host.context(), 20);
        column.addView(art, artParams);

        lyricBanner = Ui.text(host.context(), "", 12f, Theme.ACCENT, true);
        lyricBanner.setGravity(Gravity.CENTER);
        lyricBanner.setSingleLine(true);
        lyricBanner.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lyricBanner.setPadding(0, 0, 0, Ui.dp(host.context(), 8));
        column.addView(lyricBanner);

        LinearLayout titleRow = Ui.row(host.context());
        LinearLayout titles = Ui.column(host.context());
        title = Ui.text(host.context(), "Nothing playing", 22f, Theme.TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(title);
        artist = Ui.text(host.context(), "Pick a song to start", 14f, Theme.TEXT_DIM, false);
        artist.setMaxLines(1);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(artist);
        titleRow.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        likeIcon = new ImageView(host.context());
        likeIcon.setImageResource(R.drawable.soniq_ic_favorite_border);
        likeIcon.setColorFilter(Theme.TEXT_DIM);
        likeIcon.setPadding(Ui.dp(host.context(), 10), Ui.dp(host.context(), 10),
                Ui.dp(host.context(), 10), Ui.dp(host.context(), 10));
        likeIcon.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(host.context(), 44),
                Ui.dp(host.context(), 44)));
        likeIcon.setClickable(true);
        likeIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MediaItem item = service() == null ? null : service().current();
                if (item != null) {
                    host.toggleLike(item);
                }
            }
        });
        titleRow.addView(likeIcon);
        column.addView(titleRow);

        seekBar = new SeekBar(host.context());
        seekBar.setMax(1);
        seekBar.setProgress(0);
        seekBar.setPadding(Ui.dp(host.context(), 4), 0, Ui.dp(host.context(), 4), 0);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    positionLabel.setText(Theme.formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                scrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                scrubbing = false;
                if (service() != null) {
                    service().seekTo(seekBar.getProgress());
                }
            }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(host.context(), 28));
        seekParams.topMargin = Ui.dp(host.context(), 8);
        column.addView(seekBar, seekParams);

        LinearLayout times = Ui.row(host.context());
        positionLabel = Ui.text(host.context(), "0:00", 11f, Theme.TEXT_DIM, false);
        durationLabel = Ui.text(host.context(), "0:00", 11f, Theme.TEXT_DIM, false);
        times.addView(positionLabel, Ui.weight(1f));
        times.addView(durationLabel);
        column.addView(times);

        statusLabel = Ui.text(host.context(), "", 12f, Theme.ACCENT, false);
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setPadding(0, Ui.dp(host.context(), 6), 0, 0);
        column.addView(statusLabel);

        LinearLayout transport = Ui.row(host.context());
        transport.setGravity(Gravity.CENTER);
        transport.setPadding(0, Ui.dp(host.context(), 10), 0, Ui.dp(host.context(), 4));
        shuffleIcon = transportIcon(R.drawable.soniq_ic_shuffle, Theme.TEXT_DIM, new Runnable() {
            @Override
            public void run() {
                if (service() != null) {
                    service().setShuffle(!service().isShuffle());
                }
            }
        });
        transport.addView(shuffleIcon);
        transport.addView(transportIcon(R.drawable.soniq_ic_prev, Theme.TEXT, new Runnable() {
            @Override
            public void run() {
                if (service() != null) {
                    service().previous();
                }
            }
        }));
        playIcon = new ImageView(host.context());
        playIcon.setImageResource(R.drawable.soniq_ic_play);
        playIcon.setColorFilter(Color.WHITE);
        int playSize = Ui.dp(host.context(), 72);
        playIcon.setPadding(Ui.dp(host.context(), 22), Ui.dp(host.context(), 22),
                Ui.dp(host.context(), 22), Ui.dp(host.context(), 22));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(playSize, playSize);
        playParams.leftMargin = Ui.dp(host.context(), 18);
        playParams.rightMargin = Ui.dp(host.context(), 18);
        playIcon.setLayoutParams(playParams);
        GradientDrawable playBackground = Ui.rounded(host.context(), Theme.ACCENT, 36f);
        playIcon.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                playBackground, Ui.rounded(host.context(), 0xFFFFFFFF, 36f)));
        playIcon.setClickable(true);
        playIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (service() != null) {
                    service().toggle();
                }
            }
        });
        transport.addView(playIcon);
        transport.addView(transportIcon(R.drawable.soniq_ic_next, Theme.TEXT, new Runnable() {
            @Override
            public void run() {
                if (service() != null) {
                    service().next();
                }
            }
        }));
        repeatIcon = transportIcon(R.drawable.soniq_ic_repeat, Theme.TEXT_DIM, new Runnable() {
            @Override
            public void run() {
                if (service() != null) {
                    service().cycleRepeat();
                }
            }
        });
        transport.addView(repeatIcon);
        column.addView(transport);

        LinearLayout actions = Ui.row(host.context());
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, Ui.dp(host.context(), 8), 0, Ui.dp(host.context(), 8));
        actions.addView(action("Lyrics", R.drawable.soniq_ic_lyrics, new Runnable() {
            @Override
            public void run() {
                toggleLyrics();
            }
        }));
        actions.addView(action("Save", R.drawable.soniq_ic_download, new Runnable() {
            @Override
            public void run() {
                MediaItem item = service() == null ? null : service().current();
                if (item != null) {
                    host.download(item);
                }
            }
        }));
        actions.addView(action("Queue", R.drawable.soniq_ic_queue, new Runnable() {
            @Override
            public void run() {
                host.showQueue();
            }
        }));
        actions.addView(action("Radio", R.drawable.soniq_ic_radio, new Runnable() {
            @Override
            public void run() {
                MediaItem item = service() == null ? null : service().current();
                if (item != null) {
                    host.startRadio(item);
                }
            }
        }));
        column.addView(actions);

        lyricsPanel = Ui.column(host.context());
        lyricsPanel.setVisibility(View.GONE);
        lyricsView = new LyricsView(host.context());
        lyricsView.setSeekCallback(new LyricsView.SeekCallback() {
            @Override
            public void onSeekTo(long positionMs) {
                if (service() != null) {
                    service().seekTo((int) positionMs);
                }
            }
        });
        LinearLayout.LayoutParams lyricsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(host.context(), 260));
        lyricsParams.topMargin = Ui.dp(host.context(), 8);
        lyricsPanel.addView(lyricsView, lyricsParams);
        column.addView(lyricsPanel);
    }

    private ImageView transportIcon(int resource, int tint, final Runnable onClick) {
        ImageView image = new ImageView(host.context());
        image.setImageResource(resource);
        image.setColorFilter(tint);
        int size = Ui.dp(host.context(), 40);
        image.setPadding(Ui.dp(host.context(), 8), Ui.dp(host.context(), 8),
                Ui.dp(host.context(), 8), Ui.dp(host.context(), 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.leftMargin = Ui.dp(host.context(), 8);
        params.rightMargin = Ui.dp(host.context(), 8);
        image.setLayoutParams(params);
        image.setBackground(Ui.ripple(host.context(), 0x00000000, 20f));
        image.setClickable(true);
        image.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onClick.run();
            }
        });
        return image;
    }

    private LinearLayout action(String label, int icon, final Runnable onClick) {
        LinearLayout button = Ui.column(host.context());
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(host.context(), 12), Ui.dp(host.context(), 8),
                Ui.dp(host.context(), 12), Ui.dp(host.context(), 8));
        button.setBackground(Ui.ripple(host.context(), 0x00000000, 14f));
        button.setClickable(true);
        ImageView image = new ImageView(host.context());
        image.setImageResource(icon);
        image.setColorFilter(Theme.TEXT_DIM);
        image.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(host.context(), 22),
                Ui.dp(host.context(), 22)));
        button.addView(image);
        TextView text = Ui.text(host.context(), label, 10f, Theme.TEXT_DIM, false);
        text.setPadding(0, Ui.dp(host.context(), 4), 0, 0);
        button.addView(text);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onClick.run();
            }
        });
        return button;
    }

    public View view() {
        return root;
    }

    public void show() {
        root.setVisibility(View.VISIBLE);
        update();
    }

    public void hide() {
        root.setVisibility(View.GONE);
    }

    public boolean isShowing() {
        return root.getVisibility() == View.VISIBLE;
    }

    public void toggleLyrics() {
        lyricsVisible = !lyricsVisible;
        lyricsPanel.setVisibility(lyricsVisible ? View.VISIBLE : View.GONE);
        if (lyricsVisible) {
            updateLyrics(true);
        }
    }

    public void update() {
        PlaybackService service = service();
        MediaItem item = service == null ? null : service.current();
        if (item == null) {
            title.setText("Nothing playing");
            artist.setText("Pick a song to start");
            playIcon.setImageResource(R.drawable.soniq_ic_play);
            seekBar.setProgress(0);
            positionLabel.setText("0:00");
            durationLabel.setText("0:00");
            lyricBanner.setText("");
            statusLabel.setText("");
            return;
        }
        if (!item.title.equals(title.getText().toString())) {
            title.setText(item.title);
        }
        String artistText = item.author.length() > 0 ? item.author : "Soniq Music";
        if (!artistText.equals(artist.getText().toString())) {
            artist.setText(artistText);
        }
        art.load(item.bestThumb());
        playIcon.setImageResource(service.isPlaying() ? R.drawable.soniq_ic_pause : R.drawable.soniq_ic_play);
        shuffleIcon.setColorFilter(service.isShuffle() ? Theme.ACCENT : Theme.TEXT_DIM);
        int repeat = service.repeatMode();
        repeatIcon.setImageResource(repeat == PlaybackQueue.REPEAT_ONE
                ? R.drawable.soniq_ic_repeat_one : R.drawable.soniq_ic_repeat);
        repeatIcon.setColorFilter(repeat == PlaybackQueue.REPEAT_OFF
                ? Theme.TEXT_DIM : Theme.ACCENT);
        boolean liked = host.isLiked(item);
        likeIcon.setImageResource(liked ? R.drawable.soniq_ic_favorite : R.drawable.soniq_ic_favorite_border);
        likeIcon.setColorFilter(liked ? Theme.ACCENT : Theme.TEXT_DIM);

        int duration = service.getDuration();
        int position = service.getPosition();
        if (duration > 0 && seekBar.getMax() != duration) {
            seekBar.setMax(duration);
        }
        if (!scrubbing) {
            seekBar.setProgress(Math.max(0, Math.min(duration, position)));
            positionLabel.setText(Theme.formatDuration(position));
        }
        durationLabel.setText(duration > 0 ? Theme.formatDuration(duration) : "--:--");
        statusLabel.setText(service.isPreparing()
                ? service.getStatus() : scrobbleBadge(item));

        updateLyrics(item, service, position);
    }

    private void updateLyrics(boolean force) {
        PlaybackService service = service();
        MediaItem item = service == null ? null : service.current();
        if (item == null) {
            return;
        }
        updateLyrics(item, service, service.getPosition());
    }

    private void updateLyrics(MediaItem item, PlaybackService service, int position) {
        String key = item.identity() + "\n" + item.title + "\n" + item.author;
        if (!key.equals(lyricsKey)) {
            lyricsKey = key;
            lyrics.clear();
            lyricsView.setLyrics(lyrics);
            lyricsRequest++;
            final int request = lyricsRequest;
            final String trackTitle = item.title;
            final String trackArtist = item.author;
            lyricsExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    final List<LyricLine> loaded = lyricsApi.fetch(trackTitle, trackArtist);
                    host.main().post(new Runnable() {
                        @Override
                        public void run() {
                            if (request != lyricsRequest) {
                                return;
                            }
                            lyrics.clear();
                            lyrics.addAll(loaded);
                            lyricsView.setLyrics(lyrics);
                            updateLyricHighlight(service.getPosition());
                        }
                    });
                }
            });
            return;
        }
        updateLyricHighlight(position);
    }

    private void updateLyricHighlight(int position) {
        int active = com.soniq.base.data.LyricsParser.activeIndex(lyrics, position);
        lyricsView.setActive(active);
        if (active >= 0 && active < lyrics.size()) {
            lyricBanner.setText(lyrics.get(active).text);
        } else {
            lyricBanner.setText("");
        }
    }

    /** Small accent line confirming the current song was sent to ListenBrainz. */
    private String scrobbleBadge(MediaItem item) {
        if (!host.settings().scrobblingEnabled() || item == null) {
            return "";
        }
        return ScrobbleQueue.get(host.context()).isCurrentTrackScrobbled()
                ? "Scrobbled to ListenBrainz ✓" : "";
    }

    private PlaybackService service() {
        return host.service();
    }

    public void release() {
        lyricsExecutor.shutdownNow();
    }
}
