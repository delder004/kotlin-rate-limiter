package ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Configuration for a [PermitBucket].
 *
 * During warmup, the refill interval ramps linearly from [coldRefillInterval]
 * down to [stableRefillInterval] as warmup progress accrues. [maxWarmupPermits]
 * is derived so that advancing from zero progress to full progress takes
 * exactly [warmup] time: with an average per-permit interval of
 * `(cold + stable) / 2`, the identity `N * (cold + stable) / 2 = warmup` gives
 * `N = 2 * warmup / (cold + stable)`.
 *
 * [cooldownInterval] applies the same budget in reverse — idle time refunds
 * one unit of warmup progress every `warmup / maxWarmupPermits`, so a full
 * cooldown from fully-warm to cold takes exactly [warmup] time, matching the
 * warm-up cost.
 */
internal data class BucketConfig(
    val capacity: Double,
    val timeSource: TimeSource,
    val stableRefillInterval: Duration,
    val warmup: Duration = Duration.ZERO,
) {
    val coldRefillInterval: Duration get() = stableRefillInterval * 3
    val maxWarmupPermits: Double get() = warmup * 2.0 / (coldRefillInterval + stableRefillInterval)
    val cooldownInterval: Duration get() = if (warmup == Duration.ZERO) Duration.ZERO else warmup / maxWarmupPermits
}
