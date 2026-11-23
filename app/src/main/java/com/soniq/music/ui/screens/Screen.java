package com.soniq.music.ui.screens;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.soniq.music.ui.Host;
import com.soniq.music.ui.Ui;

/** Base for the scrollable tab screens. Subclasses rebuild their body on refresh. */
public abstract class Screen {

    protected final Host host;
    private final ScrollView scroll;
    protected final LinearLayout content;

    protected Screen(Host host) {
        this.host = host;
        Context context = host.context();
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = Ui.column(context);
        content.setPadding(Ui.dp(context, 16), Ui.dp(context, 12),
                Ui.dp(context, 16), Ui.dp(context, 24));
        scroll.addView(content, new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public View view() {
        return scroll;
    }

    public void refresh() {
        content.removeAllViews();
        build(content);
    }

    protected abstract void build(LinearLayout container);

    protected final void runIo(Runnable runnable) {
        host.io().execute(runnable);
    }

    protected final void post(Runnable runnable) {
        host.main().post(runnable);
    }

    protected final int dp(int value) {
        return Ui.dp(host.context(), value);
    }
}
