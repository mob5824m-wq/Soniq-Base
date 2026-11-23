package com.soniq.music.ui.screens;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.soniq.music.R;
import com.soniq.music.data.ImageLoader;
import com.soniq.music.data.InnerTubeClient;
import com.soniq.music.data.ListenBrainzApi;
import com.soniq.music.data.ScrobbleQueue;
import com.soniq.music.data.Settings;
import com.soniq.music.ui.Host;
import com.soniq.music.ui.Theme;
import com.soniq.music.ui.Ui;

import java.io.IOException;

/** Playback quality, stream client, notifications, sleep timer, EQ, ListenBrainz and about. */
public final class SettingsScreen extends Screen {

    public SettingsScreen(Host host) {
        super(host);
    }

    @Override
    protected void build(LinearLayout container) {
        container.addView(Ui.text(host.context(), "Settings", 28f, Theme.TEXT, true));
        container.addView(Ui.text(host.context(),
                "Playback behaviour on this device", 12f, Theme.TEXT_DIM, false));

        container.addView(Ui.sectionHeader(host.context(), "Playback"));
        container.addView(settingRow("Stream quality",
                "Target audio bitrate · currently " + host.settings().qualityLabel(),
                new Runnable() {
                    @Override
                    public void run() {
                        chooseQuality();
                    }
                }));
        container.addView(settingRow("Stream client",
                "Auto uses the native music client, then iOS and TV",
                new Runnable() {
                    @Override
                    public void run() {
                        chooseClient();
                    }
                }));
        container.addView(settingRow("Notifications",
                "Allow the now-playing media notification",
                new Runnable() {
                    @Override
                    public void run() {
                        host.requestNotificationPermission();
                    }
                }));
        container.addView(settingRow("Sleep timer",
                describeSleepTimer(),
                new Runnable() {
                    @Override
                    public void run() {
                        chooseSleepTimer();
                    }
                }));
        container.addView(settingRow("Equalizer",
                "System audio effect attached to this player",
                new Runnable() {
                    @Override
                    public void run() {
                        chooseEqualizer();
                    }
                }));

        container.addView(Ui.sectionHeader(host.context(), "ListenBrainz"));
        container.addView(settingRow("Scrobble to ListenBrainz", scrobbleSummary(),
                new Runnable() {
                    @Override
                    public void run() {
                        toggleScrobbling();
                    }
                }));
        container.addView(settingRow("User token", tokenSummary(), new Runnable() {
            @Override
            public void run() {
                showTokenDialog();
            }
        }));
        container.addView(settingRow("Now playing updates", host.settings().announceNowPlaying()
                        ? "On — tell ListenBrainz when a song starts"
                        : "Off — only finished listens are submitted",
                new Runnable() {
                    @Override
                    public void run() {
                        host.settings().setAnnounceNowPlaying(!host.settings().announceNowPlaying());
                        refresh();
                    }
                }));
        container.addView(settingRow("Pending listens", pendingSummary(), new Runnable() {
            @Override
            public void run() {
                ScrobbleQueue queue = ScrobbleQueue.get(host.context());
                queue.clearLastError();
                queue.flush();
                host.showToast(queue.pendingCount() == 0
                        ? "Nothing waiting to be submitted"
                        : "Submitting " + queue.pendingCount() + " listen(s)…");
                host.main().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                }, 1500L);
            }
        }));

        container.addView(Ui.sectionHeader(host.context(), "Storage"));
        container.addView(settingRow("Clear artwork cache",
                "Removes decoded cover art from memory; playback is not interrupted",
                new Runnable() {
                    @Override
                    public void run() {
                        ImageLoader.clear();
                        host.showToast("Artwork cache cleared");
                    }
                }));

        container.addView(Ui.sectionHeader(host.context(), "About"));
        container.addView(settingRow("Version", versionName(), null));
        container.addView(settingRow("Upstream",
                "Ported from Kobo Arc 7 Freedom (YouTube Music, v2.7.0). "
                        + "Android 4 UI, TLS gateway and DASH remuxer removed.",
                null));
        container.addView(settingRow("Not affiliated",
                "Uses public YouTube InnerTube endpoints. No sign-in, no account, "
                        + "no provider restriction is bypassed.",
                null));

        android.widget.TextView footer = Ui.text(host.context(),
                "Soniq Music · built for Android 8.0+ (API 26) · target SDK 35",
                11f, Theme.TEXT_FAINT, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(16), dp(28), dp(16), dp(8));
        container.addView(footer);
    }

    private String describeSleepTimer() {
        if (host.service() == null) {
            return "Pause playback after a delay · off";
        }
        int minutes = host.service().sleepTimerMinutes();
        return minutes > 0
                ? ("Pause playback after " + minutes + " minutes")
                : "Pause playback after a delay · off";
    }

    private LinearLayout settingRow(String title, String subtitle, final Runnable onClick) {
        LinearLayout row = Ui.row(host.context());
        row.setPadding(dp(8), dp(12), dp(8), dp(12));
        row.setBackground(Ui.ripple(host.context(), Theme.SURFACE, 12f));
        row.setClickable(onClick != null);
        if (onClick != null) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    onClick.run();
                }
            });
        }
        LinearLayout info = Ui.column(host.context());
        info.addView(Ui.text(host.context(), title, 14f, Theme.TEXT, true));
        info.addView(Ui.text(host.context(), subtitle, 11f, Theme.TEXT_DIM, false));
        row.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (onClick != null) {
            row.addView(Ui.icon(host.context(), R.drawable.ic_next, Theme.TEXT_FAINT, 16));
        }
        return row;
    }

    private void chooseQuality() {
        final int[] values = {Settings.QUALITY_128, Settings.QUALITY_256};
        String[] labels = {"128 kbps · data saver", "256 kbps · higher quality"};
        int current = host.settings().targetBitrate() == Settings.QUALITY_256 ? 1 : 0;
        new AlertDialog.Builder(host.context())
                .setTitle("Stream quality")
                .setSingleChoiceItems(labels, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        host.settings().setTargetBitrate(values[which]);
                        dialog.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseClient() {
        final String[] keys = {Settings.CLIENT_AUTO,
                InnerTubeClient.ANDROID_MUSIC.key,
                InnerTubeClient.IOS.key,
                InnerTubeClient.TV_SIMPLY.key};
        String[] labels = {"Auto (recommended)",
                "Android Music 7.12",
                "iOS 21.26",
                "TV Simply 1.0"};
        String currentKey = host.settings().preferredClient();
        int selected = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(currentKey)) {
                selected = i;
            }
        }
        new AlertDialog.Builder(host.context())
                .setTitle("Preferred stream client")
                .setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        host.settings().setPreferredClient(keys[which]);
                        dialog.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseSleepTimer() {
        if (host.service() == null) {
            host.showToast("Playback service is not ready yet.");
            return;
        }
        final int[] minutes = {0, 15, 30, 45, 60, 90};
        String[] labels = {"Off", "15 minutes", "30 minutes", "45 minutes",
                "60 minutes", "90 minutes"};
        int current = host.service().sleepTimerMinutes();
        int selected = 0;
        for (int i = 0; i < minutes.length; i++) {
            if (minutes[i] == current) {
                selected = i;
            }
        }
        new AlertDialog.Builder(host.context())
                .setTitle("Sleep timer")
                .setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        host.service().setSleepTimer(minutes[which]);
                        dialog.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseEqualizer() {
        if (host.service() == null) {
            host.showToast("Playback service is not ready yet.");
            return;
        }
        final String[] presets = host.service().equalizerPresets();
        int current = host.service().equalizerPreset();
        new AlertDialog.Builder(host.context())
                .setTitle("Equalizer preset")
                .setSingleChoiceItems(presets, Math.min(current, presets.length - 1),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                host.service().setEqualizerPreset(which);
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /* ============================================================ ListenBrainz */

    private String scrobbleSummary() {
        Settings settings = host.settings();
        boolean hasToken = settings.listenBrainzToken().length() > 0;
        if (settings.scrobblingEnabled() && hasToken) {
            String user = settings.scrobbleUserName();
            return "On — listening as " + (user.length() > 0 ? user : "your ListenBrainz user");
        }
        if (hasToken) {
            return "Off — token saved, scrobbling paused";
        }
        return "Off — add your user token to scrobble";
    }

    private String tokenSummary() {
        String masked = host.settings().maskedListenBrainzToken();
        return masked.length() > 0
                ? masked + " · tap to change"
                : "Not set · get one at listenbrainz.org/settings";
    }

    private String pendingSummary() {
        ScrobbleQueue queue = ScrobbleQueue.get(host.context());
        int count = queue.pendingCount();
        String base = count == 0
                ? "Nothing queued"
                : count + (count == 1 ? " listen" : " listens") + " waiting to be submitted";
        String error = queue.lastError();
        return error.length() > 0 ? base + " · " + error : base;
    }

    private void toggleScrobbling() {
        Settings settings = host.settings();
        if (settings.listenBrainzToken().length() == 0) {
            showTokenDialog();
            return;
        }
        boolean enable = !settings.scrobblingEnabled();
        settings.setScrobblingEnabled(enable);
        if (enable) {
            ScrobbleQueue.get(host.context()).flush();
            String user = settings.scrobbleUserName();
            host.showToast("Scrobbling to ListenBrainz" 
                    + (user.length() > 0 ? " as " + user : ""));
        } else {
            host.showToast("Scrobbling paused");
        }
        refresh();
    }

    private void showTokenDialog() {
        final Settings settings = host.settings();
        final EditText input = new EditText(host.context());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("User token from listenbrainz.org/settings");
        input.setSingleLine(true);
        String existing = settings.listenBrainzToken();
        if (existing.length() > 0) {
            input.setText(existing);
            input.setSelection(existing.length());
        }
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        final AlertDialog dialog = new AlertDialog.Builder(host.context())
                .setTitle("ListenBrainz user token")
                .setMessage("Create a token at listenbrainz.org/settings and paste it here. "
                        + "It is stored only on this device and sent only to api.listenbrainz.org.")
                .setView(input)
                .setPositiveButton("Validate & save", null)
                .setNeutralButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        settings.setListenBrainzToken("");
                        settings.setScrobblingEnabled(false);
                        host.showToast("ListenBrainz token removed");
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                final Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positive.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        final String token = input.getText().toString().trim();
                        if (token.length() < 16) {
                            host.showToast("That does not look like a ListenBrainz user token.");
                            return;
                        }
                        positive.setEnabled(false);
                        positive.setText("Checking…");
                        host.io().execute(new Runnable() {
                            @Override
                            public void run() {
                                ListenBrainzApi.TokenResult resultValue = null;
                                String failureValue = null;
                                try {
                                    resultValue = new ListenBrainzApi(host.context()).validateToken(token);
                                } catch (IOException error) {
                                    failureValue = error.getMessage();
                                }
                                final ListenBrainzApi.TokenResult result = resultValue;
                                final String failure = failureValue;
                                host.main().post(new Runnable() {
                                    @Override
                                    public void run() {
                                        positive.setEnabled(true);
                                        positive.setText("Validate & save");
                                        if (result != null && result.valid) {
                                            settings.setListenBrainzToken(token);
                                            settings.setScrobbleUserName(result.userName);
                                            settings.setScrobblingEnabled(true);
                                            ScrobbleQueue.get(host.context()).flush();
                                            host.showToast("Connected to ListenBrainz"
                                                    + (result.userName.length() > 0
                                                    ? " as " + result.userName : ""));
                                            dialog.dismiss();
                                            refresh();
                                        } else if (result != null) {
                                            host.showToast("Token rejected: " + result.message);
                                        } else {
                                            host.showToast("Could not reach ListenBrainz: "
                                                    + (failure == null ? "network error" : failure));
                                        }
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
        dialog.show();
    }

    private String versionName() {
        try {
            PackageInfo info = host.context().getPackageManager()
                    .getPackageInfo(host.context().getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ") · Android "
                    + Build.VERSION.RELEASE;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "Unknown";
        }
    }
}
