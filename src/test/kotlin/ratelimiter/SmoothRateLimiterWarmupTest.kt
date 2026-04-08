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

    private fun TestScope.warmupConfig(): BucketConfig =
        BucketConfig(
            capacity = 1.0,
            timeSource = testTimeSource,
            stableRefillInterval = 200.milliseconds,
            warmup = 2.seconds,
        )

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
    fun `refund does not allow negative warmupPermitsConsumed`() =
        runTest {
            val bucket =
                PermitBucket(
                    available = -1.0,
                    refilledAt = testTimeSource.markNow(),
                    warmupPermitsConsumed = 0.5,
                )

            val restored = bucket.refund(1, warmupConfig())
            assertTrue(
                restored.warmupPermitsConsumed >= 0.0,
                "warmupPermitsConsumed should not go negative, was ${restored.warmupPermitsConsumed}",
            )
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

    @Test
    fun `cooldown rate should be constant regardless of warmth level`() =
        runTest {
            val mark = testTimeSource.markNow()
            val config = warmupConfig()

            val warmBucket =
                PermitBucket(
                    available = 0.0,
                    refilledAt = mark,
                    warmupPermitsConsumed = 5.0,
                )

            val halfWarmBucket =
                PermitBucket(
                    available = 0.0,
                    refilledAt = mark,
                    warmupPermitsConsumed = 2.5,
                )

            advanceTimeBy(800.milliseconds)

            val warmRefilled = warmBucket.refill(config)
            val halfRefilled = halfWarmBucket.refill(config)

            val warmDrop = warmBucket.warmupPermitsConsumed - warmRefilled.warmupPermitsConsumed
            val halfDrop = halfWarmBucket.warmupPermitsConsumed - halfRefilled.warmupPermitsConsumed

            assertTrue(
                warmDrop <= halfDrop * 2.0,
                "Warm bucket should not cool more than 2x faster than half-warm bucket. " +
                    "Warm lost $warmDrop wpc, half-warm lost $halfDrop wpc " +
                    "(ratio: ${if (halfDrop > 0) warmDrop / halfDrop else "inf"}x)",
            )
        }

    @Test
    fun `refill result should not depend on how many times refill is called`() =
        runTest {
            val config = warmupConfig()
            val bucket =
                PermitBucket(
                    available = 0.0,
                    refilledAt = testTimeSource.markNow(),
                    warmupPermitsConsumed = 5.0,
                )

            advanceTimeBy(500.milliseconds)
            val step1 = bucket.refill(config)
            advanceTimeBy(500.milliseconds)
            val twoStep = step1.refill(config)
            val oneStep = bucket.refill(config)

            assertEquals(
                oneStep.warmupPermitsConsumed,
                twoStep.warmupPermitsConsumed,
                "Single 1s refill and two 500ms refills should produce the same warmth. " +
                    "One-step wpc=${oneStep.warmupPermitsConsumed}, " +
                    "two-step wpc=${twoStep.warmupPermitsConsumed}",
            )
        }

    @Test
    fun `refill only partially cools warmup state after partial idle`() =
        runTest {
            val bucket =
                PermitBucket(
                    available = 1.0,
                    refilledAt = testTimeSource.markNow(),
                    warmupPermitsConsumed = 5.0,
                )

            advanceTimeBy(1.seconds)

            val cooled = bucket.refill(warmupConfig())

            assertTrue(
                cooled.warmupPermitsConsumed in 2.4..2.6,
                "After half the warmup idle, limiter should be half cooled, was ${cooled.warmupPermitsConsumed}",
            )
            assertTrue(
                cooled.refillInterval(warmupConfig()) in 390.milliseconds..410.milliseconds,
                "Half-cooled interval should be near 400ms, was ${cooled.refillInterval(warmupConfig())}",
            )
        }
}
