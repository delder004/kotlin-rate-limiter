package ratelimiter

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test



@OptIn(ExperimentalCoroutinesApi::class)
abstract class RateLimiterContractTest {
    abstract fun TestScope.createLimiter(permits: Int, per: Duration): RateLimiter

    // ACQUIRE

    @Test
    fun `acquire suspends when permits exhausted`() = runTest {
        val limiter = createLimiter(10, 1.seconds)

        val delays = recordDelays(limiter, 11)
        assertEquals(100, delays.last())
    }

    @Test
    fun `acquire resumes after sufficient time`() = runTest {
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
    fun `tryAcquire returns Granted when permits available`() = runTest {
        val limiter = createLimiter(permits = 1, per = 1.seconds)
        assertEquals(Permit.Granted, limiter.tryAcquire())
    }

    @Test
    fun `tryAcquire returns Denied when permits exhausted`() = runTest {
        val limiter = createLimiter(permits = 1, per = 1.seconds)
        limiter.acquire()
        assertIs<Permit.Denied>(limiter.tryAcquire())
    }

    @Test
    fun `tryAcquire Denied includes positive retryAfter`() = runTest {
        val limiter = createLimiter(permits = 1, per = 1.seconds)
        limiter.acquire()
        val denied = limiter.tryAcquire()
        assertIs<Permit.Denied>(denied)
        assertTrue(denied.retryAfter > Duration.ZERO)
    }

    @Test
    fun `tryAcquire does not consume permits on Denied`() = runTest {
        val limiter = createLimiter(permits = 1, per = 1.seconds)
        limiter.acquire()
        limiter.tryAcquire() // denied, should not consume

        advanceTimeBy(1.seconds)
        assertEquals(Permit.Granted, limiter.tryAcquire())
    }

    // PARAMETER VALIDATION

    @Test
    fun `acquire rejects zero permits`() = runTest {
        val limiter = createLimiter(permits = 5, per = 1.seconds)
        assertFailsWith<IllegalArgumentException> { limiter.acquire(0) }
    }

    @Test
    fun `acquire rejects negative permits`() = runTest {
        val limiter = createLimiter(permits = 5, per = 1.seconds)
        assertFailsWith<IllegalArgumentException> { limiter.acquire(-1) }
    }

    @Test
    fun `tryAcquire rejects zero permits`() = runTest {
        val limiter = createLimiter(permits = 5, per = 1.seconds)
        assertFailsWith<IllegalArgumentException> { limiter.tryAcquire(0) }
    }

    @Test
    fun `factory rejects zero permits`() = runTest {
        assertFailsWith<IllegalArgumentException> { createLimiter(permits = 0, per = 1.seconds) }
    }

    @Test
    fun `factory rejects zero duration`() = runTest {
        assertFailsWith<IllegalArgumentException> { createLimiter(permits = 5, per = Duration.ZERO) }
    }

    @Test
    fun `factory rejects negative duration`() = runTest {
        assertFailsWith<IllegalArgumentException> { createLimiter(permits = 5, per = (-1).seconds) }
    }

    // CANCELLATION

    @Test
    fun `cancelled coroutine restores permits`() = runTest {
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
    fun `cancellation during delay does not consume permit`() = runTest {
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
    fun `other waiters complete their pre-computed delay after cancellation`() = runTest {
        val limiter = createLimiter(permits = 1, per = 1.seconds)
        limiter.acquire()

        val results = mutableListOf<String>()

        val job1 = launch { limiter.acquire(); results.add("first") }
        launch { limiter.acquire(); results.add("second") }
        runCurrent()

        job1.cancel()
        runCurrent()
        // even though job1 was cancelled, job2's delay is already locked in at 2 seconds
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue("second" in results)
    }

    // EXTENSIONS - RateLimiter.withPermit

    @Test
    fun `withPermit acquires and executes block`() = runTest {
        val limiter = createLimiter(permits = 1, per = 1.seconds)
        val result = limiter.withPermit(1) { "hello" }
        assertEquals("hello", result)
    }

    @Test
    fun `withPermit propagates exceptions`() = runTest {
        val limiter = createLimiter(permits = 1, per = 1.seconds)
        assertFailsWith<IllegalStateException> {
            limiter.withPermit(1) { throw IllegalStateException("boom") }
        }
    }

    @Test
    fun `withPermit acquires before running block`() = runTest {
        val limiter = createLimiter(permits = 1, per = 1.seconds)
        limiter.acquire()

        var blockRan = false
        val job = launch {
            limiter.withPermit(1) { blockRan = true }
        }
        runCurrent()
        assertFalse(blockRan)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(blockRan)
    }

    // EXTENSIONS — Flow.rateLimit

    @Test
    fun `each emission is individually rate limited`() = runTest {
        val limiter = createLimiter(permits = 2, per = 1.seconds)
        val timestamps = mutableListOf<Long>()

        flowOf(1, 2, 3, 4, 5)
            .rateLimit(limiter)
            .collect { timestamps.add(currentTime) }

        // With 2 permits/sec, 5 items can't all happen at t=0
        assertTrue(timestamps.last() > 0, "Emissions should be individually rate limited")
    }

    @Test
    fun `backpressure propagates correctly`() = runTest {
        val limiter = createLimiter(permits = 10, per = 1.seconds)
        val collected = mutableListOf<Int>()

        flowOf(1, 2, 3, 4, 5)
            .rateLimit(limiter)
            .collect {
                delay(50.milliseconds)
                collected.add(it)
            }

        assertEquals(listOf(1, 2, 3, 4, 5), collected)
    }

    @Test
    fun `cancelling collection cancels limiter wait`() = runTest {
        val limiter = createLimiter(permits = 1, per = 1.seconds)
        val collected = mutableListOf<Int>()

        val job = launch {
            flow { repeat(100) { emit(it) } }
                .rateLimit(limiter)
                .collect { collected.add(it) }
        }
        runCurrent()

        job.cancel()
        runCurrent()

        assertTrue(collected.size < 100, "Collection should have been cancelled, got ${collected.size} items")
        assertTrue(job.isCancelled)
    }

    @Test
    fun `multiple coroutines collectively stay under limit`() = runTest {
        val limiter = createLimiter(permits = 1000, per = 1.seconds)
        val burst = Op.Parallel((1..10000).map { 1 })
        assertRateNotExceeded(
            runScenario(limiter, burst).map { it.endedAt },
            1000,
            1000
        )
    }
}
