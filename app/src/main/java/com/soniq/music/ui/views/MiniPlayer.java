package com.soniq.music.ui.views;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.soniq.music.R;
import com.soniq.music.data.MediaItem;
import com.soniq.music.playback.PlaybackService;
import com.soniq.music.ui.NetworkImage;
import com.soniq.music.ui.Theme;
import com.soniq.music.ui.Ui;

/** Compact now-playing bar pinned above the bottom navigation. */
public final class MiniPlayer extends LinearLayout {

    public interface Listener {
        void onOpenPlayer();

        void onToggle();

        void onNext();
    }

    private final NetworkImage art;
    private final TextView title;
    private final TextView artist;
    private final ImageView playIcon;
    private final Listener listener;

    public MiniPlayer(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int padding = Ui.dp(context, 8);
        setPadding(padding, padding, padding, padding);
        setClickable(true);
        setFocusable(true);
        setBackground(Ui.ripple(context, Theme.SURFACE_HIGH, 0f));

        art = new NetworkImage(context);
        art.setRadiusDp(6f);
        int artSize = Ui.dp(context, 42);
        addView(art, new LinearLayout.LayoutParams(artSize, artSize));

        LinearLayout info = Ui.column(context);
        info.setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 6), 0);
        title = Ui.text(context, "Nothing playing", 13f, Theme.TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(title);
        artist = Ui.text(context, Theme.formatDuration(0), 11f, Theme.TEXT_DIM, false);
        artist.setMaxLines(1);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(artist);
        addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        playIcon = new ImageView(context);
        playIcon.setImageResource(R.drawable.ic_play);
        playIcon.setColorFilter(Theme.TEXT);
        int buttonSize = Ui.dp(context, 40);
        playIcon.setLayoutParams(new LinearLayout.LayoutParams(buttonSize, buttonSize));
        playIcon.setPadding(Ui.dp(context, 10), Ui.dp(context, 10),
                Ui.dp(context, 10), Ui.dp(context, 10));
        playIcon.setBackground(Ui.ripple(context, 0x00000000, 20f));
        playIcon.setClickable(true);
        playIcon.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) {
                    listener.onToggle();
                }
            }
        });
        addView(playIcon);

        ImageView next = new ImageView(context);
        next.setImageResource(R.drawable.ic_next);
        next.setColorFilter(Theme.TEXT_DIM);
        next.setLayoutParams(new LinearLayout.LayoutParams(buttonSize, buttonSize));
        next.setPadding(Ui.dp(context, 10), Ui.dp(context, 10),
                Ui.dp(context, 10), Ui.dp(context, 10));
        next.setBackground(Ui.ripple(context, 0x00000000, 20f));
        next.setClickable(true);
        next.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) {
                    listener.onNext();
                }
            }
        });
        addView(next);

        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) {
                    listener.onOpenPlayer();
                }
            }
        });
    }

    public void update(PlaybackService service) {
        MediaItem item = service == null ? null : service.current();
        if (item == null) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        title.setText(item.title);
        artist.setText(item.author.length() > 0 ? item.author : "Soniq Music");
        art.load(item.bestThumb());
        boolean playing = service.isPlaying();
        playIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (service.isPreparing()) {
            artist.setText(service.getStatus().length() > 0
                    ? service.getStatus() : "Loading…");
        }
    }
}
