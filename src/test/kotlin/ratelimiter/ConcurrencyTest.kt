package ratelimiter

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyTest {

    @Test
    fun `bursty - all acquires complete under contention`() = runTest {
        val limiter = BurstyRateLimiter(100, 1.seconds, testTimeSource)
        var completed = 0

        (1..500).map {
            launch {
                limiter.acquire()
                completed++
            }
        }
        advanceUntilIdle()

        assertEquals(500, completed, "All 500 acquires should complete")
        // 100 burst instantly + 400 at 10ms each = 4000ms
        assertEquals(4000L, currentTime)
    }

    @Test
    fun `smooth - all acquires complete under contention`() = runTest {
        val limiter = SmoothRateLimiter(100, 1.seconds, Duration.ZERO, testTimeSource)
        var completed = 0

        (1..500).map {
            launch {
                limiter.acquire()
                completed++
            }
        }
        advanceUntilIdle()

        assertEquals(500, completed, "All 500 acquires should complete")
        // smooth: capacity=1, so 1 instant + 499 * 10ms = 4990ms
        assertEquals(4990L, currentTime)
    }

    @Test
    fun `bursty - acquire grants in arrival order`() = runTest {
        val limiter = BurstyRateLimiter(1, 1.seconds, testTimeSource)
        limiter.acquire() // exhaust

        val completionOrder = mutableListOf<Int>()
        (1..5).map { i ->
            launch {
                limiter.acquire()
                completionOrder.add(i)
            }
        }
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3, 4, 5), completionOrder)
    }

    @Test
    fun `smooth - acquire grants in arrival order`() = runTest {
        val limiter = SmoothRateLimiter(1, 1.seconds, Duration.ZERO, testTimeSource)
        limiter.acquire() // exhaust

        val completionOrder = mutableListOf<Int>()
        (1..5).map { i ->
            launch {
                limiter.acquire()
                completionOrder.add(i)
            }
        }
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3, 4, 5), completionOrder)
    }

    @Test
    fun `concurrent acquire and tryAcquire do not corrupt state`() = runTest {
        val limiter = BurstyRateLimiter(50, 1.seconds, testTimeSource)

        var acquireCompleted = 0
        var granted = 0
        var denied = 0

        (1..30).map {
            launch {
                limiter.acquire()
                acquireCompleted++
            }
        }
        (1..30).map {
            launch {
                when (limiter.tryAcquire()) {
                    is Permit.Granted -> granted++
                    is Permit.Denied -> denied++
                }
            }
        }
        advanceUntilIdle()

        assertEquals(30, acquireCompleted, "All suspending acquires must complete")
        assertEquals(30, granted + denied, "All tryAcquires must return")
        // 30 acquires consume 30 of 50 burst permits, leaving 20 for tryAcquire
        assertEquals(20, granted)
        assertEquals(10, denied)
    }

    @Test
    fun `multiple cancellations do not corrupt state`() = runTest {
        val limiter = BurstyRateLimiter(2, 1.seconds, testTimeSource)
        limiter.acquire(2) // exhaust

        repeat(10) {
            val job = launch { limiter.acquire() }
            runCurrent()
            job.cancel()
            runCurrent()
        }

        // After repeated cancel cycles, refill should still work normally
        advanceTimeBy(500.milliseconds)
        assertEquals(Permit.Granted, limiter.tryAcquire())
    }
}
