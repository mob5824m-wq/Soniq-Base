# Porting notes: Kobo Arc 7 Freedom → Soniq Music

Upstream: [`mob5824m-wq/Kobo-Arc-7-freedom`](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom)
at `main` (v2.7.0 / build 270). The YouTube Music app there is a split app
(`split-apps/ytmusic`) that shares the suite's `ca.arc7freedom.app.*` engine
classes, `Theme.Holo` UI and an Android 4.0.3–4.2.2 compatibility stack.

Soniq Music is **not** a copy of those classes. It keeps the network/queue
behaviour that still makes sense and replaces everything that existed only to
make Android 4 survive TLS, DASH and modern HTTP.

## Class-by-class

| Upstream | Soniq replacement | Why |
| --- | --- | --- |
| `YouTubeMusicActivity` | `ui/MainActivity` + `ui/screens/*` | Holo-era activity with ListFragments and a slide-out drawer replaced by one edge-to-edge activity with programmatic tabs, mini player and full-screen sheets. |
| `Arc7AudioService` | `playback/PlaybackService` | Same responsibilities (queue, focus, notification, lyrics hook, sleep timer, EQ) rebuilt on `MediaSession`, `AudioFocusRequest`, `MediaStyle` and a `mediaPlayback` foreground service type. |
| `MorpheEngine` | `data/YouTubeMusicApi` + `data/InnerTubeClient` | Client-spoof table, visitor data, pacing and `playabilityStatus` checks kept; the JS signature player (`MorpheEngine`'s decipher) is gone. |
| `NetworkCertManager` / bundled CA bundle | — | Modern Android negotiates TLS 1.3 with the platform trust store. |
| `OwnTlsGateway` / TLS 1.3 relay on `127.0.0.1` | — | Only needed because Android 4's TLS stack could not reach Google. |
| `MorpheStreamServer` / `MorpheRemuxer` / DASH range proxy | — | `MediaPlayer` plays `audio/mp4` / `audio/webm` URLs directly; no local HTTP proxy, no MP4 remux. |
| `OfflineMusicLibrary` | `data/Downloads` | Downloads go to MediaStore (`Music/Soniq Music`) on API 29+ and the app's external music directory on 26–28, with `.part` staging and an atomic index. |
| `RootShell` / root helpers | — | Not used by the music player. |
| Arc7 glass/skin UI, widgets, diagnostics, support activities | — | Device-specific Chrome-less UI; replaced by the new dark Material-ish UI. |
| `ca.arc7freedom.ytmusic` package | `com.soniq.music` | New application id, new namespace, no shared prefix with the Arc7 suite. |
| Piped / Invidious cloud fallback | — | Removed; it sends listening history to third-party servers. |

## What was kept

- InnerTube client table with `visitorData` per client and 1.2 s request pacing,
  backing off for 60 s after HTTP 429/503.
- `playabilityStatus` checking and returned-`videoId` validation before a
  stream URL is trusted.
- Audio-only format preference: AAC → Opus → muxed → HLS.
- Queue with shuffle that keeps the current item first, repeat off/all/one.
- Audio focus with ducking and resume-after-transient-loss.
- Headphone / Bluetooth unplug pause (`ACTION_AUDIO_BECOMING_NOISY`).
- Synced lyrics from lrclib.net with plain-text fallback.
- Sleep timer and the system equalizer presets.

## What was dropped on purpose

- **Sign-in and bot-check bypass.** No cookies, OAuth, PO tokens or signature
  deciphering. If YouTube refuses a request the app shows the reason.
- **Native libraries.** ARMv7-only `.so` files and DEX 035 are gone.
- **Kobo-specific hardware support.** Volume-key routing, GPIO, SD card
  detection and the glass skin are not applicable to phones.
- **Account sync and history.** Everything is local to the device.

## Additions not present upstream

- **ListenBrainz scrobbling** (`data/ListenBrainzApi`, `ScrobbleQueue`,
  `ScrobblePolicy`): opt-in token auth, `playing_now` updates, ListenBrainz'
  half-or-four-minutes listen rule, and an on-disk queue for offline listens
  flushed as `import` batches at one request per second. The Kobo app had no
  scrobbling at all.

## Behavioural differences

| Area | Kobo Arc 7 (upstream) | Soniq Music |
| --- | --- | --- |
| Minimum OS | Android 4.0.3 (API 15) | Android 8.0 (API 26) |
| Target | API 19, Dalvik | API 35, ART |
| UI | Holo + glass skin, XML fragments | Programmatic dark UI, edge-to-edge |
| Dependencies | none (framework only) | none (framework only) — unchanged |
| Stream access | TLS relay + DASH proxy + remuxer | direct `audio/mp4` / `audio/webm` |
| Sign-in | none | none |
| Offline | app-private M4A files | MediaStore downloads with index |
| Lyrics | lrclib.net | lrclib.net (synced + plain fallback) |
| EQ | 5-band via `android.media.audiofx` | same framework equalizer |
| Package | `ca.arc7freedom.ytmusic` | `com.soniq.music` |

## Verification status

The port was compiled with ECJ 4.30 against the Android 35 platform jar and
the JVM unit tests were run with a stub JUnit runtime, because the sandbox used
to author it has no Android SDK, Gradle distribution or Maven access. A full
`gradle assembleDebug` was **not** executed here. CI (`.github/workflows/build.yml`)
runs the real build on every pull request; treat its result as authoritative.
