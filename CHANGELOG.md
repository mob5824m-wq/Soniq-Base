# Changelog

All notable changes to Soniq Base are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[SemVer](https://semver.org/). A version is a `vX.Y.Z` git tag; cut one with
`python3 scripts/release.py X.Y.Z` (see README → Releases).

## [Unreleased]

### Added
- **Podcasts (example half): real public podcasts.** `examples/youtube-music`
  now answers `FILTER_PODCASTS`, `podcasts()` and `browse()` of a show with the
  public Apple directory instead of an empty list, so the Podcasts tab and the
  Podcasts search chip return real shows and their episodes play
  (`PublicPodcastsApi`): the iTunes Search API finds shows (no key, no account)
  and returns each show's RSS `feedUrl`, the feed lists the episodes, and the
  episode's `<enclosure>` URL is the audio, streamed straight from the
  publisher. Discover starts from Apple's public top-podcasts chart with a
  keyword-search fallback; episodes without audio are dropped rather than
  listed; plain `http://` feeds and enclosures are refused with a readable
  message (the example ships `usesCleartextTraffic="false"`); episodes still
  never scrobble. Search → More on a show now opens the track menu
  (Subscribe / Open) instead of silently toggling the subscription.
- **`MusicProvider.podcastSourceName()`** — the Podcasts tab names the podcast
  catalogue rather than the music service, for a provider that serves the two
  from different places (the example: songs from YouTube, podcasts from Apple's
  directory). Defaults to `displayName()`.
- **A crash log for the example.** `ExampleApp` installs an
  uncaught-exception handler that also writes the trace to
  `Android/data/com.soniq.example.youtubemusic/files/last-crash.txt`, since a
  minified release build with no adb attached otherwise leaves nothing to read.
- **`examples/youtube-music/proguard-rules.pro`** — the release build now keeps
  the app's entry points and the framework's class names while still shrinking.
- **Podcasts (framework half).** Episodes, shows and subscriptions are
  first-class kinds: `MediaItem` gained `KIND_PODCAST` / `KIND_EPISODE` with
  `isPodcast()` / `isEpisode()` helpers and a publish `date` field, and
  `MusicProvider` gained `FILTER_PODCASTS` plus a browsable `podcasts()` default
  (empty when a source has no catalogue — never mapped to songs).
  - **New bottom-nav Podcasts tab** (`Tab.PODCASTS`, `Host.openPodcasts()`)
    with `PodcastsScreen`: continue listening to in-progress episodes
    (resume position shown per row), subscribed shows from `Library.PODCASTS`
    (Subscribe / Unsubscribe lives in the track menu), and an honestly-empty
    Discover section when the provider offers no podcasts.
  - **Per-episode playback depth** — `EpisodeProgress` persists each episode's
    position and played flag (position-based resume reuses the track guards
    `MIN_RESUME_MS` / `END_GUARD_MS`; played marks are sticky); `PlaybackService`
    resumes on prepare, records every tick, marks episodes played at the end
    guard and on completion (including the crossfade hand-off), and gained
    `skipBy()` backing new ±15 s / ±30 s skip pills in the player, shown only
    for episodes.
  - **Search** gained a "Podcasts" filter chip; collection sheets say
    "N episodes" for shows; `TrackRow` labels shows "SHOW"; Home gained a
    "Podcasts" chip. Episodes never scrobble (`ScrobbleQueue` ignores them) and
    cannot seed radio. Auto / Wear pick shows up as browse
  nodes automatically via `isBrowsable()`.
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

### Fixed
- **Playback position hardening** (reviewed and ported from upstream Kobo Arc 7
  Freedom commit `eb270d0`): `PlaybackPosition` now treats only a *changed*
  player reading as authoritative, so a player frozen on one sample — a stuck
  `0` or a small positive sample such as `23` ms — no longer freezes elapsed
  time or the seek bar. Rebuffering (`MEDIA_INFO_BUFFERING_START`/`END`) freezes
  the wall-clock estimate until the audio advances again; a frozen sample can
  never confirm or undo a seek, and after the seek-grace window only a reading
  that keeps changing cancels a pending seek; a stale zero cannot confirm a
  nonzero seek; and repeat-one, gapless and crossfade restarts reset the
  tracker at zero so stale end-of-track samples cannot pollute the new
  timeline. `getDuration()` falls back to the track's known duration while the
  player still reports none, so the seek bar and the position clamp work from
  the first tick.

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
