# Changelog

All notable changes are documented here. This project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) while recognizing
that `0.x` releases may contain breaking API changes.

## [Unreleased]

## [0.2.0] - 2026-08-25

### Added

- Reusable player extraction stack with content-aware client selection,
  format selection, direct/HLS/SABR transport support, PO-token contracts,
  client-health integration, bounded watch/player parsing, and diagnostics.
- Platform-neutral BotGuard/page-attestation parsing and transcript/caption
  helpers for Android and desktop hosts.
- Public bounded response decoders and hardened cookie normalization helpers.

### Fixed

- Bound player, watch-page, iframe, caption, transcript, token, and sidecar
  responses before materializing them in memory.
- Validate media, caption, player-script, SABR, upload, and playback-statistics
  URLs by HTTPS host, default port, and expected endpoint path.
- Reject unresolved cipher parameters, incomplete video selections, invalid
  video media hosts, and bounded-range streams without known lengths.
- Preserve coroutine cancellation across extraction and playback-recovery
  fallbacks, and bind player-config caching to the originating session.
- Redact signed URLs, visitor data, PO tokens, account identifiers, media IDs,
  and raw exception messages from reusable diagnostics.
- Restore `application/octet-stream` for finalized song-upload bodies.

## [0.1.2] - 2026-08-22

### Added

- Playlist custom thumbnail upload and removal.
- Adding one playlist to another playlist.
- Deleting privately owned library entities.
- Optional progress reporting for in-memory song uploads.

### Fixed

- Documented JitPack's variant-aware KMP coordinate instead of its aggregate
  repository POM coordinate.
- Accept-Language headers no longer duplicate a region already present in the
  language tag.
- `accountsList` omits the active-account sync id so every signed-in account
  can be enumerated.

## [0.1.1] - 2026-08-21

### Fixed

- Preserved the generated binary API baseline format so clean CI and JitPack
  builds pass. The failed `v0.1.0` JitPack lookup did not publish artifacts.

## [0.1.0] - 2026-08-21

### Added

- Android and JVM Kotlin Multiplatform InnerTubeX client.
- Session-safe authenticated requests and account operations.
- Player cipher handling with Faraday/Zemer, EJS, and parser fallbacks.
- Experimental SABR audio/video transport with bounded UMP parsing.
- Streaming YouTube Music uploads without full-file buffering.
- JitPack publication, API compatibility validation, and CI gates.

### Security

- Restricted authenticated playback-statistics requests to approved YouTube
  HTTPS endpoints.
- Bounded remote player, config, visitor-data, and SABR response bodies.
- Redacted raw URLs and exception text from library diagnostics.
- Restricted authenticated upload, media probing, and executable player-config
  requests to their expected HTTPS providers without following redirects.
- Removed account cookies from anonymous browse, next, and queue requests.
- Bound account mutations and both upload stages to their originating session.
- Bounded retained SABR contexts, playback cookies, bootstrap blobs, and total
  response bytes, with exact full-stream byte validation.
- Added a QuickJS evaluation timeout for malformed or hostile player code.
