# Extensions

Convenience functions for common rate limiting patterns.

## `withPermit`

Acquires permits, runs a suspending block, and returns its result.

```kotlin
suspend fun <T> RateLimiter.withPermit(
    permits: Int = 1,
    block: suspend () -> T,
): T
```

### Usage

```kotlin
val limiter = BurstyRateLimiter(permits = 10, per = 1.seconds)

val property = limiter.withPermit {
    api.fetchProperty(address)
}
```

With multi-permit:

```kotlin
val result = limiter.withPermit(permits = 5) {
    api.bulkFetch(ids)
}
```

### Considerations

- **Syntactic sugar for `acquire()` + block.** Equivalent to calling `limiter.acquire(permits)` followed by `block()`.
- **Does not catch exceptions.** If the block throws, the exception propagates normally. Permits are consumed regardless of block outcome.

## `Flow.rateLimit`

Applies rate limiting per emission in a Flow pipeline.

```kotlin
fun <T> Flow<T>.rateLimit(
    limiter: RateLimiter,
    permits: Int = 1,
): Flow<T>
```

### Usage

```kotlin
val limiter = SmoothRateLimiter(permits = 2, per = 1.seconds)

urlFlow
    .rateLimit(limiter)
    .map { url -> httpClient.get(url).bodyAsText() }
    .collect { save(it) }
```

With variable cost per item:

```kotlin
chunkFlow
    .rateLimit(bandwidthLimiter, permits = 1024)
    .collect { chunk -> upload(chunk) }
```

### Considerations

- **Per-emission, not per-collection.** Each element emitted by the upstream flow acquires permits before being emitted downstream. If multiple collectors exist, they all share the same limiter.
- **Backpressure-friendly.** The flow suspends naturally during `acquire()`, applying backpressure upstream without buffering.
- **Position matters.** Place `.rateLimit()` before the operator you want to throttle:

```kotlin
// Correct — rate limits the HTTP calls
urlFlow
    .rateLimit(limiter)
    .map { url -> httpClient.get(url) }

// Wrong — rate limits emission after the HTTP call already happened
urlFlow
    .map { url -> httpClient.get(url) }
    .rateLimit(limiter)
```
