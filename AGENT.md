# AGENT.md — Soniq Base

Instructions for AI coding agents working in this repository.

## What this repo is

**Soniq Base** is an **Android library** for building music apps
(`:soniq-base`, package `com.soniq.base`, `minSdk 26` → `targetSdk 35`). It
ships playback, queue, library, downloads, lyrics, ListenBrainz scrobbling and
a complete programmatic UI behind a small SPI, `provider/MusicProvider`. An
app implements that one interface and extends `SoniqApp` + `MusicActivity`.

`examples/youtube-music` is a **reference app** (`com.soniq.example.youtubemusic`)
and the **only** place YouTube-specific code may live. It is a complete,
buildable app on top of the library and the seed for apps in other repos.
The owner builds the real music app in a separate repository; this repo is
the framework.

Both modules have **zero runtime dependencies**: framework `MediaPlayer`,
`MediaSession`, `Notification.MediaStyle`, `AudioFocusRequest`, `MediaStore`,
`SharedPreferences` / `org.json` only — no AndroidX, Compose, ExoPlayer,
OkHttp, Gson or Kotlin.

## Non-negotiable product rules

- **The library is provider-agnostic.** Nothing under `soniq-base/` may
  mention a specific music service in code, strings, resource names or
  default behaviour. Anything source-specific (API clients, parsers, URL
  schemes, client tables, provider-specific settings) belongs in
  `examples/youtube-music/` or in the consumer's repo. User-visible text in
  the library uses `host.provider().displayName()` / the app label, never a
  hard-coded product name. Resource names are prefixed `soniq_`, intent
  actions are `com.soniq.base.action.*`.
- **Keep the SPI small and stable.** `MusicProvider`'s required surface is
  `id`, `displayName`, `search`, `browse`, `resolve`. New capabilities are
  added as `default` methods so existing providers keep compiling. Model
  classes (`MediaItem`, `StreamSource`) persist to JSON — never rename a key
  without a migration; add new keys with defaults.
- **Modern Android only.** Never reintroduce Android 4 / API 15–17 shims: no
  DEX 035, no `Theme.Holo`, no TLS gateway, no CA bundle overrides, no local
  DASH range proxy, no MP4 remuxer, no legacy AAC/TS probes.
- **No account, no bypass** (example app). Public InnerTube endpoints only.
  Never add cookies, OAuth, bot-check solving, signature deciphering, or a
  "sign in to continue" workaround. Provider refusals are surfaced to the user
  as text, not hidden.
- **Zero runtime dependencies.** Do not add AndroidX, Compose, ExoPlayer,
  OkHttp, Gson or Kotlin unless the owner explicitly approves a stack change.
  If a change truly needs a library, open an issue first and record the reason
  in `README.md`.
- **No bundled binaries.** No checked-in APKs, AARs, native libraries,
  keystores or third-party JARs. The Gradle wrapper is the only binary in the
  tree.
- **Versions come from git tags.** The root `build.gradle.kts` derives every
  module's version from the latest `vX.Y.Z` tag (`X.Y.Z` on the tagged commit,
  `X.Y.Z-SNAPSHOT` after it). Never hard-code a version in a build file, and
  never pass `-PsoniqVersion` outside a local experiment. A release is
  `python3 scripts/release.py X.Y.Z --yes` (changelog → commit → tag → push);
  do not create tags by hand, do not tag a dirty or stale tree, and add a
  `CHANGELOG.md` entry under `[Unreleased]` for every user-visible change —
  the release script moves it into the version's section and the Release
  workflow uses it as the GitHub Release notes.
- **Position reporting.** `PlaybackService.getPosition()` must go through
  `PlaybackPosition`; never read `MediaPlayer.getCurrentPosition()` directly
  for UI/MediaSession (it can read 0 right after start or the pre-seek value
  mid-seek). Keep that class Android-free so it stays unit-tested.
- **Parser isolation** (example). All InnerTube/YouTube JSON parsing lives in
  `YouTubeMusicApi`. Keep it defensive: every field is optional, every array
  can be missing, and a malformed response must degrade to an empty list or an
  `IOException`, never a crash.
- **Thread discipline.** Provider calls, network, downloads, artwork decoding
  and JSON parsing run on `ExecutorService` threads. `MediaPlayer`, views,
  `Toast` and service state changes run on the main thread
  (`Handler(Looper.getMainLooper())`). Never block the main thread with disk
  or network work.
