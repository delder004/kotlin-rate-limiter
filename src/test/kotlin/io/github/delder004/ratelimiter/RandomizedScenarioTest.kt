package io.github.delder004.ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RandomizedScenarioTest {
    companion object {
        @JvmStatic
        fun limiterTypes(): List<Arguments> =
            listOf(
                Arguments.of("bursty"),
                Arguments.of("smooth"),
            )
    }

    @ParameterizedTest(name = "{0} total delay matches random batch sizes")
    @MethodSource("limiterTypes")
    fun `total delay matches permits consumed with random batch sizes`(type: String) =
        runTest {
            val capacity = 100
            val per = 1.seconds
            val intervalMs = (per / capacity).inWholeMilliseconds
            val limiter = createTestLimiter(type, capacity, per)
            val random = Random(42)

            val requests = (1..50).map { random.nextInt(1, 10) }
            val totalPermits = requests.sum()
            requests.forEach { limiter.acquire(it) }

            val freePermits = if (type == "bursty") capacity else 1
            val expectedDelay = (totalPermits - freePermits).coerceAtLeast(0) * intervalMs
            assertEquals(expectedDelay, currentTime)
        }

    @Test
    fun `bursty no permits lost with concurrent random access`() =
        runTest {
            val capacity = 50
            val per = 1.seconds
            val intervalMs = (per / capacity).inWholeMilliseconds
            val limiter = BurstyRateLimiter(capacity, per, testTimeSource)
            val random = Random(123)

            val requests = (1..100).map { random.nextInt(1, 5) }
            val totalPermits = requests.sum()
            var completed = 0

            requests.forEach { n ->
                launch {
                    limiter.acquire(n)
                    completed++
                }
            }
            advanceUntilIdle()

            assertEquals(100, completed, "All 100 acquire calls should complete")
            val expectedDelay = (totalPermits - capacity).coerceAtLeast(0) * intervalMs
            assertEquals(expectedDelay, currentTime)
        }

    @ParameterizedTest(name = "{0} tryAcquire denials do not affect acquire timing")
    @MethodSource("limiterTypes")
    fun `tryAcquire denials do not affect acquire timing`(type: String) =
        runTest {
            val limiter = createTestLimiter(type, permits = 5, per = 1.seconds)
            limiter.acquire(5)

            repeat(1000) { limiter.tryAcquire() }

            val before = currentTime
            limiter.acquire()
            assertEquals(200L, currentTime - before)
        }
}
