# BurstyRateLimiter

Token bucket algorithm. Permits accumulate over time up to a maximum, allowing bursts of requests followed by paced consumption.

```kotlin
fun BurstyRateLimiter(
    permits: Int,
    per: Duration,
    timeSource: TimeSource = TimeSource.Monotonic,
): RefundableRateLimiter
```

## Usage

```kotlin
val limiter = BurstyRateLimiter(permits = 100, per = 1.minutes)

// All 100 calls proceed immediately (burst), then paces at ~1.67/sec
repeat(100) {
    limiter.withPermit { api.call() }
}
```

### Batch processing with concurrency

```kotlin
val limiter = BurstyRateLimiter(permits = 10, per = 1.seconds)

suspend fun enrichAll(addresses: List<String>): List<Property> =
    coroutineScope {
        addresses.map { address ->
            async {
                limiter.withPermit { api.fetchProperty(address) }
            }
        }.awaitAll()
    }
```

### Cost control on a paid API

```kotlin
val dailyLimiter = BurstyRateLimiter(permits = 10_000, per = 24.hours)

suspend fun checkCredit(userId: String): Score = dailyLimiter.withPermit {
    creditApi.check(userId)  // $0.10 per call
}
```

## Considerations

- **Use when an API specifies a quota.** "100 requests per minute" maps directly to `BurstyRateLimiter(permits = 100, per = 1.minutes)`.
- **Burst then pace.** All accumulated permits can be consumed instantly. If you need evenly-spaced requests, use [SmoothRateLimiter](SmoothRateLimiter.md) instead.
- **Permits refill lazily.** No background coroutine or timer. Permits are recalculated based on elapsed time each time you call `acquire()` or `tryAcquire()`. This means no `CoroutineScope` is needed and there's nothing to clean up.
- **Shared across coroutines.** A single limiter instance coordinates all coroutines that call it. Bookkeeping happens under a short per-instance lock — no coroutine suspension, no background work.
- **`refund(permits)` is available.** The factory returns `RefundableRateLimiter`, which adds `refund()` to return permits to the bucket if an operation is abandoned before completion.
