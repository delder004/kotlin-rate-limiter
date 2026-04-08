# CompositeRateLimiter

Combines multiple `RefundableRateLimiter`s into a single `RateLimiter`. An acquire succeeds only if every child limiter can grant the same request.

```kotlin
fun CompositeRateLimiter(
    vararg limiters: RefundableRateLimiter,
): RateLimiter
```

## Usage

```kotlin
val perSecond = BurstyRateLimiter(permits = 10, per = 1.seconds)
val perMinute = BurstyRateLimiter(permits = 500, per = 1.minutes)

val limiter = CompositeRateLimiter(perSecond, perMinute)
```

### Apply multiple quotas at once

```kotlin
suspend fun fetchUser(id: String): User = limiter.withPermit {
    api.fetchUser(id)
}
```

### Fail fast with `tryAcquire()`

```kotlin
when (val permit = limiter.tryAcquire()) {
    is Permit.Granted -> process()
    is Permit.Denied -> delay(permit.retryAfter)
}
```

## Considerations

- **All child limiters must grant.** A request succeeds only if every child limiter can satisfy it.
- **Partial grants are rolled back.** If one child denies or a later `acquire()` fails, any permits already acquired from earlier child limiters are refunded before returning or rethrowing.
- **`tryAcquire()` returns the longest `retryAfter`.** If multiple child limiters would deny the request, the composite denial uses the maximum retry duration across them.
- **Requires refundable children.** The factory accepts `RefundableRateLimiter`s because rollback depends on `refund()`.
- **Positive permits only.** Like other limiters, `permits` must be greater than zero.
