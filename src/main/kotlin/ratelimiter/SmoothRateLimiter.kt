package ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeSource

@Suppress("FunctionName")
fun SmoothRateLimiter(
    permits: Int,
    per: Duration,
    warmup: Duration = Duration.ZERO,
    timeSource: TimeSource = TimeSource.Monotonic,
): RefundableRateLimiter = SmoothRateLimiterImpl(permits, per, warmup, timeSource)

internal class SmoothRateLimiterImpl(
    permits: Int,
    period: Duration,
    warmup: Duration,
    timeSource: TimeSource,
) : AtomicRateLimiter(
        BucketConfig(
            capacity = 1.0,
            timeSource = timeSource,
            stableRefillInterval = period / permits,
            warmup = warmup,
        ),
        PermitBucket(
            available = 1.0,
            refilledAt = timeSource.markNow(),
        ),
    ) {
    init {
        require(permits > 0) { "Permits must be positive, was $permits" }
        require(period > Duration.ZERO) { "Period must be positive, was $period" }
        require(warmup >= Duration.ZERO) { "Warmup can't be negative, was $warmup" }
    }

    override fun waitDuration(
        refilled: PermitBucket,
        next: PermitBucket,
    ): Duration {
        val deficit = minOf(next.available, 0.0)
        return refilled.refillInterval(config) * -deficit
    }
}
