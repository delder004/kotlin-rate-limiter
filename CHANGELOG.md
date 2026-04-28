# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and the project aims to follow Semantic Versioning.

## [Unreleased]

- Initial public documentation and OSS project scaffolding
- **Changed (breaking)**: All public types moved from package `ratelimiter` to
  `io.github.delder004.ratelimiter` to match the Maven group coordinate and
  avoid the overly generic top-level package name. Consumers must update their
  imports (e.g. `import ratelimiter.BurstyRateLimiter` →
  `import io.github.delder004.ratelimiter.BurstyRateLimiter`). No source-level
  API shape changes.
- **Fixed**: `kotlinx-coroutines-core` is now declared as an `api` dependency
  so consumers calling `Flow.rateLimit()` get `kotlinx.coroutines.flow.Flow`
  on their compile classpath transitively. Previously declared as
  `implementation`, which mapped to `<scope>runtime</scope>` in the published
  POM and forced Maven consumers (and Gradle consumers without their own
  coroutines dep) to add it explicitly.
- **Fixed**: `CompositeRateLimiter.tryAcquire()` no longer over-cools
  `SmoothRateLimiter` delegates configured with a warmup ramp. When a
  composite denial required probing remaining delegates for their
  `retryAfter`, the previous `tryAcquire + refund` probe was not
  state-neutral on warming smooth: a granted `tryAcquire` left heat
  unchanged (grants draw from stored credit, not borrowed future), but
  `refund` always decremented heat by `permits`. The net effect was
  phantom cooling on the warming delegate after every composite denial.
  Fixed via an internal non-mutating `peekWait` path used by composite's
  probe. The bug predates the lock-based rewrite and was latent because
  composite tests only exercised zero-warmup smooth delegates.
- **Changed (behavior)**: `BurstyRateLimiter` and `SmoothRateLimiter` are now
  backed by lock-based schedule-mark implementations (`FixedIntervalLimiter`
  and `WarmingSmoothLimiter`) rather than the previous `AtomicReference`-based
  `PermitBucket` state machine. Public factory signatures are unchanged and
  all existing behavior tests pass. **Compatibility note**: both factories
  now require the `timeSource` argument to implement
  `TimeSource.WithComparableMarks` and will throw `IllegalArgumentException`
  at construction time otherwise. `TimeSource.Monotonic` (the default) and
  `kotlinx-coroutines-test`'s `testTimeSource` both satisfy this; custom
  `TimeSource` implementations that do not mix in `WithComparableMarks` will
  need to be wrapped or updated.

## [0.1.0] - 2026-04-07

- Initial release
- Added `RateLimiter` with `acquire()` and `tryAcquire()`
- Added `BurstyRateLimiter`
- Added `SmoothRateLimiter` with optional warmup
- Added `CompositeRateLimiter`
- Added `withPermit` and `Flow.rateLimit()` extensions
- Added virtual-time friendly tests and Maven Central publishing setup
