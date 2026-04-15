# SmoothRateLimiter

Distributes permits evenly over time with at most one immediately-available permit. Requests are then spaced at fixed intervals. Optional warmup ramp for gradual start.

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

## Warmup Behavior

When `warmup` is specified, the limiter starts cold and linearly ramps to full speed. The curve works as follows:

- **Cold interval** = 3x the stable interval. With `permits = 10, per = 1.seconds`, the stable interval is 100ms, so the cold interval is 300ms.
- **Linear ramp.** The interval between permits decreases linearly from the cold interval to the stable interval over the warmup duration.
- **Cooldown after idle.** If the limiter sits idle, it cools back down at a constant rate. A full idle period equal to the warmup duration returns it to fully cold. Shorter idle periods result in partial cooldown — the limiter resumes from a partially warm state.

Example with `permits = 10, per = 1.seconds, warmup = 30.seconds`:

```
Cold start        → interval between permits: 300ms (3x stable)
  ↓ linear ramp over 30s
Fully warm        → interval between permits: 100ms (stable rate)
  ↓ idle for 15s
Partially cooled  → interval resumes at ~200ms, ramps back to 100ms
```

## Considerations

- **Use when you need even spacing.** If the downstream service is sensitive to bursts (connection pooling, CPU spikes), smooth is the right choice. For quota-style limits ("100 per minute"), use [BurstyRateLimiter](BurstyRateLimiter.md) instead.
- **Warmup starts at 3x the stable interval.** With `warmup = 30.seconds`, the interval between permits starts 3x slower than the steady-state rate and linearly decreases to the stable rate over the warmup period. This protects cold caches and connection pools.
- **No large bursts.** Unlike bursty, idle time does not bank up a large bucket of permits. The limiter stores at most one immediately-available permit, so after idle there may be one free acquire before smooth pacing resumes.
- **Warmup applies after the stored permit.** After a long idle period, the first acquire may still be immediate, and the following intervals ramp from cold toward the stable rate over the configured warmup period.
- **Same lazy design.** Bookkeeping happens under a short per-instance lock — no background coroutines, no lifecycle to manage, shared safely across coroutines.
- **`refund(permits)` is available.** The factory returns `RefundableRateLimiter`, which adds `refund()` so permits can be returned if work is abandoned.
