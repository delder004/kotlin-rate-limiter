# Implementation Plan

## Overview

A coroutine-native, client-side rate limiter library for Kotlin. Two implementations (bursty and smooth) behind a common interface, plus a composite wrapper and Flow/extension utilities.

## Project Structure

```
kotlin-rate-limiter/
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
├── LICENSE
├── implementation-plan.md
├── testing-plan.md
│
└── src/
    ├── main/kotlin/ratelimiter/
    │   ├── RateLimiter.kt
    │   ├── BurstyRateLimiter.kt
    │   ├── SmoothRateLimiter.kt
    │   ├── CompositeRateLimiter.kt
    │   └── Extensions.kt
    │
    └── test/kotlin/ratelimiter/
        ├── BurstyRateLimiterTest.kt
        ├── SmoothRateLimiterTest.kt
        ├── CompositeRateLimiterTest.kt
        ├── ExtensionsTest.kt
        └── ConcurrencyTest.kt
```

## Build Order

Implement in this order. Each phase is independently submittable.

### Phase 1: Public API (RateLimiter.kt + Extensions.kt)

The entire public surface of the library. Everything else is internal.

**RateLimiter.kt:**
- `RateLimiter` interface with `acquire(permits)` and `tryAcquire(permits)`
- `Permit` sealed interface with `Granted` and `Denied(retryAfter: Duration)`

**Extensions.kt:**
- `RateLimiter.withPermit(permits, block)` — acquires then runs block
- `Flow<T>.rateLimit(limiter, permits)` — acquires per emission (not per collection)

### Phase 2: BurstyRateLimiter (core implementation)

Token bucket algorithm. This is the most important and most-used implementation.

**Factory function:**
```kotlin
fun BurstyRateLimiter(
    permits: Int,
    per: Duration,
    timeSource: TimeSource = TimeSource.Monotonic
): RateLimiter
```

**Internal implementation — `BurstyRateLimiterImpl`:**

State:
- `availablePermits: Double` — current token count, can go negative
- `lastRefillMark: TimeMark` — when we last calculated token refill

Stored as an immutable `data class State` inside an `AtomicReference`.

Key calculations:
- `intervalNanos = period.inWholeNanoseconds / permits` — time between individual permits
- `refill(current: State): State` — pure function, calculates how many permits would have accumulated since `lastRefillMark`, caps at `permits` (bucket size = rate)
- `reservePermits(permits: Int): Duration` — CAS loop that refills, subtracts permits (may go negative), returns wait duration

Acquire flow:
1. `reservePermits(n)` via CAS loop
2. If wait > 0, call `delay(waitDuration)`
3. On `CancellationException` during delay, restore permits via CAS

tryAcquire flow:
1. CAS loop: refill, check if `availablePermits >= permits`
2. If yes: subtract and return `Permit.Granted`
3. If no: calculate `retryAfter` from deficit, return `Permit.Denied(retryAfter)`

**Key design decisions:**
- `Double` for permits allows fractional refill (150ms elapsed at 200ms interval = 0.75 permits)
- Going negative on acquire means the current caller proceeds immediately but the next caller pays the debt (Guava pattern)
- Cancellation restores permits so cancelled work doesn't consume quota
- No Mutex — fully lock-free via AtomicReference CAS

### Phase 3: SmoothRateLimiter

Distributes permits evenly. If configured for 10/sec, one permit is released every 100ms regardless of demand.

**Factory function:**
```kotlin
fun SmoothRateLimiter(
    permits: Int,
    per: Duration,
    warmupDuration: Duration = Duration.ZERO,
    timeSource: TimeSource = TimeSource.Monotonic
): RateLimiter
```

**Differences from bursty:**
- Does NOT accumulate permits during idle time (or accumulates only 1)
- Even spacing between permits — if you call `acquire()` twice rapidly, the second call waits the full interval
- Warmup: starts with longer intervals that decrease to the stable rate over `warmupDuration`

**Warmup implementation (stretch goal):**
- Cold state: interval starts at `coldInterval = stableInterval * coldFactor`
- As permits are consumed, interval decreases linearly toward `stableInterval`
- After idle for `warmupDuration`, returns to cold state

Without warmup, this is essentially the bursty implementation with `maxBurst = 1` (or a small number). Consider whether a separate implementation is needed or if bursty with `maxBurst = 0` can serve this role. Decide based on whether warmup is implemented.

### Phase 4: CompositeRateLimiter

Wraps multiple limiters. Simple delegation.

**Factory function:**
```kotlin
fun CompositeRateLimiter(vararg limiters: RateLimiter): RateLimiter
```

**Implementation:**
- `acquire(permits)` — calls `acquire` on ALL limiters sequentially
- `tryAcquire(permits)` — calls `tryAcquire` on ALL limiters, returns `Granted` only if all grant, otherwise returns the `Denied` with the longest `retryAfter`

**Edge case:** If the first limiter's `acquire` succeeds but the second fails (suspends), the first limiter has already consumed a permit. This is acceptable — it's the same as what happens with real API calls. Document this behavior.

### Phase 5: Tests

See testing-plan.md for the full test matrix.

### Phase 6: README and Documentation

Usage examples, design decisions, acknowledgments. See README.md.

## Coroutine Concepts Demonstrated

This project exercises the following concepts from the course:

- **Suspending functions** — `acquire()` is a suspend function that calls `delay()`
- **Structured concurrency** — composable with `coroutineScope`, `withTimeout`, `async`
- **Cancellation** — cancelled coroutines restore permits; `delay()` is a cancellation point
- **Flow operators** — `rateLimit()` extension that acquires per emission
- **Testing with virtual time** — `testScheduler.timeSource` for deterministic tests using `advanceTimeBy`, `advanceUntilIdle`, `runCurrent`
- **Concurrent coordination** — multiple coroutines sharing a single limiter, coordinated via AtomicReference CAS

## Dependencies

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.0.0"  // adjust to match course version
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
```

## Prior Art and References

- **Guava RateLimiter** — token bucket with warmup, injectable stopwatch for testing. Test file at `guava-tests/test/com/google/common/util/concurrent/RateLimiterTest.java` is the primary reference for test patterns.
- **resilience4j** — Java resilience library with Kotlin suspend extensions. Rate limiter uses `AtomicRateLimiter` with injectable `nanoTimeSupplier`. Has a [known bug](https://github.com/resilience4j/resilience4j/issues/1416) where the Flow extension rate-limits per-collection instead of per-emission.
- **kotlinx.coroutines issue #460** — open since 2018, requesting a suspendable rate limiter. Maintainer (qwwdfsad) wants "a single primitive good enough for the vast majority of uses."
- **kotlinx.coroutines PR #2799** — community PR that was closed. Key feedback: must support bursts, must have `tryAcquire`, should use `kotlin.time`, should live in `kotlinx.coroutines.sync` package. Google's lowasser suggested multi-permit acquire, "hertz" vs "N per duration" distinction, and burstiness as intersection of rate limits.
- **kmp-resilient** — from the same coroutines course. Broad resilience library covering retry, circuit breaker, rate limiter, bulkhead, etc. Our library is more focused — just rate limiting, done well.
