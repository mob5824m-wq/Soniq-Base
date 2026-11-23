# Changelog

All notable changes to Soniq Base are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[SemVer](https://semver.org/). A version is a `vX.Y.Z` git tag; cut one with
`python3 scripts/release.py X.Y.Z` (see README → Releases).

## [Unreleased]

### Added
- **Backbone capabilities, with the app half documented.** Eight capabilities
  that every music app needs are now provided as a framework half plus a
  written, per-app finishing checklist in [`docs/WIDGETS.md`](docs/WIDGETS.md)
  and `AGENT.md`:
  - **Home-screen widget** — `SoniqWidgetProvider` (artwork, title, artist,
    play/pause, next) declared in the library manifest and pushed from
    `PlaybackService`; apps brand it or ship their own.
  - **Android Auto / MediaBrowserService** — `SoniqMediaBrowserService` exposes
    Liked / Recently played / Downloads / Playlists with the live session token;
    apps declare it, add `automotiveApp` metadata and their own browse nodes.
  - **Crossfade and gapless** — `PlaybackEngine` (pure) plus next-track
    prebuffering and a dual-player hand-off in `PlaybackService`; linear fades
    of 2–12 s, off by default, never reports buffering during a fade.
  - **Search history** — `SearchHistory` (12 entries, counted, deduped) with a
    "Recent searches" chip row, per-entry removal and a confirmed clear.
  - **Wi-Fi only** — `NetworkPolicy` (pure) enforced for streaming *and*
    downloads, with a Settings row that shows the live connection and refuses
    to guess on unknown networks.
  - **No lyrics vs instrumental** — `LyricsRepository` and `LyricsResult` split
    *found*, *instrumental*, *not found* and *unavailable*, cache only permanent
    answers, label plain lyrics as unsynced, and let
    `MusicProvider.lyricsState()` supply the catalogue's own answer.
  - **Resume where you left off** — `PlaybackSession` + `ResumePoint` (pure)
    persist the queue and position; Home shows a "Continue listening" card that
    only appears for a meaningful, recent, non-live position.
  - **Accessibility** — `ui/A11y` helpers, one focus stop per track row,
    described artwork, labelled icon buttons and 48 dp targets.
- `Settings` gained crossfade, gapless, Wi-Fi-only, resume, widget, search-sort
  and library-sort preferences; Settings now has Crossfade, Gapless, Continue
  listening, Wi-Fi only, Library order, Search history and Downloaded music rows
  with the size actually used on disk.
- `Downloads` gained `totalBytes()`, `totalLabel()`, `byteCount()`, `deleteAll()`
  and `formatBytes()`; `LibrarySort` orders liked songs and playlists by recency,
  title, artist or duration.
- `MediaItem` gained `lyricsState` (JSON key `lyr`, omitted when unset) and
  `MusicProvider` gained the optional `lyricsState(track)` hook.

### Added
- **Year in review** — a Wrapped-style recap of a whole calendar year built from
  ListenBrainz listening statistics, opened from Home ("Year in review") and
  Settings. It shows total listens and listening time, artists / tracks / albums
  heard and new artists discovered, a January–December activity chart, the top
  artists, songs, albums and genres with counts and Cover Art Archive artwork,
  the most played era and busiest weekday, and a native share of a text
  summary. Any year ListenBrainz still publishes can be selected; a year in
  progress is labelled as partial and past years stay revisitable.
  `ListeningRecapApi` only reads public statistics (no token is sent) and
  `ListeningRecap` is pure Java, unit-tested on the JVM. `Settings` gained
  `insightsUserName()` / `recapUserName()` so the recap can read a public
  profile other than the scrobbling account, and `Host` gained `openInsights()`
  and `shareText(chooserTitle, text)` for host apps that implement it.
- `docs/REQUIRED-FEATURES.md` gained requirement 13: every app ships a
  whole-year listening recap, not a December-only summary.

## [0.1.0] - 2026-11-23

First release of Soniq Base as a framework library. Everything a music player
needs lives in `:soniq-base` (`com.soniq.base`), behind a `MusicProvider` SPI;
the YouTube Music client that used to be this whole repository is now the
`examples/youtube-music` reference app.

### Added
- `:soniq-base` Android library with zero runtime dependencies: foreground
  playback service, queue, library, downloads, lyrics, ListenBrainz scrobbling
  and a complete programmatic dark UI (minSdk 26 / targetSdk 35).
- `MusicProvider` SPI: `id`, `displayName`, `search`, `browse`, `resolve`, plus
  optional `quickPicks`, `radio`, `tagline`, `musicService` and `originUrl`.
- `SoniqApp` application base, `MusicActivity` base activity with an
  `onHandleViewIntent(Uri)` hook, and `Theme.customize` / `Theme.reset` for
  rebranding.
- `MediaItem` and `StreamSource` models; metadata corrected by the provider
  while resolving is folded into the playing item (notification, player,
  scrobbles).
- `examples/youtube-music`: a complete app built on the library.
- Tag-derived versions (no version numbers in build files) and
  `scripts/release.py`: a release is `python3 scripts/release.py X.Y.Z --yes`
  (changelog → commit → tag → push), or a tag created in the GitHub UI.
- Publishing of `com.soniq.base:soniq-base` to GitHub Packages and GitHub
  Releases with the AAR, sources JAR and example APK, both triggered by
  pushing a `vX.Y.Z` tag.

### Changed
- The example app's application id is `com.soniq.example.youtubemusic`
  (was `com.soniq.music`), so it installs alongside any previous build.

### Fixed
- CI had never passed in this repository (`android-actions/setup-android@v3`
  failed before Gradle started) and the first green build caught the library
  icons being tinted with AppCompat's `?attr/colorControlNormal`, which does
  not exist in a framework-only app.

[unreleased]: https://github.com/mob5824m-wq/Soniq-Base/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/mob5824m-wq/Soniq-Base/compare/633e363...v0.1.0
