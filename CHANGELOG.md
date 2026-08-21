# Changelog

All notable changes are documented here. This project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) while recognizing
that `0.x` releases may contain breaking API changes.

## [Unreleased]

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
