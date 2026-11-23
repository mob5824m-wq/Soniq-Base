# Soniq Base

**Soniq Base** is a framework for building Android music apps. It is an Android
library (`:soniq-base`, package `com.soniq.base`) that ships everything a music
player needs — playback, queue, library, downloads, lyrics, ListenBrainz
scrobbling and a complete dark UI — behind a small **`MusicProvider`** SPI.
You implement one interface for your catalogue, and the framework does the rest.

The repository also contains a reference app,
[`examples/youtube-music`](examples/youtube-music), which is the only place
any YouTube-specific code lives. It is a complete, buildable Android app in
about 1,600 lines on top of the library — and the intended starting point if
you want to build your own app in your own repository.

![Android](https://img.shields.io/badge/minSdk-26-3DDC84?logo=android&logoColor=white)
![Target](https://img.shields.io/badge/targetSdk-35-3DDC84?logo=android&logoColor=white)
![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Dependencies](https://img.shields.io/badge/runtime%20dependencies-0-4C1)

## What the framework gives you

The framework-wide carry-forward requirements distilled from the legacy app are listed in [docs/OLD-APP-REQUIRED-FEATURES.md](docs/OLD-APP-REQUIRED-FEATURES.md).

- **Playback** through a foreground `mediaPlayback` service with
  `MediaPlayer`, `MediaSession`, lock-screen / Bluetooth / headset controls,
  a `MediaStyle` notification, audio focus with ducking, wake/Wi-Fi locks and
  unplug-to-pause.
- **Queue** with shuffle (the current song stays first), repeat off / all /
  one, an "up next" sheet and per-item removal.
- **Full-screen player** with artwork, scrubber, synced lyrics (tap a line to
  seek), like, download, queue and radio actions.
- **Library** with liked songs, saved artists, saved albums, playlists and local
  playlists — all stored on the device in private storage. No account, no cloud.
- **Offline downloads** to MediaStore (`Music/<your app's name>`) with a
  progress dialog and an offline index; offline tracks play without the provider.
- **Lyrics** from lrclib.net (synced, with plain-text fallback).
- **ListenBrainz scrobbling** (opt-in): `playing_now` updates, the official
  "half the track or four minutes" listen rule, one request per second, and an
  on-disk queue for listens made offline. The `music_service` and `origin_url`
  of each listen come from your provider.
- **Settings**: stream quality target, sleep timer, equalizer presets,
  notifications, ListenBrainz token, About.
- **Theming**: one call to rebrand the accent and secondary colours.
- **Deep links**: a single hook on the activity for your own URL scheme.

It has **zero runtime dependencies** — no AndroidX, no Kotlin, no OkHttp, no
Gson. The library uses the Android framework only (`MediaPlayer`,
`MediaSession`, `Notification.MediaStyle`, `AudioFocusRequest`, `MediaStore`,
`SharedPreferences`, `org.json`), which keeps consumer apps small and the build
free of Google Maven.

## Build your own app

The example app is deliberately tiny. This is the whole of its wiring:

```java
// 1. Describe your catalogue.
public final class MyProvider implements MusicProvider {
    @Override public String id()          { return "my-source"; }
    @Override public String displayName() { return "My Music"; }

    @Override public List<MediaItem> search(String query, String filter) throws IOException { … }
    @Override public List<MediaItem> browse(MediaItem item) throws IOException { … }
    @Override public StreamSource resolve(MediaItem track) throws IOException { … }
}

// 2. Tell the framework which provider to use.
public final class MyApp extends SoniqApp {
    @Override protected MusicProvider createProvider() { return new MyProvider(this); }
}

// 3. Launcher activity: the entire UI comes from MusicActivity.
public class MainActivity extends MusicActivity { }
```

```xml
<!-- 4. Manifest: permissions, PlaybackService and MediaButtonReceiver are
     merged in from the library; you only declare your app and activity. -->
<application android:name=".MyApp" android:label="@string/app_name"
             android:theme="@style/Theme.SoniqBase">
    <activity android:name=".MainActivity" android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

### Depending on the library

Pick whichever suits your setup:

| Option | How |
| --- | --- |
| **GitHub Packages** (published automatically from `v*` tags) | `implementation("com.soniq.base:soniq-base:<version>")` with a `maven { url = uri("https://maven.pkg.github.com/mob5824m-wq/Soniq-Base") }` repository and a GitHub token with `read:packages`. |
| **Local build** of this repo | `includeBuild("../Soniq-Base")` + `implementation("com.soniq.base:soniq-base:0.1.0")`, or `./gradlew :soniq-base:publishToMavenLocal` and `mavenLocal()`. |
| **Git submodule** | Add this repo under `libs/soniq-base`, `include(":soniq-base").projectDir = file("libs/soniq-base/soniq-base")` in your `settings.gradle.kts`, then `implementation(project(":soniq-base"))`. |
| **Copy the sources** | Copy `soniq-base/` into your repo as a module. It is ~40 Java files with no dependencies; keep the MIT notice. |

Or start from the example: copy `examples/youtube-music` to your new repo as
`app/`, point it at the library, and replace `YouTubeMusicProvider` with your
own.

### The `MusicProvider` contract

All methods are called on background threads and may block; return an empty
list for "nothing found" and throw `IOException` with a message the user can
read for failures. Implementations must be safe to call from several threads.

| Method | Required | Purpose |
| --- | --- | --- |
| `String id()` | yes | Stable identifier for diagnostics, e.g. `"youtube-music"`. |
| `String displayName()` | yes | Human-readable name shown in the UI. |
| `List<MediaItem> search(String query, String filter)` | yes | `filter` is one of `FILTER_SONGS` / `FILTER_ARTISTS` / `FILTER_ALBUMS` / `FILTER_PLAYLISTS` (treat unknown values as songs). Results may mix kinds; the framework routes each by `MediaItem.isBrowsable()`. |
| `List<MediaItem> browse(MediaItem item)` | yes | Children of an artist, album or playlist. Artists may return a mix of albums and tracks; albums and playlists return tracks. |
| `StreamSource resolve(MediaItem track)` | yes | A directly playable URL for a track: `url` plus optional `headers`, `mimeType`, `hls` flag, `bitrate`, `sourceLabel`, and corrected `title` / `author` / `album` / `artworkUrl` / `durationSec`. |
| `List<MediaItem> quickPicks()` | no | Tracks for the Home screen. Default: a `"popular music"` song search. |
| `List<MediaItem> radio(MediaItem seed)` | no | A mix seeded by a track. Default: the framework falls back to a title search. |
| `String musicService()` | no | ListenBrainz `music_service` domain (e.g. `"music.youtube.com"`). |
| `String originUrl(MediaItem track)` | no | Per-track ListenBrainz `origin_url`. |
| `String tagline()` | no | One line shown under the Home heading. |

**`MediaItem`** is the universal model for tracks, artists, albums and
playlists: `id`, `kind` (`KIND_TRACK` / `KIND_ARTIST` / `KIND_ALBUM` /
`KIND_PLAYLIST`), `title`, `author`, `album`, `durationText`, `durationSec`,
`thumbnailUrl`, `isLive`, and `localUri` for offline copies. A track is
anything with an `id` that is not browsable. Ids are opaque to the framework
and must be stable: the library, downloads and play queue persist
`kind + ":" + id`.

**`StreamSource`** is what `resolve()` returns. The player needs `url`; set
`headers` when the host requires them (note that `MediaPlayer` cannot send
headers to an HLS manifest, so `hls = true` streams play without them), set
`hls = true` for HLS manifests, and fill in the corrected metadata fields when
the player response knows better than the search result did.

### Optional customisation points

- **Deep links** — add `VIEW` intent filters to your activity and override
  `protected void onHandleViewIntent(Uri data)` in your `MusicActivity`
  subclass. The example parses `youtu.be` / `watch?v=` / `?list=` links there.
- **Colours** — call `Theme.customize(0xFF00A86B)` or
  `Theme.customize(accent, secondary)` in `MyApp.onCreate()` (after
  `super.onCreate()`), before any view is built.
- **Download folder** — `new Downloads(context, provider, "Music/My App")`
  if you build your own `Downloads`; by default it is `Music/<app label>`.
- **App-level settings** — keep your own `SharedPreferences` (the example
  stores its preferred InnerTube client in `"soniq_youtube_settings"`); the
  framework never reads them.

## Build this repository

```bash
./gradlew :soniq-base:assembleDebug                  # AAR: soniq-base/build/outputs/aar/
./gradlew :examples:youtube-music:assembleDebug      # example APK
./gradlew :soniq-base:testDebugUnitTest              # JVM unit tests
./gradlew :soniq-base:publishToMavenLocal            # try the library from another repo
```

Requires JDK 17 and an Android SDK with platform 35. The Gradle wrapper pins
Gradle 8.9 and AGP 8.7.3. Module versions are derived from git — the latest
`vX.Y.Z` tag, or `X.Y.Z-SNAPSHOT` on later commits — so there is no version
number to bump; see [Releases](#releases).

No APK or AAR is committed to the repository. Build with the commands above, or
download the `soniq-youtube-music-example-debug` / `soniq-base-debug-aar`
artifacts from this repository's GitHub Actions "Build" workflow.

### No Android SDK? Quick compile check

```bash
ANDROID_HOME=~/Android/Sdk python3 scripts/compile-check.py --tests
# no javac on PATH? use the Eclipse compiler jar:
python3 scripts/compile-check.py --ecj /path/to/ecj-4.30.jar --java /path/to/java
```

This compiles every module against a single `android.jar` and (with `--tests`)
the library's unit tests, without Gradle.

## Releases

A release is a `vX.Y.Z` tag on `main` plus a `CHANGELOG.md` section — nothing
else. Pushing the tag runs `.github/workflows/release.yml`, which

1. builds the library and the example app (release variants) and runs the unit
   tests,
2. publishes `com.soniq.base:soniq-base:X.Y.Z` to GitHub Packages, and
3. creates the GitHub Release with the changelog section as notes and the AAR,
   sources JAR and example APK attached (`-rc.1`-style tags become pre-releases).

The easy way, from a clean checkout of `main`:

```bash
python3 scripts/release.py 0.2.0            # validate and show the plan (dry run)
python3 scripts/release.py 0.2.0 --yes      # commit the changelog, tag v0.2.0, push both
```

Add entries under `## [Unreleased]` in `CHANGELOG.md` as you go (or pass
`--notes "…"`); the script moves them into the new version's section, updates
the compare links, refuses to run on a dirty tree, a stale `main` or an
existing tag, and prints the Actions and release URLs. Needs Python 3.8+.

- **Protected `main`?** `python3 scripts/release.py 0.2.0 --pr` opens a
  "Release 0.2.0" pull request with the changelog; once it is merged, run
  `python3 scripts/release.py 0.2.0 --tag-only --yes` on an up-to-date `main`.
- **Prefer the GitHub UI?** "Draft a new release" → type a new tag `vX.Y.Z` →
  publish. The same workflow runs; if the changelog has no section for it, the
  release gets auto-generated notes and the assets are attached to it.

## The example app: YouTube Music

`examples/youtube-music` is a standalone YouTube Music client built on the
library: search (songs, artists, albums, playlists), browse, audio-only
playback through the public InnerTube endpoints, radio, deep links and
ListenBrainz scrobbling with `music.youtube.com` origins. It is **not** the
product of this repository — it demonstrates the SPI and seeds your own app.

- **No sign-in.** Public endpoints only; age-restricted, members-only or
  region-locked videos refuse to play, and the reason is shown as text.
- **No stream-signature deciphering.** If Google changes the player response
  enough that audio URLs can no longer be read directly, playback breaks until
  `YouTubeMusicApi` is updated.
- **No bypass of age/member/region restrictions.** This is intentional.

## Project layout

```
soniq-base/                        the framework (Android library, com.soniq.base)
  src/main/java/com/soniq/base/
    SoniqApp.java                  Application base: createProvider()
    provider/MusicProvider.java    the SPI an app implements
    model/MediaItem.java           track / artist / album / playlist model + JSON
    model/StreamSource.java        resolved stream: url, headers, mime, corrected meta
    data/                          Library, Settings, Downloads, LyricsApi/Parser,
                                   ImageLoader, ListenBrainzApi, ScrobbleQueue,
                                   ScrobblePolicy, Scrobble
    playback/                      PlaybackService, PlaybackQueue, PlaybackPosition,
                                   MediaButtonReceiver
    ui/                            MusicActivity, Host, Theme, Ui, views/, screens/
  src/main/res/                    strings (soniq_ prefix), colours, Theme.SoniqBase
  src/test/                        JVM unit tests
examples/youtube-music/            reference app (com.soniq.example.youtubemusic)
  YouTubeMusicProvider.java        MusicProvider implementation — the only class
                                   the framework sees
  YouTubeMusicApi.java             search / browse / stream resolution + parsing
  InnerTubeClient.java             HTTP, client table, visitor data
  ExampleApp.java, MainActivity.java
docs/PORTING.md                    history: Kobo Arc 7 Freedom → this framework
CHANGELOG.md                       Keep a Changelog; [Unreleased] feeds the next release
scripts/release.py                 changelog → commit → vX.Y.Z tag → push (see Releases)
scripts/compile-check.py           android.jar-only compile check
.github/workflows/build.yml        build + tests on every push / pull request
.github/workflows/release.yml      on v* tags: build, publish to GitHub Packages, GitHub Release
```

The UI is built programmatically — no XML layouts — so the library has no
AndroidX / AppCompat dependency. For agent instructions see
[agent.md](agent.md).

## History

Soniq Base grew out of a clean-room port of the YouTube Music app in
[Kobo Arc 7 Freedom](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom) to
modern Android (minSdk 26, no Android 4 compatibility stack, new UI). The
player, queue and service logic from that port now live in the library; the
YouTube-specific parts became the example. The class-by-class map is in
[docs/PORTING.md](docs/PORTING.md).

## Legal

MIT License. The example app is an unofficial client; it is not affiliated
with Google or YouTube. "YouTube" and "YouTube Music" are trademarks of Google
LLC. Use it only with content you have the right to access, and respect the
platform's terms of service.
