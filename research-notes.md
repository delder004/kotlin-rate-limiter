# Research Notes

Compiled from landscape analysis, prior art review, and design discussions.

## The Gap

kotlinx.coroutines has had an open issue (#460) since July 2018 requesting a suspendable RateLimiter. A community PR (#2799) was submitted in June 2021, received feedback from Google's Guava team, and was closed in February 2024 without being merged. The maintainer (qwwdfsad) stated the biggest problem is deciding "what exactly is this primitive" — not the implementation itself.

No existing Kotlin library provides a coroutine-native, lightweight, testable-with-virtual-time rate limiter as a standalone dependency.

## Existing Libraries

### Guava RateLimiter
- Java, blocking (not suspend)
- Token bucket with smooth and bursty variants
- Injectable `SleepingStopwatch` for testing
- Excellent test suite (~570 lines) with event recording pattern
- No `tryAcquire` with `retryAfter` — returns boolean
- License: Apache 2.0

### resilience4j (+ resilience4j-kotlin)
- Java core with Kotlin suspend extensions
- `AtomicRateLimiter` with injectable `nanoTimeSupplier`
- Fixed window algorithm (not token bucket)
- Flow extension has a bug: rate limits per-collection, not per-emission (#1416)
- Part of a large resilience framework — heavy dependency

### Bucket4k
- Kotlin wrapper around Java Bucket4j
- Suspending functions via lock-free bucket
- JVM only, depends on Bucket4j
- No `tryAcquire` with retry info

### Krate
- Kotlin, token bucket with Redis support
- Not coroutine-native internally
- `awaitUntilTake()` for suspend behavior

### kmp-resilient (from this course)
- KMP, coroutine-native
- Token bucket rate limiter as part of broader resilience DSL
- Covers retry, circuit breaker, bulkhead, hedging, cache, fallback
- Our library is more focused — just rate limiting

## Key Insights from PR #2799 Discussion

### From lowasser (Google/Guava team):
- Support many permits per operation (e.g. bytes-per-second bandwidth limiting)
- Use proper time types from `kotlin.time`
- Distinguish "hertz" rate limits (smooth, evenly distributed) from "N per Duration" (bursty)
- Burstiness can be controlled as intersection of rate limits
- Warmup/cooldown support is useful

### From qwwdfsad (kotlinx.coroutines maintainer):
- Biggest question is whether this belongs in core library or separate library
- Wants "a single primitive good enough for the vast majority of uses"
- This is the hardest question in the whole issue

### From the community:
- Must support bursts (the #1 complaint about the PR)
- Must have `tryAcquire` for non-blocking checks
- Don't use `System.currentTimeMillis()` — use monotonic time
- `delay()` coerces to at least 1ms — matters at very high rates (>1000/sec)
- Should live alongside `Mutex` and `Semaphore` conceptually

## Rate Limiting Algorithms

### Token Bucket (our bursty implementation)
- Bucket holds up to N tokens
- Tokens added at a steady rate (e.g. 1 every 100ms for 10/sec)
- Each request takes a token
- If empty, wait for next token
- Allows bursts when tokens have accumulated during idle time
- Used by: Guava (SmoothBursty), AWS, Stripe

### Leaky Bucket (our smooth implementation)
- Requests processed at a constant rate regardless of arrival pattern
- No bursting — creates perfectly even load
- Used by: NGINX

### Fixed Window
- Counter resets every N seconds
- Simple but has edge-case: 2x burst at window boundary
- Used by: resilience4j

### Sliding Window
- Tracks timestamps of recent requests
- Smoother than fixed window, no boundary burst
- Higher memory cost (stores all timestamps)

### For our library:
- Bursty = token bucket (the most common and useful)
- Smooth = leaky bucket (even distribution)
- We skip fixed/sliding window — they're server-side patterns

## Client-Side Use Cases

1. **Third-party API compliance** — stay under documented rate limits proactively
2. **Cost control** — prevent runaway loops from incurring charges on paid APIs
3. **Bandwidth limiting** — cap upload/download rate using multi-permit acquire
4. **Batch processing** — maximize throughput on bulk operations without 429s
5. **Web scraping** — respect robots.txt / rate limit headers
6. **Concurrent coroutine coordination** — many coroutines sharing one API limit
7. **Multi-tier API limits** — composite limiter for layered limits (10/sec AND 1000/day)

## Guava Test Patterns (from RateLimiterTest.java)

### Event Recording
Tests record the delay for each acquire as a string event (e.g. "R0.20" for rate-limiter-caused 200ms delay, "U1.00" for user-caused 1s sleep). The full sequence is asserted at end.

Our equivalent: record `currentTime` before/after each acquire, compute deltas, assert the list.

### Key Test Categories from Guava
1. Simple acquire with expected delays
2. Acquire after user wait (idle doesn't penalize)
3. One-second burst capacity (idle accumulates up to 1 second of permits)
4. Weighted/multi-permit acquire (acquire(4) costs 4x for next caller)
5. Warmup (decreasing delays during warmup period)
6. Rate update mid-use
7. Infinity rate edge cases
8. Burst cap never exceeds configured limit
9. tryAcquire with zero timeout, some timeout, overflow
10. Parameter validation (zero permits, negative values)
11. Time-to-warmup is honored regardless of permit weights (fuzz test)

### Key Insight: Guava's `FakeStopwatch`
Controls time completely. Both the "user sleeps" and "rate limiter delays" go through the same fake time source. This is exactly what `testScheduler.timeSource` + `delay()` virtual time gives us in kotlinx-coroutines-test.

## Real-World API Rate Limits (for context)

- Twitter: 300 tweets per 3 hours
- Google Docs API: 300 read requests per user per 60 seconds
- ATTOM API: tiered, ~10 requests per second on basic plans
- OpenWeatherMap free tier: 60 calls/minute, 1,000,000/month
- GitHub API: 5,000 requests/hour (authenticated)
- Stripe API: 100 requests/second

## Links

- kotlinx.coroutines issue #460: https://github.com/Kotlin/kotlinx.coroutines/issues/460
- kotlinx.coroutines PR #2799: https://github.com/Kotlin/kotlinx.coroutines/pull/2799
- Guava RateLimiter source: https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/RateLimiter.java
- Guava RateLimiter tests: https://github.com/google/guava/blob/master/guava-tests/test/com/google/common/util/concurrent/RateLimiterTest.java
- Guava SmoothRateLimiter source: https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/SmoothRateLimiter.java
- resilience4j Kotlin extensions: https://resilience4j.readme.io/docs/getting-started-4
- resilience4j Flow bug: https://github.com/resilience4j/resilience4j/issues/1416
- Bucket4k: https://github.com/ksletmoe/Bucket4k
- Krate: https://github.com/lpicanco/krate
- kmp-resilient: https://github.com/santimattius/kmp-resilient
- Top coroutines course projects: https://kt.academy/article/top-projects-coroutines-2025
- kotlinx-coroutines-test docs: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/
