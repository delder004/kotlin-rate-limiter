package ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeSource

private const val MAX_STORED_PERMITS = 1.0

/**
 * Creates a limiter that spreads permit issuance evenly over time, allowing at
 * most one permit to be available immediately.
 *
 * Unlike [BurstyRateLimiter], this prevents large bursts and enforces a
 * steady throughput rate, making it a better fit for APIs with strict
 * per-second quotas.
 *
 * When [warmup] is greater than zero, the limiter starts "cold" and gradually
 * ramps up to the steady-state rate across the warmup window.
 *
 * @param permits target number of permits produced per [per]; must be positive
 * @param per period over which [permits] are produced; must be positive
 * @param warmup optional ramp-up duration before reaching the steady-state rate
 * @param timeSource monotonic source used to measure refill timing
 * @return a [RefundableRateLimiter] that supports [refund][RefundableRateLimiter.refund] for returning unused permits
 */
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
            balance = MAX_STORED_PERMITS,
            asOf = timeSource.markNow(),
        ),
    ) {
    init {
        require(permits > 0) { "Permits must be positive, was $permits" }
        require(period > Duration.ZERO) { "Period must be positive, was $period" }
        require(warmup >= Duration.ZERO) { "Warmup can't be negative, was $warmup" }
    }

    // Uses `refilled.refillInterval` (pre-consume), not `next.refillInterval`
    // (post-consume). Accruing warmup progress pushes the refill interval
    // toward stable (faster); charging the caller the post-consume interval
    // would credit this acquisition for warmup progress it only just caused.
    override fun waitDuration(
        refilled: PermitBucket,
        next: PermitBucket,
    ): Duration = refilled.refillInterval(config) * next.permitsOwed
}
