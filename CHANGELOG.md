# Changelog

All notable changes to Soniq Base are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[SemVer](https://semver.org/). A version is a `vX.Y.Z` git tag; cut one with
`python3 scripts/release.py X.Y.Z` (see README → Releases).

## [Unreleased]

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
