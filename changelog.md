# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

* * *

## [Unreleased]

### Added

- `BOXLANG_ENABLE_ROOT_SCAN` (shared across all serverless runtimes, default `true`): set to `false` to opt out of the legacy root-directory scan used when neither `manifest.json` nor `handlers/` is present, restricting routing to the default handler only.

### Changed

- The `response` struct is now passed as the last argument to the `Application.bx` `onRequestEnd` and `onError` hooks, and the handler's return value is assigned to `response.body` before `onRequestEnd`, so hooks can wrap or replace the body and set the status. Hooks that do not declare the extra argument are unaffected.
- A handled error now defaults the response status to `500` unless `onError` sets one (it was `200`).
- A present-but-corrupt `manifest.json` now restricts routing to the default handler only, instead of falling back to a `handlers/` or root-directory scan.
- `onRequestStart` and `onAbort` now also receive the `response` struct as their last argument, so every request lifecycle hook (`onRequestStart`, `onRequestEnd`, `onError`, `onAbort`) can read the `event` and read or change the response (BL-2516).

### Fixed

- `Application.bx` is now loaded for requests routed to a class under `handlers/`, so `onRequestStart`, datasources and every other `Application.bx` setting apply to routed handlers (previously only the default handler saw them).

### Security

- Convention-based URI routing and the `x-bx-function` header are by design, but they also let an unauthenticated request reach `Application.bx`'s lifecycle callbacks (and any other root-level `.bx` file). Routing is now scoped to a `handlers/` directory convention (or a build-time `manifest.json` allowlist); `Application.bx` and the default handler class are never eligible routing targets, even under the legacy backward-compatibility fallback for existing deployments.
- `manifest.json` `reserved` and `defaultHandler` are now enforced, not just documented: reserved files can never be routed, `defaultHandler.file` and `method` are honored, and handler files that do not exist are skipped.
- `manifest.json` handler and `defaultHandler` paths are normalized and confined to the deployment root, so `../` can no longer route to files outside it.
- Setting `defaultHandler.file` to `Application.bx` now aborts cold start with a clear error instead of crashing with `duplicate element` and leaving the reserved file in effect as the default handler.

## [1.17.0] - 2026-08-28

## [1.16.0] - 2026-07-30

## [1.15.0] - 2026-07-08

## [1.14.0] - 2026-06-03

## [1.13.0] - 2026-05-01

## [1.12.0] - 2026-04-09

- Initial release

[unreleased]: https://github.com/ortus-boxlang/boxlang-google-functions/compare/v1.17.0...HEAD
[1.17.0]: https://github.com/ortus-boxlang/boxlang-google-functions/compare/v1.16.0...v1.17.0
[1.16.0]: https://github.com/ortus-boxlang/boxlang-google-functions/compare/v1.15.0...v1.16.0
[1.15.0]: https://github.com/ortus-boxlang/boxlang-google-functions/compare/v1.14.0...v1.15.0
[1.14.0]: https://github.com/ortus-boxlang/boxlang-google-functions/compare/v1.13.0...v1.14.0
[1.13.0]: https://github.com/ortus-boxlang/boxlang-google-functions/compare/v1.12.0...v1.13.0
[1.12.0]: https://github.com/ortus-boxlang/boxlang-google-functions/compare/9a2485e013f0d75a0b0d497a43f206c4cb41bb13...v1.12.0
