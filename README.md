# kotlin-rate-limiter

[![Check](https://github.com/delder004/kotlin-rate-limiter/actions/workflows/check.yml/badge.svg)](https://github.com/delder004/kotlin-rate-limiter/actions/workflows/check.yml)
[![License](https://img.shields.io/github/license/delder004/kotlin-rate-limiter)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)

A coroutine-native rate limiter for Kotlin. Controls the pace of outbound requests to external APIs using suspending functions — no threads blocked, no timers running, no framework dependencies.

`kotlin-rate-limiter` is currently an early-stage library with a deliberately small API surface. The project targets Kotlin/JVM, uses Kotlin 2.2.20, and runs on JDK 21.

## Why?

There's an [open issue](https://github.com/Kotlin/kotlinx.coroutines/issues/460) on kotlinx.coroutines (since 2018!) requesting a suspendable rate limiter. A [PR was submitted and closed](https://github.com/Kotlin/kotlinx.coroutines/pull/2799) without being merged. The existing options are either Java-based wrappers (resilience4j, Bucket4j) that don't integrate with virtual time testing, or part of large resilience frameworks where you pull in the whole kitchen sink for one primitive.

This library is:

- **Coroutine-native** — `acquire()` suspends instead of blocking. Built on `delay()` and `AtomicReference`, no Java concurrency primitives.
- **Testable with virtual time** — inject `testTimeSource` and use `advanceTimeBy()` / `advanceUntilIdle()` for deterministic, instant tests.
- **Focused** — small API surface. No framework, no annotations, no configuration files.
- **Client-side** — designed for throttling your outbound API calls, not for protecting your server endpoints.

## Project Status

- Current version: `0.1.0`
- Stability: early public release
- Target platform: Kotlin/JVM
- Java toolchain: JDK 21

## Documentation

- Guides: [`docs/`](docs/)
- Core interface: [`docs/RateLimiter.md`](docs/RateLimiter.md)
- Bursty limiter: [`docs/BurstyRateLimiter.md`](docs/BurstyRateLimiter.md)
- Smooth limiter: [`docs/SmoothRateLimiter.md`](docs/SmoothRateLimiter.md)
- Composite limiter: [`docs/CompositeRateLimiter.md`](docs/CompositeRateLimiter.md)
- Extensions: [`docs/Extensions.md`](docs/Extensions.md)

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.delder004:kotlin-rate-limiter:0.1.0")
}
```

## Quick Start

```kotlin
import ratelimiter.BurstyRateLimiter
import ratelimiter.withPermit
import kotlin.time.Duration.Companion.seconds

val limiter = BurstyRateLimiter(permits = 10, per = 1.seconds)

suspend fun fetchProperty(address: String): Property = limiter.withPermit {
    httpClient.get("https://api.example.com/property/$address").body()
}
```

## API

### Interface

```kotlin
interface RateLimiter {
    suspend fun acquire(permits: Int = 1)
    fun tryAcquire(permits: Int = 1): Permit
}

sealed interface Permit {
    data object Granted : Permit
    data class Denied(val retryAfter: Duration) : Permit
}
```

`acquire()` reserves permits and suspends the current caller until they are available. `tryAcquire()` returns immediately with `Granted` or `Denied(retryAfter)`.

Both functions require `permits > 0`.

Need a timeout? Use `kotlinx.coroutines`:

```kotlin
withTimeout(3.seconds) {
    limiter.acquire()
}
```

### Factory Functions

#### BurstyRateLimiter

Token bucket algorithm. Allows bursts up to the permit count, then paces requests. Use when an external API says "100 requests per minute" and you want maximum throughput while staying compliant.

```kotlin
val limiter = BurstyRateLimiter(
    permits = 100,
    per = 1.minutes,
    timeSource = TimeSource.Monotonic     // injectable for testing
)
```

Returns `RefundableRateLimiter`, which also exposes `refund(permits)` when reserved work is abandoned before completion.

#### SmoothRateLimiter

Distributes permits evenly over time with at most one immediately-available permit. If you configure 10 per second, permits are released every 100ms after that. Use when you want to create even load on a downstream service.

```kotlin
val limiter = SmoothRateLimiter(
    permits = 10,
    per = 1.seconds,
    warmup = Duration.ZERO,               // optional ramp-up period
    timeSource = TimeSource.Monotonic
)
```

Returns `RefundableRateLimiter`, which also exposes `refund(permits)` when reserved work is abandoned before completion.

#### CompositeRateLimiter

Combines multiple limiters. `acquire()` only proceeds when ALL limiters have capacity. `tryAcquire()` returns the longest `retryAfter` among denying children. Use for APIs with layered limits.

```kotlin
val limiter = CompositeRateLimiter(
    BurstyRateLimiter(permits = 10, per = 1.seconds),
    BurstyRateLimiter(permits = 1000, per = 24.hours)
)
```

Partial grants are rolled back automatically if a later limiter denies the same request.

### Extensions

```kotlin
// Wrap a block with rate limiting
val result = limiter.withPermit {
    api.fetchData()
}

// Rate limit a Flow (per-emission, not per-collection)
addressFlow
    .rateLimit(limiter)
    .map { address -> api.fetch(address) }
    .collect { save(it) }
```

`withPermit {}` acquires before running the block, but does not automatically refund permits if the block fails or is cancelled.

## Usage Examples

### Batch Processing

Process a large list of items while respecting API rate limits. All coroutines coordinate through the shared limiter.

```kotlin
val limiter = BurstyRateLimiter(permits = 10, per = 1.seconds)

suspend fun enrichAllProperties(addresses: List<String>): List<Property> =
    coroutineScope {
        addresses.map { address ->
            async {
                limiter.withPermit {
                    attomApi.fetchProperty(address)
                }
            }
        }.awaitAll()
    }
```

### Multi-Permit Acquire (Bandwidth Limiting)

Charge different costs for different operations.

```kotlin
val bandwidthLimiter = SmoothRateLimiter(
    permits = 10_000_000,  // 10MB per second
    per = 1.seconds
)

suspend fun uploadChunk(data: ByteArray) {
    bandwidthLimiter.acquire(permits = data.size)
    s3Client.putObject(data)
}
```

### Cost Control

Prevent a runaway loop from racking up charges on a paid API.

```kotlin
val paidApiLimiter = BurstyRateLimiter(permits = 10_000, per = 24.hours)

suspend fun checkCreditScore(userId: String): Score = paidApiLimiter.withPermit {
    creditApi.check(userId)  // $0.10 per call
}
```

### Conditional Fallback with tryAcquire

Serve from cache when rate limited instead of waiting.

```kotlin
suspend fun getProperty(address: String): Property {
    return when (limiter.tryAcquire()) {
        is Permit.Granted -> {
            val fresh = api.fetch(address)
            cache.put(address, fresh)
            fresh
        }
        is Permit.Denied -> {
            cache.get(address) ?: throw ServiceUnavailableException()
        }
    }
}
```

### Flow Pipeline

```kotlin
val scrapeLimiter = SmoothRateLimiter(permits = 2, per = 1.seconds)

urlFlow
    .rateLimit(scrapeLimiter)
    .map { url -> httpClient.get(url).bodyAsText() }
    .map { html -> parseListings(html) }
    .collect { listings -> db.insertAll(listings) }
```

## Testing

The library is designed for deterministic testing with `kotlinx-coroutines-test`. Pass `testTimeSource` to tie the rate limiter's clock to virtual time:

```kotlin
@Test
fun `acquire suspends when permits exhausted`() = runTest {
    val limiter = BurstyRateLimiter(
        permits = 2,
        per = 1.seconds,
        timeSource = testTimeSource
    )

    limiter.acquire()  // instant
    limiter.acquire()  // instant — both permits consumed

    var acquired = false
    launch {
        limiter.acquire()  // suspends
        acquired = true
    }

    runCurrent()
    assertFalse(acquired)

    advanceTimeBy(500.milliseconds)
    runCurrent()
    assertTrue(acquired)
}
```

For tests that don't care about rate limiting behavior, inject a no-op limiter:

```kotlin
val noOpLimiter = object : RateLimiter {
    override suspend fun acquire(permits: Int) {}
    override fun tryAcquire(permits: Int) = Permit.Granted
}
```

## Design Decisions

### Why `acquire()` + `tryAcquire()` and not `tryAcquire(timeout)`?

`acquire()` suspends, so you compose timeouts with `withTimeout {}` / `withTimeoutOrNull {}` from `kotlinx.coroutines`. No need to reinvent what already exists. `tryAcquire()` exists for the non-suspending "check and decide" case where you want the `retryAfter` hint.

This matches the convention established by Guava's `RateLimiter` and resilience4j.

### Why AtomicReference + CAS instead of Mutex?

Lock-free implementation means `tryAcquire()` works naturally as a non-suspend function, `refill()` is a pure function that's easy to test in isolation, and there's no coroutine suspension just for bookkeeping under contention.

### Why lazy refill instead of a background coroutine?

The limiter calculates how many permits *would have been* added since the last access, rather than running a timer. This means no `CoroutineScope` required, no lifecycle to manage, no cleanup — just create the limiter and use it.

### Why client-side only?

Server-side rate limiting (protecting your API from callers) is a different problem with different tools — Ktor has a built-in plugin, nginx handles it at the infrastructure level. Client-side rate limiting (throttling your outbound calls) is the underserved use case where developers are rolling their own `while(isActive) { delay() }` loops.

## Community

- Questions, bug reports, and feature requests: open a GitHub issue
- Contributing guide: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- Code of conduct: [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)
- Security policy: [`SECURITY.md`](SECURITY.md)
- Changelog: [`CHANGELOG.md`](CHANGELOG.md)

## Acknowledgments

- [kotlinx.coroutines issue #460](https://github.com/Kotlin/kotlinx.coroutines/issues/460) and [PR #2799](https://github.com/Kotlin/kotlinx.coroutines/pull/2799) for design insights
- [Guava RateLimiter](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/RateLimiter.java) for the token bucket implementation patterns and test strategies
- [lowasser's comments](https://github.com/Kotlin/kotlinx.coroutines/pull/2799#issuecomment-871557098) on multi-permit acquire and burstiness control from Google's experience with Guava

## License

Apache 2.0
