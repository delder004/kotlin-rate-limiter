package ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeSource

private const val MAX_STORED_PERMITS = 1.0

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
            // Smooth limiters store at most one immediately-available permit and
            // encode throughput in the refill interval instead of bucket depth.
            capacity = MAX_STORED_PERMITS,
            timeSource = timeSource,
            stableRefillInterval = period / permits,
            warmup = warmup,
        ),
        PermitBucket(
            available = MAX_STORED_PERMITS,
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
    ): Duration = refilled.refillInterval(config) * -next.deficit
}
