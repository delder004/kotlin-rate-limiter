# SmoothRateLimiter

Distributes permits evenly over time. No bursting — requests are spaced at fixed intervals. Optional warmup ramp for gradual start.

```kotlin
fun SmoothRateLimiter(
    permits: Int,
    per: Duration,
    warmup: Duration = Duration.ZERO,
    timeSource: TimeSource = TimeSource.Monotonic,
): RefundableRateLimiter
```

## Usage

```kotlin
// 10 permits per second = 1 permit every 100ms
val limiter = SmoothRateLimiter(permits = 10, per = 1.seconds)
```

### Even load on a downstream service

```kotlin
val limiter = SmoothRateLimiter(permits = 2, per = 1.seconds)

urlFlow
    .rateLimit(limiter)
    .map { url -> httpClient.get(url).bodyAsText() }
    .map { html -> parseListings(html) }
    .collect { listings -> db.insertAll(listings) }
```

### Bandwidth limiting with multi-permit acquire

```kotlin
val bandwidthLimiter = SmoothRateLimiter(
    permits = 10_000_000,  // 10 MB/s
    per = 1.seconds
)

suspend fun uploadChunk(data: ByteArray) {
    bandwidthLimiter.acquire(permits = data.size)
    s3Client.putObject(data)
}
```

### Warmup for cold services

```kotlin
val limiter = SmoothRateLimiter(
    permits = 100,
    per = 1.seconds,
    warmup = 30.seconds  // ramp from slow to full rate over 30s
)
```

## Considerations

- **Use when you need even spacing.** If the downstream service is sensitive to bursts (connection pooling, CPU spikes), smooth is the right choice. For quota-style limits ("100 per minute"), use [BurstyRateLimiter](BurstyRateLimiter.md) instead.
- **Warmup starts at 3x the stable interval.** With `warmup = 30.seconds`, the interval between permits starts 3x slower than the steady-state rate and linearly decreases to the stable rate over the warmup period. This protects cold caches and connection pools.
- **No permit accumulation.** Unlike bursty, idle time does not bank up permits for a later burst. The limiter always enforces even spacing regardless of how long it's been idle.
- **Same lock-free, lazy design.** No background coroutines, no lifecycle to manage. Shared safely across coroutines.
- **`refund(permits)` is available.** Returns `RefundableRateLimiter`, so you can return permits if work is abandoned.
