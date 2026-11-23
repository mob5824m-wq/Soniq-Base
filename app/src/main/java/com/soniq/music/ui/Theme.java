package com.soniq.music.ui;

/** Palette and small formatting helpers for the dark, music-first UI. */
public final class Theme {

    public static final int BG = 0xFF0B0B0F;
    public static final int SURFACE = 0xFF15151B;
    public static final int SURFACE_HIGH = 0xFF1E1E26;
    public static final int OUTLINE = 0xFF2C2C36;
    public static final int ACCENT = 0xFFFF3B5C;
    public static final int ACCENT_SOFT = 0x33FF3B5C;
    public static final int SECONDARY = 0xFF7C5CFF;
    public static final int TEXT = 0xFFF5F5F7;
    public static final int TEXT_DIM = 0xFF9A9AA6;
    public static final int TEXT_FAINT = 0xFF6E6E7A;

    private Theme() {
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    public static String formatDuration(long millis) {
        if (millis <= 0L) {
            return "0:00";
        }
        long totalSeconds = millis / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return hours + ":" + two(minutes) + ":" + two(seconds);
        }
        return minutes + ":" + two(seconds);
    }

    private static String two(long value) {
        return value < 10L ? "0" + value : String.valueOf(value);
    }
}
