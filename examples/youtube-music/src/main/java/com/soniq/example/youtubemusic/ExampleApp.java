package com.soniq.example.youtubemusic;

import android.os.Build;
import android.util.Log;

import com.soniq.base.SoniqApp;
import com.soniq.base.provider.MusicProvider;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The example app: one line of wiring (which music source to use) plus a crash
 * log, so a release build that dies with no cable attached still leaves a
 * readable stack trace behind.
 */
public final class ExampleApp extends SoniqApp {

    private static final String TAG = "SoniqExample";
    /** Written on a crash to {@code Android/data/<package>/files/last-crash.txt}. */
    public static final String CRASH_FILE = "last-crash.txt";

    @Override
    public void onCreate() {
        // Install the handler before anything else so start-up failures are
        // captured too.
        installCrashLog();
        super.onCreate();
    }

    @Override
    protected MusicProvider createProvider() {
        return new YouTubeMusicProvider(this);
    }

    /**
     * Release builds strip stack traces, and a phone with no adb attached has
     * no log to read, so the next uncaught exception is also written to the
     * app's external files directory — a path any file manager can open.
     */
    private void installCrashLog() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable failure) {
                writeCrash(thread, failure);
                if (previous != null) {
                    previous.uncaughtException(thread, failure);
                }
            }
        });
    }

    private void writeCrash(Thread thread, Throwable failure) {
        try {
            Log.e(TAG, "Uncaught exception on " + thread.getName(), failure);
        } catch (Throwable ignored) {
            // Logging must never be the reason a crash goes unreported.
        }
        try {
            File directory = getExternalFilesDir(null);
            if (directory == null) {
                directory = getFilesDir();
            }
            File target = new File(directory, CRASH_FILE);
            StringWriter trace = new StringWriter();
            PrintWriter writer = new PrintWriter(trace);
            writer.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date()));
            writer.println("Android " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + "), "
                    + Build.MANUFACTURER + " " + Build.MODEL);
            writer.println("Thread: " + thread.getName());
            writer.println();
            failure.printStackTrace(writer);
            writer.flush();
            FileWriter out = new FileWriter(target, false);
            try {
                out.write(trace.toString());
            } finally {
                out.close();
            }
            Log.e(TAG, "Crash written to " + target.getAbsolutePath());
        } catch (Throwable ignored) {
            // Nothing useful left to do, and the crash handler must not crash.
        }
    }
}
