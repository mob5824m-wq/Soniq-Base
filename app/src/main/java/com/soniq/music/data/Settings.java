package com.soniq.music.data;

import android.content.Context;
import android.content.SharedPreferences;

/** Small preference facade. Everything is stored in one private SharedPreferences file. */
public final class Settings {

    public static final int QUALITY_128 = 128000;
    public static final int QUALITY_256 = 256000;

    public static final String CLIENT_AUTO = "auto";

    private static final String PREFS = "soniq_settings";
    private static final String KEY_QUALITY = "target_bitrate";
    private static final String KEY_CLIENT = "preferred_client";
    private static final String KEY_NOTIFICATIONS_ASKED = "notifications_asked";

    private final SharedPreferences prefs;

    public Settings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int targetBitrate() {
        return prefs.getInt(KEY_QUALITY, QUALITY_128);
    }

    public void setTargetBitrate(int bitrate) {
        prefs.edit().putInt(KEY_QUALITY, bitrate).apply();
    }

    public String preferredClient() {
        return prefs.getString(KEY_CLIENT, CLIENT_AUTO);
    }

    public void setPreferredClient(String clientKey) {
        prefs.edit().putString(KEY_CLIENT, clientKey == null ? CLIENT_AUTO : clientKey).apply();
    }

    public boolean notificationsAsked() {
        return prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false);
    }

    public void setNotificationsAsked(boolean asked) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, asked).apply();
    }

    public String qualityLabel() {
        return (targetBitrate() / 1000) + " kbps";
    }
}
