# Testing Plan

## Testing Approach

All tests use `kotlinx-coroutines-test` with virtual time. Rate limiters are created with `testScheduler.timeSource` so `delay()` calls and time-based refill calculations share the same virtual clock. Tests run instantly regardless of the durations involved.

```kotlin
@Test
fun `example test structure`() = runTest {
    val limiter = BurstyRateLimiter(
        permits = 5,
        per = 1.seconds,
        timeSource = testScheduler.timeSource
    )
    // advanceTimeBy, advanceUntilIdle, runCurrent, currentTime available
}
```

## Test Pattern: Delay Recording

Inspired by Guava's `FakeStopwatch` pattern. Many tests record the delay observed for each `acquire()` call and assert the full sequence:

```kotlin
val delays = mutableListOf<Long>()
repeat(n) {
    val before = currentTime
    limiter.acquire()
    delays.add(currentTime - before)
}
assertEquals(listOf(0L, 0L, 200L, 200L), delays)
```

## Test Matrix

### RateLimiterContractTest (abstract)

Tests the interface contract — invariants that must hold for ANY `RateLimiter` implementation. Does not assert timing, replenish patterns, or burst behavior. Each subclass overrides a factory method:

```kotlin
abstract class RateLimiterContractTest {
    abstract fun TestScope.createLimiter(permits: Long, per: Duration): RateLimiter
}
```

#### Core Semantics
- **`acquire suspends when permits exhausted`** — exhaust permits, launch a coroutine that acquires, verify it doesn't proceed until time advances
- **`acquire resumes after sufficient time`** — exhaust permits, advance time, verify next acquire eventually succeeds
- **`tryAcquire returns Granted when permits available`**
- **`tryAcquire returns Denied when exhausted`**
- **`tryAcquire Denied includes positive retryAfter`** — retryAfter should be > Duration.ZERO
- **`tryAcquire does not consume permits on Denied`** — denied, advance time, try again — should succeed

#### Parameter Validation
- **`acquire rejects zero permits`** — `assertFailsWith<IllegalArgumentException>`
- **`acquire rejects negative permits`**
- **`tryAcquire rejects zero permits`**
- **`factory rejects zero permits`**
- **`factory rejects zero duration`**
- **`factory rejects negative duration`**

#### Cancellation
- **`cancelled coroutine restores permits`** — acquire all permits, launch a coroutine that acquires (suspends), cancel it, verify permits are restored by successfully acquiring from another coroutine
- **`cancellation during delay does not consume permit`** — coroutine is cancelled while in `delay()` during acquire. Permit should be returned.
- ~~**`other waiters proceed after cancellation`**~~ — removed. The "reserve then delay" design pre-computes wait durations; cancelled permits are returned to the bucket but don't wake existing waiters. Fixing this would require signaling (SharedFlow/Mutex), which conflicts with the lock-free CAS design.

### BurstyRateLimiterTest (extends RateLimiterContractTest)

Inherits all contract tests. Tests token bucket behavior:

#### Basic Behavior
- **`acquire returns immediately when permits available`** — create limiter with 5 permits, acquire 5 times, all should have 0ms delay
- **`permits replenish at correct rate`** — 5 permits per 1 second = 1 every 200ms. Exhaust all, then verify timing of subsequent acquires

#### Multi-Permit
- **`acquire(n) consumes multiple permits`** — acquire(3) from a 5-permit limiter, then acquire(3) should wait (only 2 left)
- **`acquire(n) charges proportionally for next caller`** — 5-permit limiter, 1s period (interval = 200ms). acquire(1) is instant, acquire(4) is instant (4 remaining), next acquire(1) waits 1 interval (200ms) because bucket is at 0. Assert delay sequence like `[0, 0, 1*interval]`. For deeper debt: acquire(5) instant, acquire(5) waits 5 intervals, acquire(1) waits 6 intervals.
- **`acquire(n) where n > permits per window`** — acquire(10) from a 5/sec limiter. Should work but the next caller waits a long time

#### Burst Behavior
- **`burst up to permit count after idle`** — let limiter sit idle for 2 seconds, then rapidly acquire 5 times. All should be instant (burst). 6th should wait.
- **`idle accumulation capped at permit count`** — idle for 10 seconds with 5 permits/sec, verify only 5 instant acquires (bucket size = rate)
- **`burst never exceeds one window of work`** — Guava's `testWeNeverGetABurstMoreThanOneSec` adapted. After long idle, burst completes within one period, but subsequent work takes at least one period.

#### Idle and Resume
- **`idle time is not penalized`** — acquire, wait 200ms (>= interval), acquire again. Second should be instant.
- **`acquire after long idle returns immediately`** — idle for 10 seconds, next acquire is instant

#### tryAcquire
- **`tryAcquire Denied includes correct retryAfter`** — retryAfter should reflect actual time until next permit based on token bucket state

### SmoothRateLimiterTest (extends RateLimiterContractTest)

Inherits all contract tests. Tests leaky bucket behavior:

