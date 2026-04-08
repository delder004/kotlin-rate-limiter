package ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Creates a token-bucket style limiter that allows short bursts up to [permits]
 * and replenishes that capacity over each [per].
 *
 * Unlike [SmoothRateLimiter], all stored permits may be consumed at once,
 * making this a good fit for workloads with natural spikes.
 *
 * @param permits maximum burst size and total permits restored per period; must be positive
 * @param per period over which [permits] are replenished; must be positive
 * @param timeSource monotonic source used to measure refill timing
 * @return a [RefundableRateLimiter] that supports [refund][RefundableRateLimiter.refund] for returning unused permits
 */
@Suppress("FunctionName")
fun BurstyRateLimiter(
    permits: Int,
    per: Duration,
    timeSource: TimeSource = TimeSource.Monotonic,
): RefundableRateLimiter = BurstyRateLimiterImpl(permits, per, timeSource)

internal class BurstyRateLimiterImpl(
    permits: Int,
    period: Duration,
    timeSource: TimeSource,
) : AtomicRateLimiter(
        BucketConfig(
            capacity = permits.toDouble(),
            timeSource = timeSource,
            stableRefillInterval = period / permits,
        ),
        PermitBucket(
            available = permits.toDouble(),
            refilledAt = timeSource.markNow(),
        ),
    ) {
    init {
        require(permits > 0) { "Permits must be positive, was $permits" }
        require(period > Duration.ZERO) { "Period must be positive, was $period" }
    }

    override fun waitDuration(
        refilled: PermitBucket,
        next: PermitBucket,
    ): Duration = next.refillInterval(config) * -next.deficit
}
