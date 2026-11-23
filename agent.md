# agent.md — Soniq Music

Instructions for AI coding agents working in this repository.

## What this repo is

**Soniq Music** is a standalone YouTube Music client for modern Android:
`minSdk 26` (Android 8.0) through `targetSdk 35` (Android 15). It is a port of
the YouTube Music app that ships inside
[Kobo Arc 7 Freedom v2.7.0](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom),
rebuilt as its own Gradle project with the Kobo/Arc7 suite UI and every
Android 4 compatibility workaround removed.

The app is a **single `:app` module** with **zero runtime dependencies**. It
uses framework `MediaPlayer`, `MediaSession`, `Notification.MediaStyle`,
`AudioFocusRequest`, `MediaStore` and `SharedPreferences`/`org.json` only.
That is deliberate: it keeps the build reproducible without Google Maven and
keeps the APK small enough for old-but-supported devices.

## Non-negotiable product rules

- **Modern Android only.** Never reintroduce Android 4 / API 15–17 shims:
  no DEX 035, no `Theme.Holo`, no TLS gateway, no CA bundle overrides, no
  local DASH range proxy, no MP4 remuxer, no legacy AAC/TS probes.
- **No account, no bypass.** The app uses public InnerTube endpoints only.
  Never add cookies, OAuth, bot-check solving, signature deciphering, or a
  "sign in to continue" workaround. Provider refusals are surfaced to the user
  as text, not hidden.
- **Zero runtime dependencies.** Do not add AndroidX, Compose, ExoPlayer,
  OkHttp, Gson or Kotlin unless the owner explicitly approves a stack change.
  If a change truly needs a library, open an issue first and record the reason
  in `README.md`.
- **No bundled binaries.** No checked-in APKs, native libraries, keystores or
  third-party JARs. The Gradle wrapper is the only binary in the tree.
- **Position reporting.** `PlaybackService.getPosition()` must go through
  `PlaybackPosition`; never read `MediaPlayer.getCurrentPosition()` directly
  for UI/MediaSession (it can read 0 right after start or the pre-seek value
  mid-seek). Keep that class Android-free so it stays unit-tested.
- **Parser isolation.** All InnerTube/YouTube JSON parsing lives in
  `data/YouTubeMusicApi.java`. Keep it defensive: every field is optional,
  every array can be missing, and a malformed response must degrade to an
  empty list or an `IOException`, never a crash.
- **Thread discipline.** Network, downloads, artwork decoding and JSON parsing
  run on `ExecutorService` threads. `MediaPlayer`, views, `Toast` and service
  state changes run on the main thread (`Handler(Looper.getMainLooper())`).
  Never block the main thread with disk or network work.
- **Foreground service compliance.** `PlaybackService` declares
  `foregroundServiceType="mediaPlayback"`, creates its notification channel
  before `startForeground`, uses immutable `PendingIntent`s and requests audio
  focus with `AudioFocusRequest`. Keep all four true.
- **Local-first data.** Liked songs, artists, collections, playlists and the
  download index live in private `SharedPreferences` JSON. No cloud sync, no
  account, no telemetry.
- **Version consistency.** `versionCode` / `versionName` in
  `app/build.gradle.kts` and the About screen must agree. Bump them together.
- **ListenBrainz scrobbling is opt-in and privacy-sensitive.** The user token
  lives in private `SharedPreferences` (`Settings.listenBrainzToken()`); never
  log it, never put it in a URL or analytics, and mask it in the UI
  (`Settings.maskedListenBrainzToken()`). Keep the official listen rule
  (`ScrobblePolicy`: half the track or four minutes, whichever is lower),
  send `playing_now` without `listened_at`, `single`/`import` with the
  playback start time, and never exceed one API call per second
  (`ScrobbleQueue` spaces requests and honours 429 `Retry-After`). All
  submission goes through `ScrobbleQueue`; do not call `ListenBrainzApi` from
  UI code except for `validateToken`.

## Build & verify

```bash
./gradlew assembleDebug          # debug APK: app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # pure-JVM queue/lyrics/model tests
./gradlew lintDebug              # Android Lint
python3 scripts/compile-check.py # javac/android.jar-only sanity check
```

