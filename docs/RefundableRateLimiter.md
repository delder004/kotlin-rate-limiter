# RefundableRateLimiter

A [RateLimiter](RateLimiter.md) whose acquired permits can be returned to the limiter via `refund()`.

```kotlin
interface RefundableRateLimiter : RateLimiter {
    fun refund(permits: Int)
}
```

All three factory functions return `RefundableRateLimiter`:

- [BurstyRateLimiter](BurstyRateLimiter.md)
- [SmoothRateLimiter](SmoothRateLimiter.md)
- [CompositeRateLimiter](CompositeRateLimiter.md) requires its children to be refundable so it can roll back partial grants.

## Usage

Refund when you successfully acquired a permit but then decided not to do the work it was reserved for.

```kotlin
val limiter = BurstyRateLimiter(permits = 10, per = 1.seconds)

suspend fun fetchIfFresh(id: String): User? {
    limiter.acquire()
    if (cache.isFresh(id)) {
        limiter.refund(1)
        return cache.get(id)
    }
    return api.fetchUser(id)
}
```

## Considerations

- **Refunds are best-effort, not bookkeeping.** `refund()` releases capacity for *future* callers. It does not retroactively shorten delays that other waiters have already computed. Don't use it as a transactional rollback mechanism.
- **Refund only what you acquired.** Refunding more permits than you acquired makes the limiter behave as if it had been idle longer — it will hand out a burst on the next acquire (bursty) or skip ahead in its schedule (smooth). The library does not track outstanding permits per caller.
- **Positive permits only.** `refund(permits)` requires `permits > 0` and throws `IllegalArgumentException` otherwise.
- **Cancellation already refunds.** If a coroutine is cancelled while suspended in `acquire()`, the limiter refunds the reservation automatically. You don't need to wrap `acquire()` in `try { ... } catch (CancellationException) { refund(...) }`.
- **Exactness depends on the limiter.** For `BurstyRateLimiter` and the zero-warmup `SmoothRateLimiter`, `refund()` rewinds the schedule exactly. For the warming `SmoothRateLimiter`, refunds called directly (outside the cancellation fast path) are bounded best-effort — see [SmoothRateLimiter](SmoothRateLimiter.md) for the envelope.
