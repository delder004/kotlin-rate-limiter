package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SmoothRateLimiterTest : RateLimiterContractTest() {
    override fun TestScope.createLimiter(
        permits: Int,
        per: Duration,
    ): RateLimiter = SmoothRateLimiter(permits, per, Duration.ZERO, testTimeSource)

    // BASIC SMOOTH BEHAVIOR

    @Test
    fun `first permit returned immediately, subsequent permits delayed even when available`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            val delays = recordDelays(limiter, 5)
            val expected: List<Long> = listOf(0, 200, 200, 200, 200)
            assertEquals(expected, delays)
        }

    @Test
    fun `spacing is consistent under steady load`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            val delays = recordDelays(limiter, 10000)
            val expected: List<Long> = (2..10000).map { 200 }
            val cumulative = delays.runningReduce { acc, d -> acc + d }
            assertEquals(expected, cumulative.zipWithNext { a, b -> b - a })
        }

    @Test
    fun `no burst accumulation during idle`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            (1..3).map { limiter.acquire() }
            advanceTimeBy(5.seconds)
            runCurrent()
            val delays = recordDelays(limiter, 10)
            val expected: List<Long> = (2..10).map { 200 }
            val cumulative = delays.runningReduce { acc, d -> acc + d }
            assertEquals(expected, cumulative.zipWithNext { a, b -> b - a })
        }

    @Test
    fun `permits issued never exceed one window of work`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            advanceTimeBy(5.seconds)
            runCurrent()
            val burst1 = launch { limiter.acquire(10) }
            val burst2 = launch { limiter.acquire(10) }
            advanceTimeBy(1.seconds)
            runCurrent()
            assertFalse(burst1.isCompleted)
            assertFalse(burst2.isCompleted)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(burst1.isCompleted)
            assertFalse(burst2.isCompleted)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertFalse(burst2.isCompleted)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(burst2.isCompleted)
        }

    @Test
    fun `tryAcquire Denied includes correct retryAfter`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            limiter.acquire(5)
            val permit = limiter.tryAcquire()
            assertIs<Permit.Denied>(permit)
            assertEquals(200.milliseconds, permit.retryAfter)
        }

    // IDLE and RESUME

    @Test
    fun `idle accumulation capped at 1`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            advanceTimeBy(5.seconds)
            val before = currentTime
            limiter.acquire(5)
            limiter.acquire(1)
            assertEquals(1000, currentTime - before)
        }

    @Test
    fun `acquire after long idle returns immediately`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            advanceTimeBy(10.seconds)
            val before = currentTime
            limiter.acquire(1)
            assertEquals(0L, currentTime - before)
        }

    // MULTI-PERMIT

    @Test
    fun `acquire(n) consumes multiple permits`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            launch { limiter.acquire(3) }
            val delayed = launch { limiter.acquire(3) }
            advanceTimeBy(400.milliseconds)
            runCurrent()
            assertFalse(delayed.isCompleted)
            advanceTimeBy(200.milliseconds)
            runCurrent()
            assertFalse(delayed.isCompleted)
            advanceTimeBy(200.milliseconds)
            runCurrent()
            assertFalse(delayed.isCompleted)
            advanceTimeBy(200.milliseconds)
            runCurrent()
            assertTrue(delayed.isCompleted)
        }

    @Test
    fun `acquire(n) charges proportionally for next caller`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            val job1 = launch { limiter.acquire(5) }
            val job2 = launch { limiter.acquire(5) }
            val job3 = launch { limiter.acquire(1) }
            advanceTimeBy(600.milliseconds)
            runCurrent()
            assertFalse(job1.isCompleted)
            assertFalse(job2.isCompleted)
            assertFalse(job3.isCompleted)
            advanceTimeBy(200.milliseconds)
            runCurrent()
            assertTrue(job1.isCompleted)
            assertFalse(job2.isCompleted)
            assertFalse(job3.isCompleted)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(job2.isCompleted)
            assertFalse(job3.isCompleted)
            advanceTimeBy(200.milliseconds)
            runCurrent()
            assertTrue(job3.isCompleted)
        }

    @Test
    fun `acquire(n) where n more than permits per window`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            val job1 = launch { limiter.acquire(10) }
            val job2 = launch { limiter.acquire(1) }
            runCurrent()
            assertFalse(job1.isCompleted)
            assertFalse(job2.isCompleted)
            advanceTimeBy(1800.milliseconds)
            runCurrent()
            assertTrue(job1.isCompleted)
            assertFalse(job2.isCompleted)
            advanceTimeBy(200.milliseconds)
            runCurrent()
            assertTrue(job2.isCompleted)
        }

    // WARMUP

    @Test
    fun `warmup starts with longer intervals`() =
        runTest {
            // 5 permits/sec = 200ms stable interval, 2 second warmup
            val limiter = SmoothRateLimiter(5, 1.seconds, warmup = 2.seconds, testTimeSource)
            val delays = recordDelays(limiter, 3)

            assertEquals(0L, delays[0], "First acquire should be free")
            assertTrue(delays[1] > 200, "During warmup, interval should exceed stable rate of 200ms, was ${delays[1]}")
            assertTrue(delays[2] > 200, "During warmup, interval should exceed stable rate of 200ms, was ${delays[2]}")
        }

    @Test
    fun `warmup converges to stable rate`() =
        runTest {
            val limiter = SmoothRateLimiter(5, 1.seconds, warmup = 2.seconds, testTimeSource)
            // Acquire enough to fully warm up (well past the 2-second warmup period)
            val delays = recordDelays(limiter, 30)

            // After warmup, intervals should settle at the stable 200ms
            val lastFive = delays.takeLast(5)
            lastFive.forEach { delay ->
                assertEquals(200L, delay, "After warmup, interval should be stable at 200ms")
            }
        }

    @Test
    fun `returns to cold state after idle`() =
        runTest {
            val limiter = SmoothRateLimiter(5, 1.seconds, warmup = 2.seconds, testTimeSource)
            // Warm up fully
            recordDelays(limiter, 30)

            // Go idle for the full warmup duration
            advanceTimeBy(2.seconds)

            // Should be cold again — intervals should be longer than stable
            val delays = recordDelays(limiter, 3)
            assertEquals(0L, delays[0], "First acquire after idle should be free")
            assertTrue(delays[1] > 200, "Should return to cold state after idle >= warmup, was ${delays[1]}")
        }

    @Test
    fun `partial warmup after partial idle`() =
        runTest {
            val limiter = SmoothRateLimiter(5, 1.seconds, warmup = 2.seconds, testTimeSource)

            // Record cold-start intervals
            val coldDelays = recordDelays(limiter, 3)

            // Continue acquiring to fully warm up
            recordDelays(limiter, 30)

            // Partial idle (half the warmup duration)
            advanceTimeBy(1.seconds)

            val partialDelays = recordDelays(limiter, 3)

            // After partial idle, should be between stable and fully cold
            assertEquals(0L, partialDelays[0])
            assertTrue(
                partialDelays[1] > 200,
                "Partial idle should increase interval above stable 200ms, was ${partialDelays[1]}",
            )
            assertTrue(
                partialDelays[1] < coldDelays[1],
                "Partial idle interval (${partialDelays[1]}) should be less than cold-start interval (${coldDelays[1]})",
            )
        }

    @Test
    fun `partial idle cools halfway rather than resetting fully cold`() =
        runTest {
            val limiter = SmoothRateLimiter(5, 1.seconds, warmup = 2.seconds, testTimeSource)

            recordDelays(limiter, 30)
            advanceTimeBy(1.seconds)

            val delays = recordDelays(limiter, 3)

            assertEquals(0L, delays[0], "First acquire after idle should still use the stored permit")
            assertTrue(
                delays[1] in 390L..410L,
                "After half the warmup idle, second interval should be near 400ms, was ${delays[1]}ms",
            )
        }

    @Test
    fun `first warmup interval should be near cold rate`() =
        runTest {
            // 5 permits/sec = 200ms stable, cold = 3x stable = 600ms
            // The first waiting interval from cold should reflect the cold rate,
            // not a partially-warmed rate
            val limiter = SmoothRateLimiter(5, 1.seconds, warmup = 2.seconds, testTimeSource)
            val delays = recordDelays(limiter, 3)
            assertEquals(0L, delays[0], "First acquire should be free")
            assertTrue(
                delays[1] >= 500,
                "First warmup interval should be near cold rate (600ms), was ${delays[1]}ms",
            )
        }

    @Test
    fun `tryAcquire retryAfter is sufficient to acquire on retry`() =
        runTest {
            val limiter = SmoothRateLimiter(5, 1.seconds, warmup = 2.seconds, testTimeSource)
            limiter.acquire()

            // tryAcquire doesn't commit state, so the refill rate during the wait
            // is the pre-removal rate — retryAfter must account for that
            val denied = limiter.tryAcquire()
            assertIs<Permit.Denied>(denied)

            // After waiting exactly retryAfter, a retry should succeed
            advanceTimeBy(denied.retryAfter)
            val retry = limiter.tryAcquire()
            assertIs<Permit.Granted>(retry, "After waiting retryAfter, permit should be granted")
        }

    @Test
    fun `replace does not allow negative warmupPermitsConsumed`() =
        runTest {
            // Directly test PermitBucket: if warmupPermitsConsumed is low and
            // refund() is called, the result should be clamped to >= 0
            val config =
                BucketConfig(
                    capacity = 1.0,
                    timeSource = testTimeSource,
                    stableRefillInterval = 200.milliseconds,
                    warmup = 2.seconds,
                )
            val bucket =
                PermitBucket(
                    available = -1.0,
                    refilledAt = testTimeSource.markNow(),
                    warmupPermitsConsumed = 0.5,
                )
            val restored = bucket.refund(1, config)
            assertTrue(
                restored.warmupPermitsConsumed >= 0.0,
                "warmupPermitsConsumed should not go negative, was ${restored.warmupPermitsConsumed}",
            )
        }

    @Test
    fun `multi-permit acquire from cold uses cold interval`() =
        runTest {
            // 5 permits/sec = 200ms stable, cold = 600ms, 2s warmup
            val limiter = SmoothRateLimiter(5, 1.seconds, warmup = 2.seconds, testTimeSource)

            // acquire(3) from cold: deficit = 2, should wait at the cold rate
            val before = currentTime
            limiter.acquire(3)
            val delay = currentTime - before
            assertTrue(
                delay >= 1000,
                "Multi-permit acquire from cold should reflect cold interval (~1200ms), was ${delay}ms",
            )
        }

    @Test
    fun `cancellation during warmup preserves correct warmup delay`() =
        runTest {
            val limiter = SmoothRateLimiter(5, 1.seconds, warmup = 2.seconds, testTimeSource)

            // First acquire is free, advances warmup to wpc=1
            limiter.acquire()

            // Second acquire will wait — cancel it mid-delay
            val job = launch { limiter.acquire() }
            runCurrent()
            job.cancel()
            runCurrent()

            // After cancel, state restored to wpc=1, warmth=0.2, interval=520ms
            // The next wait should reflect the pre-removal cold-ish interval
            val before = currentTime
            limiter.acquire()
            assertTrue(
                currentTime - before >= 500,
                "Post-cancellation warmup delay should reflect cold interval, was ${currentTime - before}ms",
            )
        }

    @Test
    fun `warmup zero disables warmup`() =
        runTest {
            val limiter = SmoothRateLimiter(5, 1.seconds, warmup = Duration.ZERO, testTimeSource)
            val delays = recordDelays(limiter, 4)
            assertEquals(listOf(0L, 200L, 200L, 200L), delays)
        }
}
