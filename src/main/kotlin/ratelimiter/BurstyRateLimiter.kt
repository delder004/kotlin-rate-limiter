package ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeSource

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
    ): Duration {
        val deficit = minOf(next.available, 0.0)
        return next.refillInterval(config) * -deficit
    }
}