`scripts/compile-check.py` is the fast check for environments without the
Android Gradle plugin. It generates a temporary `R.java` stub from the
resource directory and compiles every Java source against one `android.jar`:

```bash
ANDROID_HOME=$HOME/Android/Sdk python3 scripts/compile-check.py --tests
# or, with no javac on PATH:
python3 scripts/compile-check.py --ecj /path/to/ecj-4.30.jar
```

CI runs `gradle assembleDebug` and `gradle testDebugUnitTest` on every pull
request (`.github/workflows/build.yml`).

## Repository map

```
app/src/main/java/com/soniq/music/
  data/
    MediaItem.java          song / artist / album / playlist model + JSON
    InnerTubeClient.java    HTTP, client table, visitor data, player/search/browse
    YouTubeMusicApi.java    search, browse, audio-only stream resolution, parsing
    ResolvedTrack.java      playable URL + headers + metadata
    Library.java            liked / artists / collections / local playlists / recent
    Settings.java           quality, preferred client, notification-prompt state
    Downloads.java          MediaStore downloads + offline index
    ListenBrainzApi.java    validate-token / submit-listens HTTP client
    ScrobbleQueue.java      listen detection, playing_now, offline listen queue
    ScrobblePolicy.java     listen threshold + metadata cleaning (pure, unit-tested)
    Scrobble.java           one persisted listen
    LyricsApi.java          lrclib.net client
    LyricsParser.java       LRC parser (pure JVM, unit-tested)
    ImageLoader.java        bounded in-memory artwork cache
  playback/
    PlaybackService.java    foreground service, MediaPlayer, MediaSession, notification,
                            audio focus, wake/wifi locks, sleep timer, equalizer
    PlaybackQueue.java      queue / shuffle / repeat (pure JVM, unit-tested)
    PlaybackPosition.java   smooths MediaPlayer position (stuck 0, stale readings,
                            in-flight seeks) with a wall-clock estimate (pure JVM)
    MediaButtonReceiver.java headset / Bluetooth buttons
  ui/
    MainActivity.java       activity, tabs, service binding, dialogs, deep links
    Host.java               screen ↔ activity contract
    Theme.java / Ui.java    palette + programmatic widget helpers
    NetworkImage.java       artwork view with outline-rounded corners
    Tab.java                bottom navigation destinations
    views/                  TrackRow, Chip, BottomNav, MiniPlayer, LyricsView
    screens/                Home, Search, Library, Settings, Player, Queue, Collection
app/src/test/java/           JUnit tests for queue, lyrics, scrobble policy, model
                            and filename logic
docs/PORTING.md              upstream class → replacement mapping
scripts/compile-check.py     android.jar-only compile check
```

There are no XML layouts by design: the UI is built programmatically so the
project has no AndroidX / AppCompat dependency. Add new screens in `ui/screens/`
and new widgets in `ui/views/`; keep the `Host` contract small.

## How to add a feature

1. **Model first.** If the feature needs a new field, add it to `MediaItem`
   and extend `toJson()` / `fromJson()` — the library persists that shape.
2. **API second.** New InnerTube calls belong in `InnerTubeClient`; new
   response parsing belongs in `YouTubeMusicApi`. Keep the public method
   signature small and throw `IOException` with a user-readable reason.
3. **Service third.** Anything that affects playback goes through
   `PlaybackService`; keep queue mutations on the main thread and update
   `MediaSession` metadata/state after every change.
4. **UI last.** Screens read from `Host`; never reach into the service from a
   background thread. Rebuild with `Screen.refresh()` rather than mutating
   views from a worker.
5. **Test the pure part.** Queue, lyrics, identity and filename logic have
   JVM tests. Add a test for any new pure function; do not add Android-only
   tests that require `org.json` on the JVM (the android.jar stubs throw).

## PR checklist

- [ ] `./gradlew assembleDebug` succeeds.
- [ ] `./gradlew testDebugUnitTest` succeeds.
- [ ] No new runtime dependency, no new binary, no new permission unless the
      PR explains why.
- [ ] No Android 4 / Arc7 / Kobo code path reintroduced.
- [ ] Network and disk work stay off the main thread.
- [ ] Foreground-service, notification-channel and immutable-PendingIntent
      rules still hold.
- [ ] `README.md` / `docs/PORTING.md` updated if the architecture changed.
