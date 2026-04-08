package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WarmupCooldownBugTest {
    /**
     * Issue 3: Cooldown from fully warm completes too early.
     *
     * refill() computes excess permits at the warm rate (200ms/permit), so
     * excessRefill overwhelms maxWarmupPermits in ~1.2s. In Guava's model,
     * the cooldown rate is warmupPeriod/maxPermits = 400ms/permit, requiring
     * ~2.2s to fully cool. After 75% of the warmup duration, the limiter
     * should still retain warmth — but the current code is already fully cold.
     */
    @Test
    fun `cooldown from warm should not complete before warmup duration elapses`() =
        runTest {
            val limiter = SmoothRateLimiter(5, 1.seconds, warmup = 2.seconds, testTimeSource)

            // Record cold-start intervals for comparison
            val coldDelays = recordDelays(limiter, 3)

            // Warm up fully
            recordDelays(limiter, 30)

            // Idle for 75% of warmup duration (1.5 seconds)
            advanceTimeBy(1500.milliseconds)

            // Record intervals after idle
            val afterIdleDelays = recordDelays(limiter, 3)

            // Should NOT have returned to cold-start behavior
            assertTrue(
                afterIdleDelays[1] < coldDelays[1],
                "After 75% of warmup idle, should retain some warmth. " +
                    "Got ${afterIdleDelays[1]}ms, same as cold start (${coldDelays[1]}ms)",
            )
        }

    /**
     * Issue 2: refill() uses a warmth-dependent interval for the refill rate.
     *
     * A warm bucket (interval=200ms) refills 3x faster than a cold bucket
     * (interval=600ms). In Guava, the cooldown rate is constant regardless
     * of warmth (1/coolDownInterval = 1/400ms). This test creates two
     * PermitBuckets at different warmth levels, idles them for the same
     * duration, and checks that the warmth decrease is proportional.
     */
    @Test
    fun `cooldown rate should be constant regardless of warmth level`() =
        runTest {
            val mark = testTimeSource.markNow()

            val config =
                BucketConfig(
                    capacity = 1.0,
                    timeSource = testTimeSource,
                    stableRefillInterval = 200.milliseconds,
                    warmup = 2.seconds,
                )

            val warmBucket =
                PermitBucket(
                    available = 0.0,
                    refilledAt = mark,
                    warmupPermitsConsumed = 5.0, // fully warm
                )

            val halfWarmBucket =
                PermitBucket(
                    available = 0.0,
                    refilledAt = mark,
                    warmupPermitsConsumed = 2.5, // half warm
                )

            advanceTimeBy(800.milliseconds)

            val warmRefilled = warmBucket.refill(config)
            val halfRefilled = halfWarmBucket.refill(config)

            val warmDrop = warmBucket.warmupPermitsConsumed - warmRefilled.warmupPermitsConsumed
            val halfDrop = halfWarmBucket.warmupPermitsConsumed - halfRefilled.warmupPermitsConsumed

            // Both buckets idled for the same 800ms. The warmth decrease should be
            // approximately the same if the cooldown rate is constant.
            // Current bug: warm drops 3.0 wpc, half-warm drops 1.0 wpc (3x ratio).
            assertTrue(
                warmDrop <= halfDrop * 2.0,
                "Warm bucket should not cool more than 2x faster than half-warm bucket. " +
                    "Warm lost $warmDrop wpc, half-warm lost $halfDrop wpc " +
                    "(ratio: ${if (halfDrop > 0) warmDrop / halfDrop else "inf"}x)",
            )
        }

    /**
     * Issue 2: refill() uses a single interval snapshot for the entire elapsed time.
     *
     * A single 1-second refill uses the warm interval (200ms) for the full
     * duration. Two 500ms refills are more accurate: the first cools the
     * bucket, so the second uses a colder (longer) interval and refills less.
     * In a correct integral-based implementation (or constant-rate like Guava),
     * the result would be path-independent.
     */
    @Test
    fun `refill result should not depend on how many times refill is called`() =
        runTest {
            val config =
                BucketConfig(
                    capacity = 1.0,
                    timeSource = testTimeSource,
                    stableRefillInterval = 200.milliseconds,
                    warmup = 2.seconds,
                )

            // t=0: create bucket
            val bucket =
                PermitBucket(
                    available = 0.0,
                    refilledAt = testTimeSource.markNow(), // refilledAt = t0
                    warmupPermitsConsumed = 5.0, // fully warm
                )

            // Two-step: refill at t=500ms, then again at t=1000ms
            advanceTimeBy(500.milliseconds)
            val step1 = bucket.refill(config) // 500ms elapsed from t0, updates refilledAt to t=500ms
            advanceTimeBy(500.milliseconds)
            val twoStep = step1.refill(config) // 500ms elapsed from t=500ms

            // One-step: refill original bucket at t=1000ms (1000ms elapsed from t0)
            // bucket is immutable — its refilledAt is still t0
            val oneStep = bucket.refill(config)

            // Both represent 1 second of total idle from the same initial state.
            // A path-independent refill would produce the same wpc.
            // Current bug: one-step wpc=1.0, two-step wpc=1.9375
            assertEquals(
                oneStep.warmupPermitsConsumed,
                twoStep.warmupPermitsConsumed,
                "Single 1s refill and two 500ms refills should produce the same warmth. " +
                    "One-step wpc=${oneStep.warmupPermitsConsumed}, " +
                    "two-step wpc=${twoStep.warmupPermitsConsumed}",
            )
        }
}
