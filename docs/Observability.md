# Observability

The library intentionally ships without metrics dependencies. Since `RateLimiter` is an interface, you can add observability by wrapping any limiter in a decorator.

## MeteredRateLimiter

A minimal decorator that tracks acquire counts, denied counts, and total wait time:

```kotlin
class MeteredRateLimiter(
    private val delegate: RateLimiter,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : RateLimiter {

    private val _acquireCount = AtomicLong(0)
    private val _denyCount = AtomicLong(0)
    private val _totalWaitNanos = AtomicLong(0)

    val acquireCount: Long get() = _acquireCount.get()
    val denyCount: Long get() = _denyCount.get()
    val totalWaitTime: Duration get() = _totalWaitNanos.get().nanoseconds

    override suspend fun acquire(permits: Int) {
        val mark = timeSource.markNow()
        delegate.acquire(permits)
        _totalWaitNanos.addAndGet(mark.elapsedNow().inWholeNanoseconds)
        _acquireCount.incrementAndGet()
    }

    override fun tryAcquire(permits: Int): Permit {
        val result = delegate.tryAcquire(permits)
        when (result) {
            is Permit.Granted -> _acquireCount.incrementAndGet()
            is Permit.Denied -> _denyCount.incrementAndGet()
        }
        return result
    }
}
```

### Usage

```kotlin
val limiter = MeteredRateLimiter(
    delegate = BurstyRateLimiter(permits = 100, per = 1.minutes)
)

// Use it like any RateLimiter
limiter.withPermit { api.fetch(id) }

// Read counters
println("Acquires: ${limiter.acquireCount}")
println("Denials: ${limiter.denyCount}")
println("Total wait: ${limiter.totalWaitTime}")
```

### Micrometer integration

Feed the counters into Micrometer gauges for dashboards and alerting:

```kotlin
val registry: MeterRegistry = ...

Gauge.builder("rate_limiter.acquires", limiter) { it.acquireCount.toDouble() }
    .tag("limiter", "api")
    .register(registry)

Gauge.builder("rate_limiter.denials", limiter) { it.denyCount.toDouble() }
    .tag("limiter", "api")
    .register(registry)

Gauge.builder("rate_limiter.wait_seconds", limiter) { it.totalWaitTime.inWholeMilliseconds / 1000.0 }
    .tag("limiter", "api")
    .register(registry)
```

## Considerations

- **Does not wrap `refund()`.** This decorator implements `RateLimiter`, not `RefundableRateLimiter`. If you need to meter refunds, extend the pattern to delegate `refund()` as well.
- **Counters are monotonic.** They grow for the lifetime of the instance. For windowed rates, use your metrics library's rate functions (e.g., Micrometer's `FunctionCounter`).
- **Thread-safe.** `AtomicLong` counters are safe for concurrent use, matching the limiter's own concurrency guarantees.
