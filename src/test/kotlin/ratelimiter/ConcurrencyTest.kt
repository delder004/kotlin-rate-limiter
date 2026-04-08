package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyTest {
    companion object {
        @JvmStatic
        fun limiterTypes(): List<Arguments> =
            listOf(
                Arguments.of("bursty"),
                Arguments.of("smooth"),
                Arguments.of("composite"),
            )

        @JvmStatic
        fun contentionCases(): List<Arguments> =
            listOf(
                // type, expectedTotalMs, maxPermitsPerWindow
                Arguments.of("bursty", 4000L, 200),
                Arguments.of("smooth", 4990L, 100),
                Arguments.of("composite", 4990L, 100),
            )
    }

    @ParameterizedTest(name = "{0} - all acquires complete under contention")
    @MethodSource("contentionCases")
    fun `all acquires complete under contention`(
        type: String,
        expectedTotalMs: Long,
        maxPermitsPerWindow: Int,
    ) = runTest {
        val limiter = createTestLimiter(type, 100, 1.seconds)
        val timestamps = mutableListOf<Long>()

        (1..500).map {
            launch {
                limiter.acquire()
                timestamps.add(currentTime)
            }
        }
        advanceUntilIdle()

        assertEquals(500, timestamps.size, "All 500 acquires should complete")
        assertEquals(expectedTotalMs, currentTime)
        assertRateNotExceeded(timestamps, limit = maxPermitsPerWindow, windowMs = 1000)
    }

    @ParameterizedTest(name = "{0} - acquire grants in arrival order")
    @MethodSource("limiterTypes")
    fun `acquire grants in arrival order`(type: String) =
        runTest {
            val limiter = createTestLimiter(type, 1, 1.seconds)
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
    fun `concurrent acquire and tryAcquire do not corrupt state`() =
        runTest {
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
    fun `multiple cancellations do not corrupt state`() =
        runTest {
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
