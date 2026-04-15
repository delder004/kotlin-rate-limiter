package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PermitBucketInvariantsTest {
    private val tolerance = 1e-9

    private fun TestScope.warmupConfig(): BucketConfig =
        BucketConfig(
            capacity = 1.0,
            timeSource = testTimeSource,
            stableRefillInterval = 200.milliseconds,
            warmup = 2.seconds,
        )

    @Test
    fun `permitsOwed is zero when balance is positive`() =
        runTest {
            val bucket = PermitBucket.Stable(balance = 5.0, asOf = testTimeSource.markNow())
            assertEquals(0.0, bucket.permitsOwed, tolerance)
        }

    @Test
    fun `permitsOwed is zero when balance is zero`() =
        runTest {
            // Double negation of 0.0 produces -0.0, which coerceAtLeast(0.0)
            // passes through as -0.0. Numerically zero, so tolerance assertion.
            val bucket = PermitBucket.Stable(balance = 0.0, asOf = testTimeSource.markNow())
            assertEquals(0.0, bucket.permitsOwed, tolerance)
        }

    @Test
    fun `permitsOwed equals magnitude of negative balance`() =
        runTest {
            val bucket = PermitBucket.Stable(balance = -3.0, asOf = testTimeSource.markNow())
            assertEquals(3.0, bucket.permitsOwed, tolerance)
        }

    @Test
    fun `consume saturates warmupProgress at maxWarmupPermits`() =
        runTest {
            val config = warmupConfig()
            val bucket = PermitBucket.Warming(balance = 1.0, asOf = testTimeSource.markNow())

            val consumed = bucket.consume(10, config).bucket

            assertEquals(config.maxWarmupPermits, consumed.warmupProgress, tolerance)
        }

    @Test
    fun `refund does not drive warmupProgress below zero`() =
        runTest {
            val config = warmupConfig()
            val bucket = PermitBucket.Warming(balance = -1.0, asOf = testTimeSource.markNow(), warmupProgress = 0.5)

            val refunded = bucket.refund(5, config)

            assertEquals(0.0, refunded.warmupProgress)
        }

    @Test
    fun `long idle floors warmupProgress at zero`() =
        runTest {
            val config = warmupConfig()
            val bucket = PermitBucket.Warming(balance = 0.0, asOf = testTimeSource.markNow(), warmupProgress = 2.0)

            advanceTimeBy(config.warmup * 10)
            val refilled = bucket.refill(config)

            assertEquals(0.0, refilled.warmupProgress)
        }

    @Test
    fun `refill does not cool warmup while balance is being repaid`() =
        runTest {
            val config = warmupConfig()
            val bucket = PermitBucket.Warming(balance = -2.0, asOf = testTimeSource.markNow(), warmupProgress = 2.5)

            val interval = bucket.refillInterval(config)
            val debtDuration = interval * bucket.permitsOwed

            // Halfway through debt repayment: every microsecond of elapsed time
            // is consumed by repaying debt, leaving nothing for cooldown. If the
            // refill ordering were swapped, warmupProgress would drop below 2.5.
            advanceTimeBy(debtDuration / 2)
            val refilled = bucket.refill(config)

            assertEquals(2.5, refilled.warmupProgress, tolerance)
        }

    @Test
    fun `refill cools warmup only by the elapsed time beyond debt repayment`() =
        runTest {
            val config = warmupConfig()
            val bucket = PermitBucket.Warming(balance = -2.0, asOf = testTimeSource.markNow(), warmupProgress = 2.5)

            val interval = bucket.refillInterval(config)
            val debtDuration = interval * bucket.permitsOwed

            // Debt duration + exactly one cooldown interval: only the trailing
            // cooldown interval drives cooldown, so warmupProgress drops by one.
            advanceTimeBy(debtDuration + config.cooldownInterval)
            val refilled = bucket.refill(config)

            assertEquals(1.5, refilled.warmupProgress, tolerance)
        }

    @Test
    fun `refundCancelled reverses consume when no time elapses`() =
        runTest {
            val config = warmupConfig()
            // Fractional positive balance exposes the difference between exact
            // cancellation accounting and the public best-effort refund.
            val bucket =
                PermitBucket.Warming(
                    balance = 0.0714,
                    asOf = testTimeSource.markNow(),
                    warmupProgress = 0.9,
                )

            val consumed = bucket.consume(1, config)
            val result = consumed.bucket.refundCancelled(1, consumed.warmupDelta, config)

            assertEquals(bucket.balance, result.balance, tolerance)
            assertEquals(bucket.warmupProgress, result.warmupProgress, tolerance)
        }

    @Test
    fun `public refund is best-effort on warmup state`() =
        runTest {
            val config = warmupConfig()
            // Same starting state as the exact-cancellation test.
            val bucket =
                PermitBucket.Warming(
                    balance = 0.0714,
                    asOf = testTimeSource.markNow(),
                    warmupProgress = 0.9,
                )

            val result = bucket.consume(1, config).bucket.refund(1, config)

            // refund() subtracts the nominal permit count from warmupProgress,
            // so a fractional positive balance leaves it short by max(balance, 0).
            assertEquals(bucket.warmupProgress - bucket.balance, result.warmupProgress, tolerance)
        }
}
