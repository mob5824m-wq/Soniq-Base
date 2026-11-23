# Soniq Music

A standalone YouTube Music client for modern Android, rebuilt from the
YouTube Music app in
[Kobo Arc 7 Freedom](https://github.com/mob5824m-wq/Kobo-Arc-7-freedom).

![Android](https://img.shields.io/badge/minSdk-26-3DDC84?logo=android&logoColor=white)
![Target](https://img.shields.io/badge/targetSdk-35-3DDC84?logo=android&logoColor=white)
![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Dependencies](https://img.shields.io/badge/runtime%20dependencies-0-4C1)

## What it does

- **Search** YouTube Music for songs, artists, albums, playlists and community
  playlists, with a filter chip row and per-result overflow menu.
- **Play** audio-only streams with a foreground service, `MediaSession`,
  lock-screen / Bluetooth / headset controls and a `MediaStyle` notification.
- **Queue** with shuffle (current song stays first), repeat off / all / one,
  "up next" sheet and per-item remove.
- **Full-screen player** with artwork, scrubber, synced lyrics (tap a line to
  seek), like, download, queue and radio actions.
- **Library** with liked songs, saved artists, saved albums and playlists,
  local playlists and a downloads section — all stored on the device.
- **Offline downloads** to MediaStore (`Music/Soniq Music`) with a progress
  dialog and an offline index in private storage.
- **Settings** for stream quality (128/256 kbps), preferred InnerTube client,
  sleep timer, equalizer presets and notifications.
- **ListenBrainz scrobbling** (opt-in): paste your user token, and finished
  listens are submitted with artist, track, album, duration and YouTube Music
  link. A `playing_now` update is sent when a song starts; listens made
  offline are queued and sent later. Follows ListenBrainz' "half the track or
  four minutes" rule and one-request-per-second limit.
- **Deep links** for `music.youtube.com` and `youtu.be` URLs.

## Requirements

- Android 8.0 (API 26) or newer.
- No account, no Google Play Services, no root.

## Build

```bash
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests
```

Requires JDK 17 and an Android SDK with platform 35. The Gradle wrapper pins
Gradle 8.9 and AGP 8.7.3.

No APK is committed to the repository. Build it with the command above, or
download the `soniq-music-debug` artifact from this repository's GitHub
Actions "Build" workflow.

## No Android SDK? Quick compile check

```bash
ANDROID_HOME=~/Android/Sdk python3 scripts/compile-check.py --tests
# no javac on PATH? use the Eclipse compiler jar:
python3 scripts/compile-check.py --ecj /path/to/ecj-4.30.jar
```

## Limitations / not included

- **No sign-in.** Public InnerTube endpoints only; some videos are
  age-restricted, members-only or region-locked and will refuse to play. The
  app reports the provider's reason instead of hiding it.
- **No stream-signature deciphering.** If Google changes the player response
  enough that audio URLs can no longer be read directly, playback breaks until
  the parser is updated.
- **No bypass of age/member/region restrictions.** This is intentional.
- **Lyrics** come from lrclib.net and may be missing or unsynced.
- **Radio** is a search-seeded mix, not YouTube's own radio endpoint.
- **Downloads** are stored unencrypted in public MediaStore; removing the app
  keeps the files (delete them from your music player/files app).
- **Artwork cache** is memory-only and cleared when the process dies.
- **ListenBrainz** needs a free account token from
  <https://listenbrainz.org/settings>; it is stored only in the app's private
  storage and sent only to `api.listenbrainz.org`. Scrobbling is off until you
  add a token.

## Project layout

```
app/src/main/java/com/soniq/music/
  data/        MediaItem, InnerTubeClient, YouTubeMusicApi, Library, Settings,
               Downloads, LyricsApi/Parser, ImageLoader, ListenBrainzApi,
               ScrobbleQueue, ScrobblePolicy, Scrobble
  playback/    PlaybackService, PlaybackQueue, MediaButtonReceiver
  ui/          MainActivity, Host, Theme, Ui, views/, screens/
app/src/test/  JVM unit tests
docs/PORTING.md
scripts/compile-check.py
```

For the full class-by-class porting map see [docs/PORTING.md](docs/PORTING.md).
For agent instructions see [agent.md](agent.md).

## Legal

Soniq Music is an unofficial client. It is not affiliated with Google or
YouTube. "YouTube" and "YouTube Music" are trademarks of Google LLC. Use it
only with content you have the right to access, and respect the platform's
terms of service.
