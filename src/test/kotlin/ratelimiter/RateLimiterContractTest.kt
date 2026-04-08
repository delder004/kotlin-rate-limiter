package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
abstract class RateLimiterContractTest {
    abstract fun TestScope.createLimiter(
        permits: Int,
        per: Duration,
    ): RateLimiter

    // ACQUIRE

    @Test
    fun `acquire suspends when permits exhausted`() =
        runTest {
            val limiter = createLimiter(10, 1.seconds)

            val delays = recordDelays(limiter, 11)
            assertEquals(100, delays.last())
        }

    @Test
    fun `acquire resumes after sufficient time`() =
        runTest {
            val limiter = createLimiter(permits = 1, per = 1.seconds)
            limiter.acquire()

            var acquired = false
            launch {
                limiter.acquire()
                acquired = true
            }

            runCurrent()
            assertFalse(acquired)

            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(acquired)
        }

    // TRYACQUIRE

    @Test
    fun `tryAcquire returns Granted when permits available`() =
        runTest {
            val limiter = createLimiter(permits = 1, per = 1.seconds)
            assertEquals(Permit.Granted, limiter.tryAcquire())
        }

    @Test
    fun `tryAcquire returns Denied when permits exhausted`() =
        runTest {
            val limiter = createLimiter(permits = 1, per = 1.seconds)
            limiter.acquire()
            assertIs<Permit.Denied>(limiter.tryAcquire())
        }

    @Test
    fun `tryAcquire Denied includes positive retryAfter`() =
        runTest {
            val limiter = createLimiter(permits = 1, per = 1.seconds)
            limiter.acquire()
            val denied = limiter.tryAcquire()
            assertIs<Permit.Denied>(denied)
            assertTrue(denied.retryAfter > Duration.ZERO)
        }

    @Test
    fun `tryAcquire does not consume permits on Denied`() =
        runTest {
            val limiter = createLimiter(permits = 1, per = 1.seconds)
            limiter.acquire()
            limiter.tryAcquire() // denied, should not consume

            advanceTimeBy(1.seconds)
            assertEquals(Permit.Granted, limiter.tryAcquire())
        }

    // IDLE AND RESUME

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

    // PARAMETER VALIDATION

    @ParameterizedTest(name = "acquire rejects permits={0}")
    @ValueSource(ints = [0, -1])
    fun `acquire rejects invalid permits`(permits: Int) =
        runTest {
            val limiter = createLimiter(permits = 5, per = 1.seconds)
            assertFailsWith<IllegalArgumentException> { limiter.acquire(permits) }
        }

    @ParameterizedTest(name = "tryAcquire rejects permits={0}")
    @ValueSource(ints = [0, -1])
    fun `tryAcquire rejects invalid permits`(permits: Int) =
        runTest {
            val limiter = createLimiter(permits = 5, per = 1.seconds)
            assertFailsWith<IllegalArgumentException> { limiter.tryAcquire(permits) }
        }

    @ParameterizedTest(name = "factory rejects permits={0}")
    @ValueSource(ints = [0, -1])
    fun `factory rejects invalid permits`(permits: Int) =
        runTest {
            assertFailsWith<IllegalArgumentException> { createLimiter(permits = permits, per = 1.seconds) }
        }

    @ParameterizedTest(name = "factory rejects per={0}ms")
    @ValueSource(longs = [0L, -1000L])
    fun `factory rejects invalid duration`(perMillis: Long) =
        runTest {
            val per = perMillis.milliseconds
            assertFailsWith<IllegalArgumentException> { createLimiter(permits = 5, per = per) }
        }

    // CANCELLATION

    @Test
    fun `cancelled coroutine restores permits`() =
        runTest {
            val limiter = createLimiter(permits = 1, per = 1.seconds)
            limiter.acquire()

            val job = launch { limiter.acquire() }
            runCurrent()
            job.cancel()
            runCurrent()

            // permit should be restored — a new coroutine can acquire after refill
            var acquired = false
            launch {
                limiter.acquire()
                acquired = true
            }
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(acquired)
        }

    @Test
    fun `cancellation during delay does not consume permit`() =
        runTest {
            val limiter = createLimiter(permits = 2, per = 1.seconds)
            limiter.acquire(2)

            val job = launch { limiter.acquire() }
            runCurrent()
            job.cancel()
            runCurrent()
            advanceTimeBy(500.milliseconds)

            assertEquals(Permit.Granted, limiter.tryAcquire())
        }

    @Test
    fun `other waiters complete their pre-computed delay after cancellation`() =
        runTest {
            val limiter = createLimiter(permits = 1, per = 1.seconds)
            limiter.acquire()

            val results = mutableListOf<String>()

            val job1 =
                launch {
                    limiter.acquire()
                    results.add("first")
                }
            launch {
                limiter.acquire()
                results.add("second")
            }
            runCurrent()

            job1.cancel()
            runCurrent()
            // even though job1 was cancelled, job2's delay is already locked in at 2 seconds
            advanceTimeBy(2.seconds)
            runCurrent()
            assertTrue("second" in results)
        }

}
