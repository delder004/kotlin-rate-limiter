package io.github.delder004.ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeSource

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
 * @param timeSource monotonic source used to measure refill timing; must support
 *   comparable marks (both [TimeSource.Monotonic] and `kotlinx-coroutines-test`'s
 *   `testTimeSource` satisfy this)
 */
@Suppress("FunctionName")
fun SmoothRateLimiter(
    permits: Int,
    per: Duration,
    warmup: Duration = Duration.ZERO,
    timeSource: TimeSource = TimeSource.Monotonic,
): RefundableRateLimiter {
    require(permits > 0) { "permits must be positive, was $permits" }
    require(per > Duration.ZERO) { "per must be positive, was $per" }
    require(warmup >= Duration.ZERO) { "warmup must be non-negative, was $warmup" }
    require(timeSource is TimeSource.WithComparableMarks) {
        "timeSource must support comparable marks (TimeSource.WithComparableMarks)"
    }
    val stableInterval = per / permits
    return if (warmup == Duration.ZERO) {
        FixedIntervalLimiter(capacity = 1.0, interval = stableInterval, timeSource = timeSource)
    } else {
        WarmingSmoothLimiter(stableInterval = stableInterval, warmup = warmup, timeSource = timeSource)
    }
}
