package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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

    // VERY SLOW RATES

    @Test
    fun `bursty - very slow rate limiter paces correctly`() =
        runTest {
            val limiter = BurstyRateLimiter(1, 10.minutes, testTimeSource)
            limiter.acquire()

            val before = currentTime
            val job = launch { limiter.acquire() }
            runCurrent()
            assertFalse(job.isCompleted)

            advanceTimeBy(9.minutes)
            runCurrent()
            assertFalse(job.isCompleted)

            advanceTimeBy(1.minutes)
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(10.minutes.inWholeMilliseconds, currentTime - before)
        }

    @Test
    fun `smooth - very slow rate limiter paces correctly`() =
        runTest {
            val limiter = SmoothRateLimiter(1, 10.minutes, timeSource = testTimeSource)
            limiter.acquire()

            val before = currentTime
            val job = launch { limiter.acquire() }
            runCurrent()
            assertFalse(job.isCompleted)

            advanceTimeBy(10.minutes)
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(10.minutes.inWholeMilliseconds, currentTime - before)
        }

    // MASSIVE ACQUIRE REQUESTS

    @Test
    fun `bursty - massive acquire completes with correct delay`() =
        runTest {
            val limiter = BurstyRateLimiter(100, 1.seconds, testTimeSource)
            // 100_000 permits at 100/sec: (100_000 - 100) * 10ms = 999_000ms
            val before = currentTime
            limiter.acquire(100_000)
            assertEquals(999_000L, currentTime - before)
        }

    @Test
    fun `smooth - massive acquire completes with correct delay`() =
        runTest {
            val limiter = SmoothRateLimiter(100, 1.seconds, timeSource = testTimeSource)
            // 100_000 permits at 100/sec: (100_000 - 1) * 10ms = 999_990ms
            val before = currentTime
            limiter.acquire(100_000)
            assertEquals(999_990L, currentTime - before)
        }

    // DEEP DEBT — tryAcquire retryAfter

    @Test
    fun `bursty - tryAcquire retryAfter stays finite after deep debt`() =
        runTest {
            val limiter = BurstyRateLimiter(10, 1.seconds, testTimeSource)
            limiter.acquire(10_000) // go 9990 permits into debt

            val denied = limiter.tryAcquire()
            assertIs<Permit.Denied>(denied)
            assertTrue(denied.retryAfter > Duration.ZERO)
            assertTrue(denied.retryAfter.isFinite())
        }

    @Test
    fun `smooth - tryAcquire retryAfter stays finite after deep debt`() =
        runTest {
            val limiter = SmoothRateLimiter(10, 1.seconds, timeSource = testTimeSource)
            limiter.acquire(10_000)

            val denied = limiter.tryAcquire()
            assertIs<Permit.Denied>(denied)
            assertTrue(denied.retryAfter > Duration.ZERO)
            assertTrue(denied.retryAfter.isFinite())
        }

    @Test
    fun `bursty - tryAcquire with Int MAX_VALUE permits does not crash`() =
        runTest {
            val limiter = BurstyRateLimiter(10, 1.seconds, testTimeSource)
            val denied = limiter.tryAcquire(Int.MAX_VALUE)
            assertIs<Permit.Denied>(denied)
            assertTrue(denied.retryAfter > Duration.ZERO)
        }

    @Test
    fun `smooth - tryAcquire with Int MAX_VALUE permits does not crash`() =
        runTest {
            val limiter = SmoothRateLimiter(10, 1.seconds, timeSource = testTimeSource)
            val denied = limiter.tryAcquire(Int.MAX_VALUE)
            assertIs<Permit.Denied>(denied)
            assertTrue(denied.retryAfter > Duration.ZERO)
        }

    // VERY LONG IDLE — refill overflow

    @Test
    fun `bursty - refill after very long idle does not overflow`() =
        runTest {
            val limiter = BurstyRateLimiter(10, 1.seconds, testTimeSource)
            limiter.acquire(10)
            advanceTimeBy(365.days)

            val before = currentTime
            limiter.acquire(10) // should be instant (capped at capacity)
            assertEquals(0L, currentTime - before)

            limiter.acquire(1) // 11th should wait one interval
            assertEquals(100L, currentTime - before)
        }

    @Test
    fun `smooth - refill after very long idle does not overflow`() =
        runTest {
            val limiter = SmoothRateLimiter(10, 1.seconds, timeSource = testTimeSource)
            limiter.acquire()
            advanceTimeBy(365.days)

            val before = currentTime
            limiter.acquire() // should be instant (1 stored permit)
            assertEquals(0L, currentTime - before)

            limiter.acquire() // next should wait one interval
            assertEquals(100L, currentTime - before)
        }

    // NON-INTEGER REFILL INTERVAL — indivisible period/permits

    @Test
    fun `bursty - indivisible refill interval maintains correct total rate`() =
        runTest {
            // 3 permits per 1 second = 333.33ms interval
            val limiter = BurstyRateLimiter(3, 1.seconds, testTimeSource)
            limiter.acquire(3) // exhaust

            // After 1 full second, all 3 permits should have refilled
            advanceTimeBy(1.seconds)
            val before = currentTime
            repeat(3) { limiter.acquire() }
            assertEquals(0L, currentTime - before, "3 permits should have refilled after 1 second")

            // 4th should require a wait
            limiter.acquire()
            assertTrue(currentTime - before > 0)
        }

    @Test
    fun `smooth - indivisible refill interval maintains correct total rate`() =
        runTest {
            // 3 permits per 1 second = 333.33ms interval
            val limiter = SmoothRateLimiter(3, 1.seconds, timeSource = testTimeSource)
            val delays = recordDelays(limiter, 4)

            // first is free, each subsequent waits ~333ms
            assertEquals(0L, delays[0])
            assertTrue(delays[1] in 333L..334L, "Expected ~333ms, got ${delays[1]}")
            assertTrue(delays[2] in 333L..334L, "Expected ~333ms, got ${delays[2]}")
            assertTrue(delays[3] in 333L..334L, "Expected ~333ms, got ${delays[3]}")
        }

    // CANCELLATION UNDER EXTREME DEBT

    @Test
    fun `bursty - cancelling massive acquire restores permits correctly`() =
        runTest {
            val limiter = BurstyRateLimiter(5, 1.seconds, testTimeSource)
            limiter.acquire(5) // exhaust

            val job = launch { limiter.acquire(10_000) }
            runCurrent()
            job.cancel()
            runCurrent()

            // permits should be restored — after refill, a normal acquire should work
            advanceTimeBy(200.milliseconds)
            assertEquals(Permit.Granted, limiter.tryAcquire())
        }

    @Test
    fun `smooth - cancelling massive acquire restores permits correctly`() =
        runTest {
            val limiter = SmoothRateLimiter(5, 1.seconds, timeSource = testTimeSource)
            limiter.acquire() // exhaust (smooth capacity = 1)

            val job = launch { limiter.acquire(10_000) }
            runCurrent()
            job.cancel()
            runCurrent()

            advanceTimeBy(200.milliseconds)
            assertEquals(Permit.Granted, limiter.tryAcquire())
        }
}
