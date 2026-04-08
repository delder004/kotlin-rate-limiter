# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and the project aims to follow Semantic Versioning.

## [Unreleased]

- Initial public documentation and OSS project scaffolding

## [0.1.0] - 2026-04-07

- Initial release
- Added `RateLimiter` with `acquire()` and `tryAcquire()`
- Added `BurstyRateLimiter`
- Added `SmoothRateLimiter` with optional warmup
- Added `CompositeRateLimiter`
- Added `withPermit` and `Flow.rateLimit()` extensions
- Added virtual-time friendly tests and Maven Central publishing setup
