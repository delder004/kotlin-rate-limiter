package io.github.delder004.ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
class RateLimiterExtremeValuesTest {
    companion object {
        @JvmStatic
        fun limiterTypes(): List<Arguments> =
            listOf(
                Arguments.of("bursty"),
                Arguments.of("smooth"),
            )
    }

    @Test
    fun `composite rejects empty limiter list`() {
        assertFailsWith<IllegalArgumentException> { CompositeRateLimiter() }
    }

    @Test
    fun `bursty tryAcquire after partial refill returns exact retryAfter`() =
        runTest {
            val limiter = BurstyRateLimiter(10, 1.seconds, testTimeSource)
            limiter.acquire(10)

            advanceTimeBy(50.milliseconds)

            val permit = limiter.tryAcquire()
            assertIs<Permit.Denied>(permit)
            assertEquals(50.milliseconds, permit.retryAfter)
        }

    @ParameterizedTest(name = "{0} very slow rate limiter paces correctly")
    @MethodSource("limiterTypes")
    fun `very slow rate limiter paces correctly`(type: String) =
        runTest {
            val limiter = createTestLimiter(type, permits = 1, per = 10.minutes)
            limiter.acquire()

            val before = currentTime
            val job = launch { limiter.acquire() }
            runCurrent()
            assertFalse(job.isCompleted)

            advanceTimeBy(9.minutes)
            runCurrent()
            if (type == "bursty") {
                assertFalse(job.isCompleted)
            }

            advanceTimeBy(1.minutes)
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(10.minutes.inWholeMilliseconds, currentTime - before)
        }

    @ParameterizedTest(name = "{0} retryAfter stays finite after deep debt")
    @MethodSource("limiterTypes")
    fun `retryAfter stays finite after deep debt`(type: String) =
        runTest {
            val limiter = createTestLimiter(type, permits = 10, per = 1.seconds)
            limiter.acquire(10_000)

            val denied = limiter.tryAcquire()
            assertIs<Permit.Denied>(denied)
            assertTrue(denied.retryAfter > Duration.ZERO)
            assertTrue(denied.retryAfter.isFinite())
        }

    @ParameterizedTest(name = "{0} tryAcquire with Int MAX_VALUE permits does not crash")
    @MethodSource("limiterTypes")
    fun `tryAcquire with Int MAX_VALUE permits does not crash`(type: String) =
        runTest {
            val limiter = createTestLimiter(type, permits = 10, per = 1.seconds)
            val denied = limiter.tryAcquire(Int.MAX_VALUE)
            assertIs<Permit.Denied>(denied)
            assertTrue(denied.retryAfter > Duration.ZERO)
        }

    @ParameterizedTest(name = "{0} refill after very long idle does not overflow")
    @MethodSource("limiterTypes")
    fun `refill after very long idle does not overflow`(type: String) =
        runTest {
            val limiter = createTestLimiter(type, permits = 10, per = 1.seconds)
            if (type == "bursty") {
                limiter.acquire(10)
            } else {
                limiter.acquire()
            }
            advanceTimeBy(365.days)

            val before = currentTime
            limiter.acquire(if (type == "bursty") 10 else 1)
            assertEquals(0L, currentTime - before)

            limiter.acquire()
            assertEquals(100L, currentTime - before)
        }

    @Test
    fun `bursty indivisible refill interval maintains correct total rate`() =
        runTest {
            val limiter = BurstyRateLimiter(3, 1.seconds, testTimeSource)
            limiter.acquire(3)

            advanceTimeBy(1.seconds)
            val before = currentTime
            repeat(3) { limiter.acquire() }
            assertEquals(0L, currentTime - before)

            limiter.acquire()
            assertTrue(currentTime - before > 0)
        }

    @Test
    fun `smooth indivisible refill interval maintains correct total rate`() =
        runTest {
            val limiter = SmoothRateLimiter(3, 1.seconds, timeSource = testTimeSource)
            val delays = recordDelays(limiter, 4)

            assertEquals(0L, delays[0])
            assertTrue(delays[1] in 333L..334L, "Expected ~333ms, got ${delays[1]}")
            assertTrue(delays[2] in 333L..334L, "Expected ~333ms, got ${delays[2]}")
            assertTrue(delays[3] in 333L..334L, "Expected ~333ms, got ${delays[3]}")
        }

    @ParameterizedTest(name = "{0} cancelling massive acquire restores permits correctly")
    @MethodSource("limiterTypes")
    fun `cancelling massive acquire restores permits correctly`(type: String) =
        runTest {
            val limiter = createTestLimiter(type, permits = 5, per = 1.seconds)
            if (type == "bursty") {
                limiter.acquire(5)
            } else {
                limiter.acquire()
            }

            val job = launch { limiter.acquire(10_000) }
            runCurrent()
            job.cancel()
            runCurrent()

            advanceTimeBy(200.milliseconds)
            assertEquals(Permit.Granted, limiter.tryAcquire())
        }
}
