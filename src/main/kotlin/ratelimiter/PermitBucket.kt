package ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeMark

internal data class PermitBucket(
    val available: Double,
    val refilledAt: TimeMark,
    val warmupPermitsConsumed: Double = 0.0,
) {
    fun warmth(config: BucketConfig): Double =
        when (config.warmup) {
            Duration.ZERO -> 1.0
            else -> (warmupPermitsConsumed / config.maxWarmupPermits).coerceIn(0.0, 1.0)
        }

    fun refillInterval(config: BucketConfig): Duration =
        config.coldRefillInterval + (config.stableRefillInterval - config.coldRefillInterval) * warmth(config)

    fun refill(config: BucketConfig): PermitBucket {
        val elapsed = refilledAt.elapsedNow()

        if (elapsed <= Duration.ZERO) return this

        val refillAmount = elapsed / refillInterval(config)
        val newlyAvailable = available + refillAmount
        val excessRefill = (newlyAvailable - config.capacity).coerceAtLeast(0.0)

        return copy(
            available = newlyAvailable.coerceAtMost(config.capacity),
            refilledAt = config.timeSource.markNow(),
            warmupPermitsConsumed = (warmupPermitsConsumed - excessRefill).coerceAtLeast(0.0),
        )
    }

    fun consume(
        permits: Int,
        config: BucketConfig,
    ): PermitBucket =
        copy(
            available = available - permits,
            warmupPermitsConsumed =
                (warmupPermitsConsumed + (permits - available.coerceAtLeast(0.0))).coerceIn(0.0, config.maxWarmupPermits),
        )

    fun refund(
        permits: Int,
        config: BucketConfig,
    ): PermitBucket {
        val refilled = refill(config)
        return refilled.copy(
            available = (refilled.available + permits).coerceAtMost(config.capacity),
            warmupPermitsConsumed = (refilled.warmupPermitsConsumed - permits).coerceAtLeast(0.0),
        )
    }
}
