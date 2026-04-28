# kotlin-rate-limiter

[![Maven Central](https://img.shields.io/maven-central/v/io.github.delder004/kotlin-rate-limiter?logo=apachemaven)](https://central.sonatype.com/artifact/io.github.delder004/kotlin-rate-limiter)
[![Check](https://github.com/delder004/kotlin-rate-limiter/actions/workflows/check.yml/badge.svg)](https://github.com/delder004/kotlin-rate-limiter/actions/workflows/check.yml)
[![License](https://img.shields.io/github/license/delder004/kotlin-rate-limiter)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)

A coroutine-native rate limiter for Kotlin. Controls the pace of outbound requests to external APIs using suspending functions — no threads blocked, no timers running, no framework dependencies.

`kotlin-rate-limiter` is currently an early-stage library with a deliberately small API surface. The project targets Kotlin/JVM, uses Kotlin 2.2.20, and runs on JDK 17 or later.

**API reference:** [delder004.github.io/kotlin-rate-limiter](https://delder004.github.io/kotlin-rate-limiter/) — full Dokka-generated docs.

**Try it live:** [kotlin-rate-limiter-demo.dev](https://kotlin-rate-limiter-demo.dev) — interactive playground to experiment with the limiters in your browser.

## Why?

There's an [open issue](https://github.com/Kotlin/kotlinx.coroutines/issues/460) on kotlinx.coroutines (since 2018) requesting a suspendable rate limiter. A [PR was submitted and closed](https://github.com/Kotlin/kotlinx.coroutines/pull/2799) without being merged. The existing options are either Java-based wrappers (resilience4j, Bucket4j) that don't integrate with virtual time testing, or part of large resilience frameworks where you pull in the whole kitchen sink for one primitive.

This library is:

- **Coroutine-native** — `acquire()` suspends instead of blocking. Built on `delay()` with short, non-blocking critical sections; no background threads, no timers, no `CoroutineScope` lifecycle.
- **Testable with virtual time** — inject `testTimeSource` and use `advanceTimeBy()` / `advanceUntilIdle()` for deterministic, instant tests.
- **Focused** — small API surface. No framework, no annotations, no configuration files.
- **Client-side** — designed for throttling your outbound API calls, not for protecting your server endpoints.

## Project Status

- Current version: see the Maven Central badge above
- Stability: early public release
- Target platform: Kotlin/JVM
- Java toolchain: JDK 17

## Documentation

- API reference (Dokka): [delder004.github.io/kotlin-rate-limiter](https://delder004.github.io/kotlin-rate-limiter/)
- Guides: [`docs/`](docs/)
- Core interface: [`docs/RateLimiter.md`](docs/RateLimiter.md)
- Refundable interface: [`docs/RefundableRateLimiter.md`](docs/RefundableRateLimiter.md)
- Bursty limiter: [`docs/BurstyRateLimiter.md`](docs/BurstyRateLimiter.md)
- Smooth limiter: [`docs/SmoothRateLimiter.md`](docs/SmoothRateLimiter.md)
- Composite limiter: [`docs/CompositeRateLimiter.md`](docs/CompositeRateLimiter.md)
- Extensions: [`docs/Extensions.md`](docs/Extensions.md)
- Ktor client plugin example: [`docs/KtorClientPluginExample.md`](docs/KtorClientPluginExample.md)
- HTTP client wrapper: [`docs/RateLimitedHttpClient.md`](docs/RateLimitedHttpClient.md)

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.delder004:kotlin-rate-limiter:0.2.0")
}
```

## Quick Start

```kotlin
import io.github.delder004.ratelimiter.BurstyRateLimiter
import io.github.delder004.ratelimiter.withPermit
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

Returns [`RefundableRateLimiter`](docs/RefundableRateLimiter.md), which also exposes `refund(permits)` when reserved work is abandoned before completion.

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

Returns [`RefundableRateLimiter`](docs/RefundableRateLimiter.md), which also exposes `refund(permits)` when reserved work is abandoned before completion.

#### CompositeRateLimiter

Combines multiple limiters. `acquire()` only proceeds when ALL limiters have capacity. On `tryAcquire()` denial, every remaining child is probed for its `retryAfter` and the longest is returned. Use for APIs with layered limits.

```kotlin
val limiter = CompositeRateLimiter(
    BurstyRateLimiter(permits = 10, per = 1.seconds),
    BurstyRateLimiter(permits = 1000, per = 24.hours)
)
```

When every child is one of this library's own limiters, `acquire()` reserves all children up front and suspends once for the longest reservation. Partial grants are rolled back automatically on cancellation or if a later limiter denies the request. See [`docs/CompositeRateLimiter.md`](docs/CompositeRateLimiter.md) for the full semantics, including the third-party fallback path.

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

### Per-Route HTTP Client

Wrap any HTTP client with per-route rate limits. Routes are matched in declaration order, the first match wins, and a matched route can be composed with a global limiter so both quotas apply to the same request. See [`docs/RateLimitedHttpClient.md`](docs/RateLimitedHttpClient.md) for a regex variant and more details.

```kotlin
data class RouteRule(
    val prefix: String,
    val limiter: RefundableRateLimiter,
)

class RateLimitedClient(
    private val client: HttpClient = HttpClient(),
    private val defaultLimiter: RefundableRateLimiter? = BurstyRateLimiter(permits = 100, per = 1.minutes),
    private val routeLimiters: List<RouteRule> = emptyList(),
) {
    suspend fun <T> request(route: String, block: suspend HttpClient.() -> T): T {
        val routeLimiter = routeLimiters
            .firstOrNull { route == it.prefix || route.startsWith("${it.prefix}/") }
            ?.limiter

        val limiter = when {
            defaultLimiter != null && routeLimiter != null -> CompositeRateLimiter(defaultLimiter, routeLimiter)
            routeLimiter != null -> routeLimiter
            else -> defaultLimiter
        }

        return if (limiter == null) {
            client.block()
        } else {
            limiter.withPermit { client.block() }
        }
    }
}

val client = RateLimitedClient(
    routeLimiters = listOf(
        RouteRule("/search", SmoothRateLimiter(permits = 2, per = 1.seconds)),
        RouteRule("/uploads", BurstyRateLimiter(permits = 5, per = 1.minutes)),
    ),
)

val results = client.request("/search") {
    get("https://api.example.com/search?q=foo")
}
```

### Ktor Client Plugin

If you are already using Ktor, the repo includes a tested client plugin example that installs rate limiting directly into the client pipeline so every outbound request passes through the configured policy.

This plugin is **not** part of the published `kotlin-rate-limiter` artifact. Copy or adapt the example from [`src/examples/kotlin/ratelimiter/examples/KtorClientRateLimitingPlugin.kt`](src/examples/kotlin/ratelimiter/examples/KtorClientRateLimitingPlugin.kt), and see [`docs/KtorClientPluginExample.md`](docs/KtorClientPluginExample.md) for the implementation notes and tests.

```kotlin
val allRoutes = BurstyRateLimiter(permits = 100, per = 1.minutes)
val userReads = BurstyRateLimiter(permits = 20, per = 1.minutes)
val payments = BurstyRateLimiter(permits = 5, per = 1.minutes)

val client = HttpClient {
    install(RateLimitingPlugin) {
        defaultLimiter = allRoutes

        route(
            "/users",
            limiter = CompositeRateLimiter(allRoutes, userReads),
            method = HttpMethod.Get,
        )

        route(
            "/payments",
            limiter = CompositeRateLimiter(allRoutes, payments),
            method = HttpMethod.Post,
        )

        route(
            Regex("/search/\\w+"),
            limiter = CompositeRateLimiter(allRoutes, userReads),
            method = HttpMethod.Get,
        )
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

The library is designed for deterministic testing with `kotlinx-coroutines-test`. Pass `testTimeSource` to tie the rate limiter's clock to virtual time. `testTimeSource` is a `TestScope` extension property from `kotlinx.coroutines.test` — it's shorthand for `testScheduler.timeSource`:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
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

### Why non-suspending bookkeeping?

Limiter state is updated under a short, non-suspending critical section. That lets `tryAcquire()` work naturally as a non-suspending function, keeps `acquire()`'s only suspension point the actual `delay()` for the computed wait, and avoids paying coroutine machinery costs for bookkeeping under contention.

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
