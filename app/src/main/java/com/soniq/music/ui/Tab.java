package com.soniq.music.ui;

import com.soniq.music.R;

/** Bottom navigation destinations. */
public enum Tab {
    HOME("Home", R.drawable.ic_home),
    SEARCH("Search", R.drawable.ic_search),
    LIBRARY("Library", R.drawable.ic_library),
    SETTINGS("Settings", R.drawable.ic_settings);

    public final String label;
    public final int icon;

    Tab(String label, int icon) {
        this.label = label;
        this.icon = icon;
    }
}
