# Porting notes: Kobo Arc 7 Freedom → Soniq Base

> **Where things live now.** The port described below was originally a single
> `:app` module (`com.soniq.music`, "Soniq Music"). It has since been split:
> the generic player, queue, library, downloads, lyrics, scrobbling and UI
> became the **`soniq-base` library** (`com.soniq.base`), and the
> YouTube-specific code became the **`examples/youtube-music`** reference app
> (`com.soniq.example.youtubemusic`). Paths in the tables are given for the
> current layout; "Soniq Music" in prose refers to the app as a whole, which
> is now the example app. See "The framework split" at the end.

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
| `YouTubeMusicActivity` | `soniq-base` `ui/MusicActivity` + `ui/screens/*`; deep links in the example's `MainActivity` | Holo-era activity with ListFragments and a slide-out drawer replaced by one edge-to-edge activity with programmatic tabs, mini player and full-screen sheets. |
| `Arc7AudioService` | `soniq-base` `playback/PlaybackService` | Same responsibilities (queue, focus, notification, lyrics hook, sleep timer, EQ) rebuilt on `MediaSession`, `AudioFocusRequest`, `MediaStyle` and a `mediaPlayback` foreground service type. |
| `MorpheEngine` | example `YouTubeMusicApi` + `InnerTubeClient` | Client-spoof table, visitor data, pacing and `playabilityStatus` checks kept; the JS signature player (`MorpheEngine`'s decipher) is gone. |
| `NetworkCertManager` / bundled CA bundle | — | Modern Android negotiates TLS 1.3 with the platform trust store. |
| `OwnTlsGateway` / TLS 1.3 relay on `127.0.0.1` | — | Only needed because Android 4's TLS stack could not reach Google. |
| `MorpheStreamServer` / `MorpheRemuxer` / DASH range proxy | — | `MediaPlayer` plays `audio/mp4` / `audio/webm` URLs directly; no local HTTP proxy, no MP4 remux. |
| `OfflineMusicLibrary` | `soniq-base` `data/Downloads` | Downloads go to MediaStore (`Music/<app label>`, e.g. `Music/Soniq Music`) on API 29+ and the app's external music directory on 26–28, with `.part` staging and an atomic index. |
| `RootShell` / root helpers | — | Not used by the music player. |
| Arc7 glass/skin UI, widgets, diagnostics, support activities | — | Device-specific Chrome-less UI; replaced by the new dark Material-ish UI. |
| `ca.arc7freedom.ytmusic` package | `com.soniq.base` (library) / `com.soniq.example.youtubemusic` (example) | New namespace for the framework and a new application id for the example; no shared prefix with the Arc7 suite. |
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

- **ListenBrainz scrobbling** (`soniq-base` `data/ListenBrainzApi`, `ScrobbleQueue`,
  `ScrobblePolicy`): opt-in token auth, `playing_now` updates, ListenBrainz'
  half-or-four-minutes listen rule, and an on-disk queue for offline listens
  flushed as `import` batches at one request per second. The Kobo app had no
  scrobbling at all.

## Upstream changes reviewed after the port

Upstream `main` is re-checked periodically; the port started from `382daa0`.

| Upstream commit | What it did | In Soniq Music |
| --- | --- | --- |
| `6e7b566` "YouTube Music seek bar stuck — add wall-clock position fallback" | `Arc7AudioService.getPositionMs()` anchored the position at play/seek time and advanced it by wall clock when Android 4.2 Stagefright reported a stuck `0` for proxied streams | Ported as the library's `playback/PlaybackPosition` (clean-room, unit-tested): a positive player reading is authoritative, zero/unavailable readings fall back to the estimate, pre-seek readings are ignored until the seek is confirmed, pause freezes the estimate. Wired into `PlaybackService.getPosition()`/`seekTo()`. |
| `8585e14` "track YouTube video preparation and timeline" | `YouTubeActivity` + new `VideoPositionTracker` for the YouTube *video* app's `VideoView` | Not applicable — the video app is not part of this port. Its "ignore stale zeros / seek-pending" ideas were folded into `PlaybackPosition`. |
| `ef24020` audio home-screen widget rework | Arc7 glass `RemoteViews` widget | Not applicable — the port uses a `MediaStyle` media notification instead. |
| `chore: update pre-built signed APKs` (several) | Rebuilt release binaries | Not applicable — no binaries are committed here. |

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
| Package | `ca.arc7freedom.ytmusic` | `com.soniq.base` (library) · `com.soniq.example.youtubemusic` (example app) |

## The framework split (single app → library + example)

The owner wants Soniq Base to be **only a framework**, with the real music app
living in a separate repository. The split keeps every behaviour above and
changes only where the code lives and what depends on what.

| Was (`app/`, `com.soniq.music`) | Now | Notes |
| --- | --- | --- |
| `data/MediaItem` with YouTube fields (`videoId`, `browseId`, `playlistId`, `channelId`, `isMusicTrack`, `collectionType`) | `soniq-base` `model/MediaItem` with generic `id` + `kind` | One opaque, stable id per item; `kind` is `track` / `artist` / `album` / `playlist`. Persisted JSON keys are `id, kind, t, a, al, d, sec, th, live, local`. |
| `data/ResolvedTrack` (`streamUrl`, `clientKey`) | `soniq-base` `model/StreamSource` (`url`, `sourceLabel`, `headers`, `mimeType`, `hls`, `bitrate`, corrected metadata) | |
| `YouTubeMusicApi` called directly from `PlaybackService`, `HomeScreen`, `SearchScreen`, `CollectionSheet`, `ScrobbleQueue`, `Downloads` | `soniq-base` `provider/MusicProvider` SPI; the example's `YouTubeMusicProvider` is the only class that talks to `YouTubeMusicApi` | Required: `id`, `displayName`, `search(query, filter)`, `browse(item)`, `resolve(track)`. Optional: `quickPicks()`, `radio(seed)`, `tagline()`, `musicService()`, `originUrl(track)`. |
| `MainActivity` (concrete, with YouTube deep links) | `soniq-base` `ui/MusicActivity` (public base, `onHandleViewIntent(Uri)` hook) + example `MainActivity` | |
| `Application` with no base class | `soniq-base` `SoniqApp` (`createProvider()`, `providerOf(Context)`) | Service, scrobble queue and downloads all read the provider through `providerOf`. |
| `Settings.preferredClient()` (InnerTube client picker) in the framework settings screen | Example-owned `SharedPreferences("soniq_youtube_settings")` on `YouTubeMusicApi` | Provider-specific settings never live in the library. |
| `Theme` fixed to the red/violet palette | `Theme.customize(accent[, secondary])` / `reset()` | Apps rebrand in `Application.onCreate()`. |
| Resources `app_name`, `ic_*`, `Theme.SoniqMusic` | Library: `soniq_*` strings, `soniq_ic_*` drawables, `Theme.SoniqBase`; example: its own `app_name` + launcher icon | The library uses the host app's label for the notification, downloads folder (`Music/<app label>`) and About footer. |
| Intent actions `com.soniq.music.action.*` | `com.soniq.base.action.*` | |
| "Searching YouTube Music…", "Search the public YouTube Music catalogue" | `host.provider().displayName()` | |
| Home quick picks: hard-coded `"popular music official audio"` search | `MusicProvider.quickPicks()`; the YouTube-tuned query is the example's override | |
| `Gradle: :app` | `:soniq-base` (Android library, `maven-publish` to GitHub Packages on `v*` tags) + `:examples:youtube-music` (application depending on `project(":soniq-base")`) | |
| `YouTubeMusicApi.findChannelId` (channel ids for artists) | Removed; `MediaItem.author` carries the artist name and artist items are identified by their browse id | |

ListenBrainz scrobbling stayed a framework feature; the per-listen
`music_service` ("music.youtube.com") and `origin_url` now come from the
provider instead of being derived from a `videoId` inside the library.

## Verification status

Both the original port and the framework split were first compiled with ECJ
4.30 against the Android 35 platform jar, with the JVM unit tests run under a
stub JUnit runtime, because the sandbox used to author them has no Android SDK,
Gradle distribution or Maven access. That check cannot see resources: it was
CI's first green build (after the framework split, PR #2) that caught the
icons being tinted with AppCompat's `?attr/colorControlNormal` instead of the
framework's `?android:attr/colorControlNormal`.

Before PR #2, CI had never passed in this repository — `android-actions/setup-android@v3`
failed on every run before Gradle started. The workflow now uses `setup-android@v4`
with platform 35 / build-tools 35.0.0 installed explicitly, runs the Gradle
wrapper (`./gradlew`) and builds both modules plus the unit tests on every
pull request; it is the authoritative verification.
