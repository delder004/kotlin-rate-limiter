# API Design

## Public API Surface

```kotlin
package ratelimiter

// === Core Interface ===

interface RateLimiter {
    suspend fun acquire(permits: Int = 1)
    fun tryAcquire(permits: Int = 1): Permit
}

sealed interface Permit {
    data object Granted : Permit
    data class Denied(val retryAfter: Duration) : Permit
}

// === Factory Functions ===

fun BurstyRateLimiter(
    permits: Int,
    per: Duration,
    timeSource: TimeSource = TimeSource.Monotonic
): RateLimiter

fun SmoothRateLimiter(
    permits: Int,
    per: Duration,
    warmupDuration: Duration = Duration.ZERO,
    timeSource: TimeSource = TimeSource.Monotonic
): RateLimiter

fun CompositeRateLimiter(vararg limiters: RateLimiter): RateLimiter

// === Extensions ===

suspend fun <T> RateLimiter.withPermit(permits: Int = 1, block: suspend () -> T): T

fun <T> Flow<T>.rateLimit(limiter: RateLimiter, permits: Int = 1): Flow<T>
```

## Design Rationale

### Why `acquire` + `tryAcquire` only?

Guava has four overloads. resilience4j has `reservePermission` + `acquirePermission`. The failed kotlinx.coroutines PR had `acquire` only.

We provide two functions that each do one thing:
- `acquire()` — "I need a permit and I'll wait however long it takes"
- `tryAcquire()` — "Can I have a permit right now?"

The "wait up to a timeout" case is handled by composing with `withTimeout`:
```kotlin
withTimeout(3.seconds) { limiter.acquire() }
```

This follows the Kotlin philosophy of composing small primitives rather than providing every combination as a method overload.

### Why factory functions instead of public classes?

Following kotlinx.coroutines conventions (`Mutex()`, `Semaphore()`, `MutableStateFlow()`). The implementation classes are `internal`. This means:
- Users depend on the `RateLimiter` interface, not concrete types
- Implementation can change without breaking API
- Factory function names use PascalCase to look like constructors

### Why `TimeSource` parameter?

Solves testability without test-specific code paths. In production, `TimeSource.Monotonic` is used (immune to clock adjustments, unlike `System.currentTimeMillis()`). In tests, `testScheduler.timeSource` ties to virtual time.

This is a better approach than:
- resilience4j's `Supplier<Long> nanoTimeSupplier` (raw longs, no type safety)
- Guava's `SleepingStopwatch` (custom abstraction specific to Guava)
- The failed PR's `System.currentTimeMillis()` (affected by clock changes, can't use virtual time)

### Why `Int` for permits?

`Int` is sufficient for all practical rate limits and matches `Semaphore.acquire(permits: Int)` from kotlinx.coroutines. Even bandwidth limiting at 10MB/sec fits comfortably within `Int.MAX_VALUE`.

### Why does acquire go negative?

When a caller requests 4 permits but only 1 is available:
- **Option A:** Caller waits until 4 are available, then takes them all
- **Option B:** Caller proceeds immediately, but "borrows" from the future. Next caller pays the debt.

We use Option B (Guava's approach). The current caller gets minimum latency. The rate is still enforced — the debt is real and the next caller waits longer. The total throughput over time is identical.

This matters for multi-permit acquire: `acquire(100)` for a 10/sec limiter shouldn't wait 10 seconds before starting. It should start immediately and the next caller waits 10 seconds.

### Why `sealed interface Permit` instead of `Boolean`?

`tryAcquire() -> Boolean` tells you yes or no. `tryAcquire() -> Permit` tells you yes or "no, and you can try again in 200ms." The `retryAfter` field enables:
- Setting `Retry-After` HTTP headers
- Logging how congested the limiter is
- Making informed decisions about fallback strategies

### Why no `StateFlow` for observability?

Considered and dropped. For client-side rate limiting:
- "Am I rate limited?" → call `tryAcquire()`
- "How long did I wait?" → wrap `acquire()` in `measureTime {}`
- Monitoring/metrics → the caller can add that around `withPermit`

Adding `StateFlow<RateLimiterState>` would complicate the implementation (need to emit on every state change inside the CAS loop) for marginal benefit. Can always be added later without breaking the interface.

### Why client-side only?

Server-side rate limiting (reject incoming requests) is a different problem:
- Ktor has a built-in rate limiting plugin
- nginx/HAProxy handle it at infrastructure level
- Per-user/per-IP limits require a registry of limiters (framework-specific concern)

Client-side rate limiting (throttle outgoing requests) is underserved. Every Kotlin developer calling an external API is rolling their own `delay()` loops. That's what this library solves.

## Comparison with Existing Libraries

| Feature | kotlin-rate-limiter | Guava | resilience4j | Bucket4k | kmp-resilient |
|---|---|---|---|---|---|
| Suspending acquire | ✅ | ❌ blocks | ✅ wrapper | ✅ wrapper | ✅ |
| Virtual time testing | ✅ TimeSource | ✅ FakeStopwatch | ⚠️ nanoTimeSupplier | ❌ | ✅ |
| Bursty + Smooth | ✅ | ✅ | ⚠️ fixed window | ✅ bursty only | ⚠️ token bucket |
| Multi-permit | ✅ | ✅ | ✅ | ✅ | ❌ |
| Composite limits | ✅ | ❌ | ❌ | ✅ | ❌ |
| Flow operator | ✅ per-emission | N/A | ⚠️ buggy | ❌ | ❌ |
| tryAcquire with retryAfter | ✅ | ❌ boolean | ❌ long | ❌ boolean | ❌ |
| Standalone (no framework) | ✅ | ✅ | ❌ resilience4j ecosystem | ❌ Bucket4j dep | ❌ resilience DSL |
| Kotlin-first | ✅ | ❌ Java | ⚠️ Kotlin extensions | ⚠️ wrapper | ✅ |
