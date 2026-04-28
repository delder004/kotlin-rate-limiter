package io.github.delder004.ratelimiter

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
 * @param timeSource monotonic source used to measure refill timing; must support comparable marks
 *   (both [TimeSource.Monotonic] and `kotlinx-coroutines-test`'s `testTimeSource` satisfy this)
 */
@Suppress("FunctionName")
public fun BurstyRateLimiter(
    permits: Int,
    per: Duration,
    timeSource: TimeSource = TimeSource.Monotonic,
): RefundableRateLimiter {
    require(permits > 0) { "permits must be positive, was $permits" }
    require(per > Duration.ZERO) { "per must be positive, was $per" }
    require(timeSource is TimeSource.WithComparableMarks) {
        "timeSource must support comparable marks (TimeSource.WithComparableMarks)"
    }
    return FixedIntervalLimiter(
        capacity = permits.toDouble(),
        interval = per / permits,
        timeSource = timeSource,
    )
}
