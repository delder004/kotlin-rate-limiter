package ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeSource

internal data class BucketConfig(
    val capacity: Double,
    val timeSource: TimeSource,
    val stableRefillInterval: Duration,
    val warmup: Duration = Duration.ZERO,
) {
    val coldRefillInterval: Duration get() = stableRefillInterval * 3
    val maxWarmupPermits: Double get() = warmup * 2.0 / (coldRefillInterval + stableRefillInterval)
}
