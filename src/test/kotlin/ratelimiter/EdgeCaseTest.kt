package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EdgeCaseTest {
    // HIGH-RATE LIMITERS

    @Test
    fun `bursty - high-rate limiter handles rapid acquires`() =
        runTest {
            val limiter = BurstyRateLimiter(10_000, 1.seconds, testTimeSource)
            val before = currentTime
            repeat(10_000) { limiter.acquire() }
            assertEquals(0L, currentTime - before, "10000 acquires within burst should be instant")

            limiter.acquire()
            assertTrue(currentTime - before > 0, "Exceeding burst should cause a delay")
        }

    @Test
    fun `smooth - high-rate limiter handles rapid acquires`() =
        runTest {
            val limiter = SmoothRateLimiter(10_000, 1.seconds, timeSource = testTimeSource)
            val before = currentTime
            limiter.acquire()
            assertEquals(0L, currentTime - before)
            limiter.acquire()
            assertTrue(currentTime - before > 0, "Second acquire on smooth should delay")
        }

    // LARGE PERMIT REQUESTS

    @Test
    fun `bursty - large acquire request delays proportionally`() =
        runTest {
            val limiter = BurstyRateLimiter(10, 1.seconds, testTimeSource)
            val before = currentTime
            limiter.acquire(1000) // 990 excess * 100ms = 99000ms
            assertEquals(99_000L, currentTime - before)
        }

    @Test
    fun `smooth - large acquire request delays proportionally`() =
        runTest {
            val limiter = SmoothRateLimiter(10, 1.seconds, timeSource = testTimeSource)
            val before = currentTime
            limiter.acquire(1000) // 999 excess * 100ms = 99900ms
            assertEquals(99_900L, currentTime - before)
        }

    // PARTIAL REFILL

    @Test
    fun `tryAcquire after partial refill returns correct retryAfter`() =
        runTest {
            val limiter = BurstyRateLimiter(10, 1.seconds, testTimeSource)
            limiter.acquire(10)

            advanceTimeBy(50.milliseconds) // half of 100ms refill interval

            val permit = limiter.tryAcquire()
            assertIs<Permit.Denied>(permit)
            assertEquals(50.milliseconds, permit.retryAfter)
        }

    // REPEATED TRYACQUIRE DOES NOT CORRUPT STATE

    @Test
    fun `bursty - rapid tryAcquire calls do not consume permits`() =
        runTest {
            val limiter = BurstyRateLimiter(5, 1.seconds, testTimeSource)
            limiter.acquire(5)

            repeat(1000) { assertIs<Permit.Denied>(limiter.tryAcquire()) }

            advanceTimeBy(200.milliseconds)
            assertEquals(Permit.Granted, limiter.tryAcquire())
        }

    @Test
    fun `smooth - rapid tryAcquire calls do not consume permits`() =
        runTest {
            val limiter = SmoothRateLimiter(5, 1.seconds, timeSource = testTimeSource)
            limiter.acquire(5)

            repeat(1000) { assertIs<Permit.Denied>(limiter.tryAcquire()) }

            advanceTimeBy(200.milliseconds)
            assertEquals(Permit.Granted, limiter.tryAcquire())
        }

    // COMPOSITE EDGE CASES

    @Test
    fun `composite - empty limiter list rejected`() {
        assertFailsWith<IllegalArgumentException> { CompositeRateLimiter() }
    }

    @Test
    fun `composite - three limiters respects most restrictive`() =
        runTest {
            val limiter =
                CompositeRateLimiter(
                    BurstyRateLimiter(10, 1.seconds, testTimeSource),
                    BurstyRateLimiter(5, 1.seconds, testTimeSource),
                    BurstyRateLimiter(2, 1.seconds, testTimeSource),
                )
            val delays = recordDelays(limiter, 3)
            assertEquals(listOf(0L, 0L, 500L), delays)
        }

    @Test
    fun `composite - mixed bursty and smooth respects smooth spacing`() =
        runTest {
            val limiter =
                CompositeRateLimiter(
                    BurstyRateLimiter(5, 1.seconds, testTimeSource),
                    SmoothRateLimiter(5, 1.seconds, timeSource = testTimeSource),
                )
            val delays = recordDelays(limiter, 5)
            assertEquals(listOf(0L, 200L, 200L, 200L, 200L), delays)
        }
}
