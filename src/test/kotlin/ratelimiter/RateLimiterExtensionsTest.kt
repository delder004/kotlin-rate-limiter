package ratelimiter

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
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterExtensionsTest {
    companion object {
        @JvmStatic
        fun limiterFactories(): List<Arguments> =
            listOf(
                Arguments.of("bursty", LimiterFactory { permits, per, scope -> BurstyRateLimiter(permits, per, scope.testTimeSource) }),
                Arguments.of(
                    "smooth",
                    LimiterFactory { permits, per, scope -> SmoothRateLimiter(permits, per, Duration.ZERO, scope.testTimeSource) },
                ),
                Arguments.of(
                    "composite",
                    LimiterFactory { permits, per, scope ->
                        CompositeRateLimiter(
                            BurstyRateLimiter(permits, per, scope.testTimeSource),
                            SmoothRateLimiter(permits, per, Duration.ZERO, scope.testTimeSource),
                        )
                    },
                ),
            )
    }

    fun interface LimiterFactory {
        fun create(
            permits: Int,
            per: Duration,
            scope: TestScope,
        ): RateLimiter
    }

    @ParameterizedTest(name = "{0} withPermit acquires and executes block")
    @MethodSource("limiterFactories")
    fun `withPermit acquires and executes block`(
        @Suppress("UNUSED_PARAMETER") type: String,
        factory: LimiterFactory,
    ) = runTest {
        val limiter = factory.create(permits = 1, per = 1.seconds, scope = this)
        val result = limiter.withPermit(1) { "hello" }
        assertEquals("hello", result)
    }

    @ParameterizedTest(name = "{0} withPermit propagates exceptions")
    @MethodSource("limiterFactories")
    fun `withPermit propagates exceptions`(
        @Suppress("UNUSED_PARAMETER") type: String,
        factory: LimiterFactory,
    ) = runTest {
        val limiter = factory.create(permits = 1, per = 1.seconds, scope = this)
        assertFailsWith<IllegalStateException> {
            limiter.withPermit(1) { throw IllegalStateException("boom") }
        }
    }

    @ParameterizedTest(name = "{0} withPermit acquires before running block")
    @MethodSource("limiterFactories")
    fun `withPermit acquires before running block`(
        @Suppress("UNUSED_PARAMETER") type: String,
        factory: LimiterFactory,
    ) = runTest {
        val limiter = factory.create(permits = 1, per = 1.seconds, scope = this)
        limiter.acquire()

        var blockRan = false
        val job =
            launch {
                limiter.withPermit(1) { blockRan = true }
            }
        runCurrent()
        assertFalse(blockRan)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(blockRan)
        job.cancel()
    }

    @ParameterizedTest(name = "{0} rateLimit limits each emission")
    @MethodSource("limiterFactories")
    fun `each emission is individually rate limited`(
        @Suppress("UNUSED_PARAMETER") type: String,
        factory: LimiterFactory,
    ) = runTest {
        val limiter = factory.create(permits = 2, per = 1.seconds, scope = this)
        val timestamps = mutableListOf<Long>()

        flowOf(1, 2, 3, 4, 5)
            .rateLimit(limiter)
            .collect { timestamps.add(currentTime) }

        assertEquals(5, timestamps.size)
        timestamps.zipWithNext().forEach { (a, b) ->
            assertTrue(b >= a, "Timestamps should be non-decreasing: $a -> $b")
        }
        assertTrue(
            timestamps.last() >= 1500,
            "5 items at 2/sec should take >= 1500ms, was ${timestamps.last()}",
        )
        val nonZeroGaps = timestamps.zipWithNext().count { (a, b) -> b > a }
        assertTrue(
            nonZeroGaps >= 2,
            "Expected at least 2 non-zero gaps between emissions, got $nonZeroGaps",
        )
    }

    @ParameterizedTest(name = "{0} rateLimit preserves backpressure")
    @MethodSource("limiterFactories")
    fun `backpressure propagates correctly`(
        @Suppress("UNUSED_PARAMETER") type: String,
        factory: LimiterFactory,
    ) = runTest {
        val limiter = factory.create(permits = 10, per = 1.seconds, scope = this)
        val collected = mutableListOf<Int>()

        flowOf(1, 2, 3, 4, 5)
            .rateLimit(limiter)
            .collect {
                delay(50.milliseconds)
                collected.add(it)
            }

        assertEquals(listOf(1, 2, 3, 4, 5), collected)
    }

    @ParameterizedTest(name = "{0} cancelling collection cancels limiter wait")
    @MethodSource("limiterFactories")
    fun `cancelling collection cancels limiter wait`(
        @Suppress("UNUSED_PARAMETER") type: String,
        factory: LimiterFactory,
    ) = runTest {
        val limiter = factory.create(permits = 1, per = 1.seconds, scope = this)
        val collected = mutableListOf<Int>()

        val job =
            launch {
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
}
