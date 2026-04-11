# RateLimiter

The core interface. All rate limiter types implement this.

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

## `acquire(permits)`

Suspends until the requested permits are available, then returns.

```kotlin
val limiter = BurstyRateLimiter(permits = 5, per = 1.seconds)

limiter.acquire()      // consumes 1 permit
limiter.acquire(3)     // consumes 3 permits
```

### Considerations

- **Cancellation-safe.** If a coroutine is cancelled while suspended in `acquire()`, the permits are returned to the limiter via CAS. No permits are leaked.
- **Current caller pays the wait.** `acquire()` reserves permits atomically, then suspends the calling coroutine for the required delay. If permits are not currently available, the caller that made the request waits before `acquire()` returns.
- **Cancellation restores permits for future callers.** If a suspended `acquire()` is cancelled, its permits are refunded. This does not retroactively shorten delays that other waiters already computed before the cancellation.
- **Positive permits only.** `acquire(permits)` requires `permits > 0` and throws `IllegalArgumentException` otherwise.
- **Timeouts.** There's no built-in timeout parameter. Use the standard library:

```kotlin
withTimeout(3.seconds) {
    limiter.acquire()
}
```

## `tryAcquire(permits)`

Returns immediately without suspending. Returns `Permit.Granted` if permits were available, or `Permit.Denied(retryAfter)` with a duration hint if not.

```kotlin
when (val permit = limiter.tryAcquire()) {
    is Permit.Granted -> {
        val fresh = api.fetch(id)
        cache.put(id, fresh)
        fresh
    }
    is Permit.Denied -> {
        // permit.retryAfter tells you how long to wait
        cache.get(id) ?: throw ServiceUnavailableException()
    }
}
```

### Considerations

- **Non-suspending.** Can be called from non-coroutine code.
- **Does not consume permits on denial.** Only `Granted` results consume permits.
- **`retryAfter` is a hint.** It reflects the limiter's state at the time of the call. Other coroutines may acquire permits before you retry.
- **Positive permits only.** `tryAcquire(permits)` requires `permits > 0` and throws `IllegalArgumentException` otherwise.

## Testing

Inject `testTimeSource` (a `TestScope` extension property from `kotlinx.coroutines.test`, shorthand for `testScheduler.timeSource`) to tie the limiter to virtual time:

```kotlin
@Test
fun `rate limits requests`() = runTest {
    val limiter = BurstyRateLimiter(
        permits = 1,
        per = 1.seconds,
        timeSource = testTimeSource
    )

    limiter.acquire()  // instant — 1 permit available

    val start = currentTime
    limiter.acquire()  // suspends — no permits left
    assertEquals(1000, currentTime - start)
}
```

For tests that don't care about rate limiting, use a no-op:

```kotlin
val noOpLimiter = object : RateLimiter {
    override suspend fun acquire(permits: Int) {}
    override fun tryAcquire(permits: Int) = Permit.Granted
}
```
