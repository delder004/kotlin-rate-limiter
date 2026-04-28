# CompositeRateLimiter

Combines multiple [RefundableRateLimiter](RefundableRateLimiter.md)s into a single `RateLimiter`. An acquire succeeds only if every child limiter can grant the same request.

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
- **`acquire()` reserves all children up front when possible.** When every child is one of this library's own limiters, the composite reserves a permit from each child synchronously, then suspends once for the longest reservation. On cancellation, every reservation is rolled back in reverse order with an exact rewind. If any child is a third-party `RefundableRateLimiter`, the composite falls back to a sequential acquire-and-rollback path: it acquires from each child in turn and refunds earlier grants if a later acquire fails or is cancelled.
- **Rollback is sequential, not globally atomic.** Children are refunded one at a time in reverse order. A concurrent observer that touches a child limiter directly mid-rollback could see a partially rolled-back state.
- **`tryAcquire()` denial probes every remaining child.** When a child denies, earlier grants are refunded and the composite then inspects every later child to find the longest `retryAfter`. For this library's own limiters this probe is non-mutating (no permits are acquired or refunded). For third-party [RefundableRateLimiter](RefundableRateLimiter.md)s the probe falls back to `tryAcquire` + `refund`, inheriting whatever semantics that pair provides.
- **Requires refundable children.** The factory accepts [RefundableRateLimiter](RefundableRateLimiter.md)s because rollback depends on `refund()`.
- **Positive permits only.** Like other limiters, `permits` must be greater than zero.