#### Even Spacing
- **`permits are evenly spaced`** — 4 permits per 1 second. Delays should be [0, 250, 250, 250]
- **`no burst accumulation during idle`** — idle for 5 seconds, acquire twice rapidly. Second should still wait the full interval (unlike bursty)
- **`spacing is consistent under steady load`** — 20 acquires at 10/sec, all gaps should be ~100ms

#### Multi-Permit
- **`acquire(n) consumes multiple permits`** — acquire(3) from a 4/sec limiter, verify next acquire waits proportionally
- **`acquire(n) charges proportionally for next caller`** — even spacing version of the Guava pattern

#### Warmup (if implemented)
- **`warmup starts with longer intervals`** — first few acquires have delays > stable interval
- **`warmup converges to stable rate`** — after warmup period, delays match stable interval
- **`returns to cold state after idle`** — idle for warmup duration, next series starts slow again
- **`partial warmup after partial idle`** — idle for less than warmup duration, intervals are between cold and stable

#### tryAcquire
- **`tryAcquire Denied includes correct retryAfter`** — retryAfter should reflect the even spacing interval

### CompositeRateLimiterTest

- **`acquire succeeds when all limiters have capacity`**
- **`acquire suspends when any limiter is exhausted`** — two limiters, exhaust only one, acquire should still suspend
- **`respects most restrictive limiter`** — 10/sec + 2/sec composite, should pace at 2/sec
- **`tryAcquire returns Denied if any limiter denies`**
- **`tryAcquire returns longest retryAfter`** — if limiter A says retry in 100ms and limiter B says 500ms, return 500ms
- **`both limiters consume permits on acquire`** — acquire from composite, then tryAcquire on individual limiters to verify both consumed
- **`layered limits work correctly`** — 5/sec + 20/minute composite. Short burst of 5 works, but over a minute only 20 total

### ExtensionsTest

#### withPermit
- **`withPermit acquires and executes block`** — block runs and returns result
- **`withPermit propagates exceptions`** — block throws, exception propagates out of withPermit (permit is consumed)
- **`withPermit acquires before running block`** — with exhausted limiter, block should not run until permit is available

#### Flow.rateLimit
- **`each emission is individually rate limited`** — THE resilience4j bug. Emit 5 items through a 2/sec limiter. Should take ~2 seconds, not be instant.
- **`backpressure propagates correctly`** — slow collector doesn't cause issues
- **`cancelling collection cancels limiter wait`** — collect a few items then cancel, verify no hanging coroutines

### ConcurrencyTest

- **`multiple coroutines collectively stay under limit`** — launch 50 coroutines all acquiring from a 10/sec limiter. Track timestamps. Verify no more than 10 acquires in any 1-second window.
- **`no permits lost under contention`** — launch 100 coroutines, each acquiring once from a 100-permit limiter. All should complete without waiting (100 permits available). Verify all 100 completed.
- **`fairness under contention`** — launch coroutines in order, verify they complete in roughly FIFO order (not strict, but early launchers should generally finish before late ones)
- **`concurrent acquire and tryAcquire don't interfere`** — mix of acquire and tryAcquire calls from different coroutines, verify no crashes or inconsistent state

### FuzzTest

Property-based tests using random inputs with fixed seeds. Key invariant: total elapsed virtual time is deterministic given total permits consumed, regardless of order or sizes.

- **`total time matches total permits consumed`** — sequential random acquires (random sizes 1-5, 50 iterations). Assert `elapsed == max(0, totalPermits - capacity) * interval`. Run with multiple seeds.
- **`no permits lost under concurrent random access`** — launch 100 concurrent coroutines with random acquire sizes (1-3). All must complete. Assert total elapsed time matches expected based on total permits consumed.
- **`tryAcquire denials don't affect timing`** — random mix of acquire and tryAcquire (100 iterations). Denied tryAcquires must not affect permit accounting. Assert elapsed time >= expected time from acquires alone.

## Test Infrastructure

### Helper Functions

```kotlin
// Record delays for a series of acquires
suspend fun TestScope.recordDelays(limiter: RateLimiter, count: Int): List<Long> {
    return (1..count).map {
        val before = currentTime
        limiter.acquire()
        currentTime - before
    }
}

// Assert that no more than `limit` events occurred in any `window`-sized slice
fun assertRateNotExceeded(timestamps: List<Long>, limit: Int, windowMs: Long) {
    for (start in timestamps) {
        val count = timestamps.count { it in start..(start + windowMs) }
        assertTrue(count <= limit, "Rate exceeded: $count events in ${windowMs}ms window starting at $start")
    }
}
```

## Reference Test Sources

- **Guava RateLimiterTest.java** — `github.com/google/guava/blob/master/guava-tests/test/com/google/common/util/concurrent/RateLimiterTest.java` (570 lines, injectable FakeStopwatch, assertEvents pattern)
- **resilience4j AtomicRateLimiterTest** — `github.com/resilience4j/resilience4j/tree/master/resilience4j-ratelimiter/src/test/java/io/github/resilience4j/ratelimiter/internal`
- **resilience4j Flow bug #1416** — `github.com/resilience4j/resilience4j/issues/1416` (rate limiting per-collection instead of per-emission)