- **Foreground service compliance.** `PlaybackService` declares
  `foregroundServiceType="mediaPlayback"`, creates its notification channel
  before `startForeground`, uses immutable `PendingIntent`s and requests audio
  focus with `AudioFocusRequest`. Keep all four true.
- **Local-first data.** Liked songs, artists, collections, playlists and the
  download index live in private `SharedPreferences` JSON. No cloud sync, no
  account, no telemetry.
- **ListenBrainz scrobbling is opt-in and privacy-sensitive.** The user token
  lives in private `SharedPreferences` (`Settings.listenBrainzToken()`); never
  log it, never put it in a URL or analytics, and mask it in the UI
  (`Settings.maskedListenBrainzToken()`). Keep the official listen rule
  (`ScrobblePolicy`: half the track or four minutes, whichever is lower),
  send `playing_now` without `listened_at`, `single`/`import` with the
  playback start time, and never exceed one API call per second
  (`ScrobbleQueue` spaces requests and honours 429 `Retry-After`). All
  submission goes through `ScrobbleQueue`; do not call `ListenBrainzApi` from
  UI code except for `validateToken`. `music_service` / `origin_url` come
  from `MusicProvider`, never hard-coded.

## Build & verify

```bash
./gradlew :soniq-base:assembleDebug                        # library AAR
./gradlew :examples:youtube-music:assembleDebug            # example APK
./gradlew :soniq-base:testDebugUnitTest                    # pure-JVM tests
python3 scripts/compile-check.py --tests                   # android.jar-only sanity check
```

