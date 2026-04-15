# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and the project aims to follow Semantic Versioning.

## [Unreleased]

- Initial public documentation and OSS project scaffolding
- **Changed (behavior)**: `BurstyRateLimiter` is now backed by a lock-based
  fixed-interval implementation rather than the previous `AtomicReference`-based
  `PermitBucket` state machine. The public factory signature is unchanged and
  all existing behavior tests pass. **Compatibility note**: the factory now
  requires its `timeSource` argument to implement `TimeSource.WithComparableMarks`
  and will throw `IllegalArgumentException` at construction time otherwise.
  `TimeSource.Monotonic` (the default) and `kotlinx-coroutines-test`'s
  `testTimeSource` both satisfy this; custom `TimeSource` implementations that
  do not mix in `WithComparableMarks` will need to be wrapped or updated.

## [0.1.0] - 2026-04-07

- Initial release
- Added `RateLimiter` with `acquire()` and `tryAcquire()`
- Added `BurstyRateLimiter`
- Added `SmoothRateLimiter` with optional warmup
- Added `CompositeRateLimiter`
- Added `withPermit` and `Flow.rateLimit()` extensions
- Added virtual-time friendly tests and Maven Central publishing setup
