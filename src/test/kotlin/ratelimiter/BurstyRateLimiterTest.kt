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
class BurstyRateLimiterTest : RateLimiterContractTest() {
    override fun TestScope.createLimiter(
        permits: Int,
        per: Duration,
    ): RateLimiter = BurstyRateLimiter(permits, per, testTimeSource)

    @Test
    fun `acquire immediately returns when permits available`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            val before = currentTime
            (1..5).map { limiter.acquire() }
            assertEquals(0L, currentTime - before)
        }

    @Test
    fun `acquire immediately returns all available permits but delays subsequent calls by refill rate`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            val before = currentTime
            (1..6).map { limiter.acquire() }
            assertEquals(200, currentTime - before)
        }

    @Test
    fun `burst up to permit count after idle`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            limiter.acquire(5)
            advanceTimeBy(2.seconds)
            val before = currentTime
            limiter.acquire(1)
            limiter.acquire(4)
            assertEquals(0L, currentTime - before)
            limiter.acquire(1)
            assertEquals(200, currentTime - before)
        }

    @Test
    fun `burst never exceeds one window of work`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            advanceTimeBy(5.seconds)
            val burst1 = launch { limiter.acquire(10) }
            val burst2 = launch { limiter.acquire(10) }
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(burst1.isCompleted)
            assertFalse(burst2.isCompleted)
            advanceTimeBy(2.seconds)
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

    @Test
    fun `idle accumulation capped at permit count`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            advanceTimeBy(5.seconds)
            val before = currentTime
            limiter.acquire(5)
            limiter.acquire(1)
            assertEquals(200, currentTime - before)
        }

    @Test
    fun `acquire(n) consumes multiple permits`() =
        runTest {
            val limiter = createLimiter(5, 1.seconds)
            limiter.acquire(3)
            val delayed = launch { limiter.acquire(3) }
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
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(job1.isCompleted)
            assertFalse(job2.isCompleted)
            advanceTimeBy(200.milliseconds)
            runCurrent()
            assertTrue(job2.isCompleted)
        }
}
