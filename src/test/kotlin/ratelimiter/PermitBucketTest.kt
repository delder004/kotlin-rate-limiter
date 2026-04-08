package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PermitBucketTest {
    @Test
    fun `refill only partially cools warmup state after partial idle`() =
        runTest {
            val config =
                BucketConfig(
                    capacity = 1.0,
                    timeSource = testTimeSource,
                    stableRefillInterval = 200.milliseconds,
                    warmup = 2.seconds,
                )
            val bucket =
                PermitBucket(
                    available = 1.0,
                    refilledAt = testTimeSource.markNow(),
                    warmupPermitsConsumed = 5.0,
                )

            advanceTimeBy(1.seconds)

            val cooled = bucket.refill(config)

            assertTrue(
                cooled.warmupPermitsConsumed in 2.4..2.6,
                "After half the warmup idle, limiter should be half cooled, was ${cooled.warmupPermitsConsumed}",
            )
            assertTrue(
                cooled.refillInterval(config) in 390.milliseconds..410.milliseconds,
                "Half-cooled interval should be near 400ms, was ${cooled.refillInterval(config)}",
            )
        }
}
