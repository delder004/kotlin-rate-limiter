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
    fun `permits are evenly spaced`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            val delays = recordDelays(limiter, 5)
            val expected: List<Long> = listOf(200, 200, 200, 200)
            val cumulative = delays.runningReduce { acc, d -> acc + d }
            assertEquals(expected, cumulative.zipWithNext { a, b -> b - a })
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
    fun `idle time is not penalized`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            val before = currentTime
            limiter.acquire(1)
            advanceTimeBy(200.milliseconds)
            limiter.acquire(1)
            assertEquals(200, currentTime - before)
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
}
