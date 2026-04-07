package ratelimiter

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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FuzzTest {
    companion object {
        @JvmStatic
        fun limiterTypes(): List<Arguments> =
            listOf(
                Arguments.of("bursty"),
                Arguments.of("smooth"),
            )
    }

    @Test
    fun `bursty - total delay matches permits consumed with random batch sizes`() =
        runTest {
            val capacity = 100
            val per = 1.seconds
            val intervalMs = (per / capacity).inWholeMilliseconds // 10ms
            val limiter = BurstyRateLimiter(capacity, per, testTimeSource)
            val random = Random(42)

            val requests = (1..50).map { random.nextInt(1, 10) }
            val totalPermits = requests.sum()

            requests.forEach { limiter.acquire(it) }

            val expectedDelay = (totalPermits - capacity).coerceAtLeast(0) * intervalMs
            assertEquals(expectedDelay, currentTime)
        }

    @Test
    fun `smooth - total delay matches permits consumed with random batch sizes`() =
        runTest {
            val capacity = 100
            val per = 1.seconds
            val intervalMs = (per / capacity).inWholeMilliseconds // 10ms
            val limiter = SmoothRateLimiter(capacity, per, Duration.ZERO, testTimeSource)
            val random = Random(42)

            val requests = (1..50).map { random.nextInt(1, 10) }
            val totalPermits = requests.sum()

            requests.forEach { limiter.acquire(it) }

            // Smooth: stored capacity is 1, so only first permit is free
            val expectedDelay = (totalPermits - 1).coerceAtLeast(0) * intervalMs
            assertEquals(expectedDelay, currentTime)
        }

    @Test
    fun `bursty - no permits lost with concurrent random access`() =
        runTest {
            val capacity = 50
            val per = 1.seconds
            val intervalMs = (per / capacity).inWholeMilliseconds // 20ms
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

    @ParameterizedTest(name = "{0} - tryAcquire denials do not affect acquire timing")
    @MethodSource("limiterTypes")
    fun `tryAcquire denials do not affect acquire timing`(type: String) =
        runTest {
            val limiter =
                when (type) {
                    "bursty" -> BurstyRateLimiter(5, 1.seconds, testTimeSource)
                    "smooth" -> SmoothRateLimiter(5, 1.seconds, Duration.ZERO, testTimeSource)
                    else -> error("unknown type: $type")
                }
            limiter.acquire(5) // exhaust

            repeat(1000) { limiter.tryAcquire() }

            val before = currentTime
            limiter.acquire()
            assertEquals(200L, currentTime - before)
        }

    @Test
    fun `random interleaving of acquire and tryAcquire is consistent`() =
        runTest {
            val limiter = BurstyRateLimiter(20, 1.seconds, testTimeSource)
            val random = Random(99)

            var totalAcquired = 0
            var totalGranted = 0
            var totalDenied = 0

            repeat(200) {
                if (random.nextBoolean()) {
                    limiter.acquire()
                    totalAcquired++
                } else {
                    when (limiter.tryAcquire()) {
                        is Permit.Granted -> totalGranted++
                        is Permit.Denied -> totalDenied++
                    }
                }
            }

            assertTrue(totalAcquired > 0, "Some acquires should have run")
            assertTrue(totalGranted + totalDenied > 0, "Some tryAcquires should have run")
            // Total consumed beyond initial burst determines minimum elapsed time
            val totalConsumed = totalAcquired + totalGranted
            val minExpectedTime = (totalConsumed - 20).coerceAtLeast(0) * 50L
            assertTrue(
                currentTime >= minExpectedTime,
                "Expected >= ${minExpectedTime}ms for $totalConsumed consumed, got ${currentTime}ms",
            )
        }
}
