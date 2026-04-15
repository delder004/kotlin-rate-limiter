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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeRateLimiterTest : RateLimiterContractTest() {
    override fun TestScope.createLimiter(
        permits: Int,
        per: Duration,
    ): RateLimiter =
        CompositeRateLimiter(
            BurstyRateLimiter(permits, per, testTimeSource),
            SmoothRateLimiter(permits, per, timeSource = testTimeSource),
        )

    @Test
    fun `acquire succeeds when all limiters have capacity`() =
        runTest {
            val limiter =
                CompositeRateLimiter(
                    BurstyRateLimiter(5, 1.seconds, testTimeSource),
                    BurstyRateLimiter(10, 1.seconds, testTimeSource),
                )
            val before = currentTime
            limiter.acquire()
            assertEquals(0L, currentTime - before)
        }

    @Test
    fun `acquire suspends when any limiter is exhausted`() =
        runTest {
            val fast = BurstyRateLimiter(10, 1.seconds, testTimeSource)
            val slow = BurstyRateLimiter(1, 1.seconds, testTimeSource)
            val limiter = CompositeRateLimiter(fast, slow)

            limiter.acquire() // exhausts slow

            val job = launch { limiter.acquire() }
            runCurrent()
            assertFalse(job.isCompleted)

            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(job.isCompleted)
        }

    @Test
    fun `respects most restrictive limiter`() =
        runTest {
            val limiter =
                CompositeRateLimiter(
                    BurstyRateLimiter(10, 1.seconds, testTimeSource),
                    BurstyRateLimiter(2, 1.seconds, testTimeSource),
                )
            // 2/sec is more restrictive — interval is 500ms
            val delays = recordDelays(limiter, 3)
            assertEquals(listOf(0L, 0L, 500L), delays)
        }

    @Test
    fun `tryAcquire returns Denied if any limiter denies`() =
        runTest {
            val fast = BurstyRateLimiter(10, 1.seconds, testTimeSource)
            val slow = BurstyRateLimiter(1, 1.seconds, testTimeSource)
            val limiter = CompositeRateLimiter(fast, slow)

            limiter.acquire() // exhausts slow but not fast
            assertIs<Permit.Denied>(limiter.tryAcquire())
        }

    @Test
    fun `tryAcquire returns longest retryAfter`() =
        runTest {
            val fast = BurstyRateLimiter(5, 1.seconds, testTimeSource) // interval = 200ms
            val slow = BurstyRateLimiter(2, 1.seconds, testTimeSource) // interval = 500ms
            val limiter = CompositeRateLimiter(fast, slow)

            // exhaust both
            limiter.acquire(5) // exhausts fast (5/5), also takes 5 from slow (but slow only has 2)

            val denied = limiter.tryAcquire()
            assertIs<Permit.Denied>(denied)
            // slow's retryAfter should be longer than fast's
            assertTrue(denied.retryAfter >= 500.milliseconds)
        }

    @Test
    fun `tryAcquire denied does not consume permits from other limiters`() =
        runTest {
            val fast = BurstyRateLimiter(2, 1.seconds, testTimeSource)
            val slow = BurstyRateLimiter(1, 1.seconds, testTimeSource)
            val limiter = CompositeRateLimiter(fast, slow)

            // Exhaust only the slow limiter so composite tryAcquire must deny.
            slow.acquire()

            val denied = limiter.tryAcquire()
            assertIs<Permit.Denied>(denied)

            // If composite tryAcquire is atomic, fast limiter should still have both permits.
            assertEquals(Permit.Granted, fast.tryAcquire())
            assertEquals(Permit.Granted, fast.tryAcquire())
            assertIs<Permit.Denied>(fast.tryAcquire())
        }

    @Test
    fun `acquire cancellation does not consume permits from already-acquired limiters`() =
        runTest {
            val fast = BurstyRateLimiter(2, 1.seconds, testTimeSource)
            val slow = BurstyRateLimiter(1, 1.seconds, testTimeSource)
            val limiter = CompositeRateLimiter(fast, slow)

            // Exhaust slow so composite acquire can consume fast then suspend on slow.
            slow.acquire()

            val job = launch { limiter.acquire() }
            runCurrent()
            job.cancel()
            runCurrent()

            // If acquire rollback is atomic, fast limiter should still have both permits.
            assertEquals(Permit.Granted, fast.tryAcquire())
            assertEquals(Permit.Granted, fast.tryAcquire())
            assertIs<Permit.Denied>(fast.tryAcquire())
        }

    @Test
    fun `both limiters consume permits on acquire`() =
        runTest {
            val a = BurstyRateLimiter(2, 1.seconds, testTimeSource)
            val b = BurstyRateLimiter(2, 1.seconds, testTimeSource)
            val limiter = CompositeRateLimiter(a, b)

            limiter.acquire()

            // both should have consumed 1 permit, leaving 1 each
            assertEquals(Permit.Granted, a.tryAcquire())
            assertEquals(Permit.Granted, b.tryAcquire())

            // now both are exhausted
            assertIs<Permit.Denied>(a.tryAcquire())
            assertIs<Permit.Denied>(b.tryAcquire())
        }

    @Test
    fun `layered limits work correctly`() =
        runTest {
            val perSecond = BurstyRateLimiter(5, 1.seconds, testTimeSource)
            val perMinute = BurstyRateLimiter(20, 10.minutes, testTimeSource)
            val limiter = CompositeRateLimiter(perSecond, perMinute)

            // short burst of 5 should work
            val before = currentTime
            repeat(5) { limiter.acquire() }
            assertEquals(0L, currentTime - before)

            // next acquire waits for per-second refill
            limiter.acquire()
            assertEquals(200L, currentTime - before)

            // keep acquiring up to 20 total over ~3 seconds
            repeat(14) { limiter.acquire() }

            // now per-minute limit is exhausted (20 total), even though per-second keeps refilling
            val job = launch { limiter.acquire() }
            advanceTimeBy(1.seconds)
            runCurrent()
            assertFalse(job.isCompleted, "per-minute limit should block even though per-second has capacity")
        }
}
