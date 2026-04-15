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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SmoothRateLimiterWarmupTest {
    private fun TestScope.warmupLimiter(warmup: Duration = 2.seconds): RateLimiter =
        SmoothRateLimiter(5, 1.seconds, warmup = warmup, timeSource = testTimeSource)

    @Test
    fun `warmup starts with longer intervals`() =
        runTest {
            val limiter = warmupLimiter()
            val delays = recordDelays(limiter, 3)

            assertEquals(0L, delays[0], "First acquire should be free")
            assertTrue(delays[1] > 200, "During warmup, interval should exceed stable rate of 200ms, was ${delays[1]}")
            assertTrue(delays[2] > 200, "During warmup, interval should exceed stable rate of 200ms, was ${delays[2]}")
        }

    @Test
    fun `warmup converges to stable rate`() =
        runTest {
            val limiter = warmupLimiter()
            val delays = recordDelays(limiter, 30)

            delays.takeLast(5).forEach { delay ->
                assertEquals(200L, delay, "After warmup, interval should be stable at 200ms")
            }
        }

    @Test
    fun `returns to cold state after idle`() =
        runTest {
            val limiter = warmupLimiter()
            recordDelays(limiter, 30)
            advanceTimeBy(2.seconds)

            val delays = recordDelays(limiter, 3)
            assertEquals(0L, delays[0], "First acquire after idle should be free")
            assertTrue(delays[1] > 200, "Should return to cold state after idle >= warmup, was ${delays[1]}")
        }

    @Test
    fun `partial warmup after partial idle`() =
        runTest {
            val limiter = warmupLimiter()
            val coldDelays = recordDelays(limiter, 3)

            recordDelays(limiter, 30)
            advanceTimeBy(1.seconds)

            val partialDelays = recordDelays(limiter, 3)
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
            val limiter = warmupLimiter()
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
            val limiter = warmupLimiter()
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
            val limiter = warmupLimiter()
            limiter.acquire()

            val denied = limiter.tryAcquire()
            assertIs<Permit.Denied>(denied)

            advanceTimeBy(denied.retryAfter)
            val retry = limiter.tryAcquire()
            assertIs<Permit.Granted>(retry, "After waiting retryAfter, permit should be granted")
        }

    @Test
    fun `multi-permit acquire from cold uses cold interval`() =
        runTest {
            val limiter = warmupLimiter()

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
            val limiter = warmupLimiter()
            limiter.acquire()

            val job = launch { limiter.acquire() }
            runCurrent()
            job.cancel()
            runCurrent()

            val before = currentTime
            limiter.acquire()
            assertTrue(
                currentTime - before >= 500,
                "Post-cancellation warmup delay should reflect cold interval, was ${currentTime - before}ms",
            )
        }

    @Test
    fun `cancellation at zero elapsed matches baseline under warmup`() =
        runTest {
            // Baseline with no cancellation.
            val baselineLimiter = warmupLimiter()
            baselineLimiter.acquire()
            val baseline = recordDelays(baselineLimiter, 5)

            // Immediate cancellation should leave the sequence unchanged.
            val cancelLimiter = warmupLimiter()
            cancelLimiter.acquire()
            val job = launch { cancelLimiter.acquire() }
            runCurrent()
            job.cancel()
            runCurrent()
            val afterCancel = recordDelays(cancelLimiter, 5)

            assertEquals(baseline, afterCancel, "post-cancel delays should match baseline")
        }

    @Test
    fun `cancellation after partial delay matches baseline under warmup`() =
        runTest {
            // Baseline after 100ms of ordinary idle time.
            val baselineLimiter = warmupLimiter()
            baselineLimiter.acquire()
            advanceTimeBy(100.milliseconds)
            val baseline = recordDelays(baselineLimiter, 5)

            // Cancelling after 100ms of waiting should match that same idle window.
            val cancelLimiter = warmupLimiter()
            cancelLimiter.acquire()
            val job = launch { cancelLimiter.acquire() }
            runCurrent()
            advanceTimeBy(100.milliseconds)
            job.cancel()
            runCurrent()
            val afterCancel = recordDelays(cancelLimiter, 5)

            assertEquals(baseline, afterCancel, "post-cancel delays should match baseline after partial elapsed")
        }

    @Test
    fun `warmup zero disables warmup`() =
        runTest {
            val limiter = warmupLimiter(warmup = Duration.ZERO)
            val delays = recordDelays(limiter, 4)
            assertEquals(listOf(0L, 200L, 200L, 200L), delays)
        }

    @Test
    fun `cooldown from warm should not complete before warmup duration elapses`() =
        runTest {
            val limiter = warmupLimiter()
            val coldDelays = recordDelays(limiter, 3)

            recordDelays(limiter, 30)
            advanceTimeBy(1500.milliseconds)

            val afterIdleDelays = recordDelays(limiter, 3)
            assertTrue(
                afterIdleDelays[1] < coldDelays[1],
                "After 75% of warmup idle, should retain some warmth. " +
                    "Got ${afterIdleDelays[1]}ms, same as cold start (${coldDelays[1]}ms)",
            )
        }

    // Slow-path cancellation (contention forced the version counter to advance
    // between the reservation and the cancel) cannot produce an exact rewind;
    // the class contract is bounded drift. This test pins the bound at the
    // public-behavior level by comparing a slow-path cancel against a baseline
    // limiter that experiences the same wall-clock without the reservation.
    //
    // With stable=200ms and warmup=2s, cold=600ms, so the documented drift
    // envelope for `permits * (coldInterval - stableInterval)` with permits=1
    // is 400ms. The slow-path next-acquire wait must land in
    // [baseline, baseline + 400ms]: never less than baseline (that would mean
    // the refund over-credited) and never more than baseline + 400ms (that
    // would exceed the drift envelope).
    @Test
    fun `slow-path cancellation drift is bounded by cold-stable gap`() =
        runTest {
            val driftBoundMs = 400L // 1 * (cold 600ms - stable 200ms)

            // Slow-path path: reserve, force a concurrent mutation (denied
            // tryAcquire bumps version), then cancel.
            val slowLimiter = warmupLimiter()
            slowLimiter.acquire() // drain initial stored permit

            val job = launch { slowLimiter.acquire() }
            runCurrent()
            advanceTimeBy(50.milliseconds)

            val concurrentDenied = slowLimiter.tryAcquire()
            assertIs<Permit.Denied>(concurrentDenied)

            job.cancel()
            runCurrent()

            val slowBefore = currentTime
            slowLimiter.acquire()
            val slowWaitMs = currentTime - slowBefore

            // Baseline: same wall-clock evolution without the cancelled
            // reservation. acquire() at t=0, idle to t=50, denied tryAcquire
            // (bumps version and cools the same idle window), then measure.
            val baselineLimiter = warmupLimiter()
            baselineLimiter.acquire() // drain initial stored permit

            advanceTimeBy(50.milliseconds)
            val baselineDenied = baselineLimiter.tryAcquire()
            assertIs<Permit.Denied>(baselineDenied)

            val baselineBefore = currentTime
            baselineLimiter.acquire()
            val baselineWaitMs = currentTime - baselineBefore

            val drift = slowWaitMs - baselineWaitMs
            assertTrue(
                drift in 0L..driftBoundMs,
                "slow-path post-cancel wait should be in [baseline, baseline + ${driftBoundMs}ms]: " +
                    "slow=${slowWaitMs}ms, baseline=${baselineWaitMs}ms, drift=${drift}ms",
            )
        }
}