`scripts/compile-check.py` is the fast check for environments without the
Android Gradle plugin. It generates a temporary `R.java` stub per module from
its resource directory and compiles all modules' Java sources against one
`android.jar` (plus the library's tests with `--tests`):

```bash
ANDROID_HOME=$HOME/Android/Sdk python3 scripts/compile-check.py --tests --junit /path/to/junit-4.13.2.jar
# or, with no javac on PATH:
python3 scripts/compile-check.py --ecj /path/to/ecj-4.30.jar --java /path/to/java
```

CI runs the Gradle build for both modules (including the example's R8 release
build) and the unit tests on every push to `main` and every pull request
(`.github/workflows/build.yml`). Pushing a `vX.Y.Z` tag runs
`.github/workflows/release.yml`: build + tests, publish `com.soniq.base:soniq-base`
to GitHub Packages, create the GitHub Release with the changelog as notes and
the AAR / sources / example APK attached.

```bash
python3 scripts/release.py 0.2.0          # dry run: validate + show the plan
python3 scripts/release.py 0.2.0 --yes    # commit changelog on main, tag, push
python3 scripts/release.py 0.2.0 --pr     # or: open a release PR (then --tag-only after merge)
```

## Repository map

```
soniq-base/src/main/java/com/soniq/base/
  SoniqApp.java             Application base: createProvider(), providerOf(Context)
  provider/
    MusicProvider.java      THE SPI: id, displayName, search, browse, resolve +
                            optional quickPicks, radio, musicService, originUrl
  model/
    MediaItem.java          track / artist / album / playlist model + JSON
    StreamSource.java       resolved stream: url, headers, mime, hls, corrected meta
  data/
    Library.java            liked / artists / collections / local playlists / recent
    Settings.java           quality, notification-prompt state, ListenBrainz token
    Downloads.java          MediaStore downloads + offline index (Music/<app label>)
    ListenBrainzApi.java    validate-token / submit-listens HTTP client
    ScrobbleQueue.java      listen detection, playing_now, offline listen queue
    ScrobblePolicy.java     listen threshold + metadata cleaning (pure, unit-tested)
    Scrobble.java           one persisted listen
    ListeningRecapApi.java  read-only ListenBrainz statistics client (year recap)
    ListeningRecap.java     year-in-review model: totals, top lists, chart (pure, unit-tested)
    LyricsApi.java          lrclib.net client
    LyricsParser.java       LRC parser (pure JVM, unit-tested)
    ImageLoader.java        bounded in-memory artwork cache
  playback/
    PlaybackService.java    foreground service, MediaPlayer, MediaSession, notification,
                            audio focus, wake/wifi locks, sleep timer, equalizer
    PlaybackQueue.java      queue / shuffle / repeat (pure JVM, unit-tested)
    PlaybackPosition.java   smooths MediaPlayer position (pure JVM)
    MediaButtonReceiver.java headset / Bluetooth buttons
  ui/
    MusicActivity.java      public base activity: tabs, service binding, dialogs,
                            onHandleViewIntent(Uri) hook, implements Host
    Host.java               screen ↔ activity contract
    Theme.java / Ui.java    palette (Theme.customize) + programmatic widget helpers
    NetworkImage.java       artwork view with outline-rounded corners
    Tab.java                bottom navigation destinations
    views/                  TrackRow, Chip, BottomNav, MiniPlayer, LyricsView
    screens/                Home, Search, Library, Settings, Player, Queue, Collection,
                            Recap (Wrapped-style "Year in review")
soniq-base/src/test/java/   JUnit tests for queue, position, lyrics, scrobble policy,
                            model and filename logic
examples/youtube-music/src/main/java/com/soniq/example/youtubemusic/
  YouTubeMusicProvider.java MusicProvider implementation — the only class the
                            framework sees (maps filters → search modes, etc.)
  YouTubeMusicApi.java      search, browse, audio-only stream resolution, parsing
  InnerTubeClient.java      HTTP, client table, visitor data, player/search/browse
  ExampleApp.java           extends SoniqApp, returns a YouTubeMusicProvider
  MainActivity.java         extends MusicActivity; parses youtu.be / watch links
docs/PORTING.md             history: upstream class → replacement mapping
CHANGELOG.md                Keep a Changelog; [Unreleased] is the next release
scripts/release.py          changelog → commit → vX.Y.Z tag → push
scripts/compile-check.py    android.jar-only compile check
.github/workflows/build.yml      build + tests on push / pull request
.github/workflows/release.yml    v* tags: publish + GitHub Release
```

There are no XML layouts by design: the UI is built programmatically so the
library has no AndroidX / AppCompat dependency. Add new screens in
`ui/screens/` and new widgets in `ui/views/`; keep the `Host` contract small.

## How to add a feature

Decide first **where** it belongs:

- Source-specific (an API, a parser, a URL scheme, a provider setting) →
  `examples/youtube-music/`, or nowhere here if it belongs in the owner's app.
- Generic player/library/UI behaviour → `soniq-base/`.

Then, for library work:

1. **Model first.** If the feature needs a new field, add it to `MediaItem`
   (or `StreamSource`) with a default and extend `toJson()` / `fromJson()`;
   the library persists that shape and old JSON must still load.
2. **SPI second.** If the feature needs something only a provider can supply,
   add a `default` method to `MusicProvider` with a sensible generic fallback
   (see `quickPicks()` / `radio()`). Never add a required method.
3. **Service third.** Anything that affects playback goes through
   `PlaybackService`; keep queue mutations on the main thread and update
   `MediaSession` metadata/state after every change.
4. **UI last.** Screens read from `Host`; never reach into the service from a
   background thread. Rebuild with `Screen.refresh()` rather than mutating
   views from a worker.
5. **Test the pure part.** Queue, position, lyrics, identity and filename
   logic have JVM tests. Add a test for any new pure function; do not add
   Android-only tests that require `org.json` on the JVM (the android.jar
   stubs throw).

## PR checklist

- [ ] `./gradlew :soniq-base:assembleDebug :examples:youtube-music:assembleDebug`
      succeeds.
- [ ] `./gradlew :soniq-base:testDebugUnitTest` succeeds.
- [ ] No music-service-specific code, strings or defaults leaked into
      `soniq-base/`.
- [ ] No new required method on `MusicProvider`; persisted JSON keys unchanged
      (or migrated).
- [ ] No new runtime dependency, no new binary, no new permission unless the
      PR explains why.
- [ ] No Android 4 / Arc7 / Kobo code path reintroduced.
- [ ] Network and disk work stay off the main thread.
- [ ] Foreground-service, notification-channel and immutable-PendingIntent
      rules still hold.
- [ ] `CHANGELOG.md` has an entry under `[Unreleased]` for user-visible
      changes.
- [ ] `README.md` / `docs/PORTING.md` updated if the architecture or the
      published surface changed.



## Music apps

- [ ] `REQUIRED-FEATURES.md` In music apps all of these features must be implemented
