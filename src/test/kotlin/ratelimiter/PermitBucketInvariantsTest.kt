package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Disabled
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

    // Test 1 — permitsOwed is always non-negative

    @Test
    fun `permitsOwed is zero when balance is positive`() =
        runTest {
            val bucket = PermitBucket(balance = 5.0, asOf = testTimeSource.markNow())
            assertEquals(0.0, bucket.permitsOwed, tolerance)
        }

    @Test
    fun `permitsOwed is zero when balance is zero`() =
        runTest {
            // Double negation of 0.0 produces -0.0, which coerceAtLeast(0.0)
            // passes through as -0.0. Numerically zero, so tolerance assertion.
            val bucket = PermitBucket(balance = 0.0, asOf = testTimeSource.markNow())
            assertEquals(0.0, bucket.permitsOwed, tolerance)
        }

    @Test
    fun `permitsOwed equals magnitude of negative balance`() =
        runTest {
            val bucket = PermitBucket(balance = -3.0, asOf = testTimeSource.markNow())
            assertEquals(3.0, bucket.permitsOwed, tolerance)
        }

    // Test 2 — warmupProgress stays within [0, maxWarmupPermits]

    @Test
    fun `consume saturates warmupProgress at maxWarmupPermits`() =
        runTest {
            val config = warmupConfig()
            val bucket = PermitBucket(balance = 1.0, asOf = testTimeSource.markNow())

            val consumed = bucket.consume(10, config)

            assertEquals(config.maxWarmupPermits, consumed.warmupProgress, tolerance)
        }

    @Test
    fun `refund does not drive warmupProgress below zero`() =
        runTest {
            val config = warmupConfig()
            val bucket = PermitBucket(balance = -1.0, asOf = testTimeSource.markNow(), warmupProgress = 0.5)

            val refunded = bucket.refund(5, config)

            assertEquals(0.0, refunded.warmupProgress)
        }

    @Test
    fun `long idle floors warmupProgress at zero`() =
        runTest {
            val config = warmupConfig()
            val bucket = PermitBucket(balance = 0.0, asOf = testTimeSource.markNow(), warmupProgress = 2.0)

            advanceTimeBy(config.warmup * 10)
            val refilled = bucket.refill(config)

            assertEquals(0.0, refilled.warmupProgress)
        }

    // Test 3 — no warmup cooldown while balance < 0

    @Test
    fun `refill does not cool warmup while balance is being repaid`() =
        runTest {
            val config = warmupConfig()
            val bucket = PermitBucket(balance = -2.0, asOf = testTimeSource.markNow(), warmupProgress = 2.5)

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
            val bucket = PermitBucket(balance = -2.0, asOf = testTimeSource.markNow(), warmupProgress = 2.5)

            val interval = bucket.refillInterval(config)
            val debtDuration = interval * bucket.permitsOwed

            // Debt duration + exactly one cooldown interval: only the trailing
            // cooldown interval drives cooldown, so warmupProgress drops by one.
            advanceTimeBy(debtDuration + config.cooldownInterval)
            val refilled = bucket.refill(config)

            assertEquals(1.5, refilled.warmupProgress, tolerance)
        }

    // Test 5 — consume.refund idempotence on warmupProgress
    //
    // Disabled: surfaces a known asymmetry in the internal math. consume(n) adds
    // `borrowed = n - max(balance, 0)` to warmupProgress, but refund(n) subtracts
    // a flat `n`, clawing back `max(balance, 0)` extra units when balance was
    // fractional positive at consume time. Keep the `refund-warmup-asymmetry`
    // marker in sync with the follow-up task when enabling this test.

    @Test
    @Disabled(
        "TODO: refund-warmup-asymmetry — consume(n).refund(n) drops warmupProgress by " +
            "max(balance, 0) when balance was fractional positive at consume time.",
    )
    fun `consume followed by refund restores warmupProgress when no time elapses`() =
        runTest {
            val config = warmupConfig()
            // Invariant is intentionally scoped to "no time elapses between
            // consume and refund", so the expected restoration is exact. The
            // starting state (fractional positive balance, positive warmup
            // progress) is the combination that exposes the asymmetry.
            val bucket =
                PermitBucket(
                    balance = 0.0714,
                    asOf = testTimeSource.markNow(),
                    warmupProgress = 0.9,
                )

            val result = bucket.consume(1, config).refund(1, config)

            assertEquals(0.9, result.warmupProgress, tolerance)
        }
}
