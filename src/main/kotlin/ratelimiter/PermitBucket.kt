package ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeMark

internal data class PermitBucket(
    val available: Double,
    val refilledAt: TimeMark,
    val warmupPermitsConsumed: Double = 0.0,
) {
    val deficit: Double get() = available.coerceAtMost(0.0)

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

        val currentRefillInterval = refillInterval(config)
        val refillAmount = elapsed / currentRefillInterval
        val availableAfterRefill = available + refillAmount
        val storedPermits = availableAfterRefill.coerceAtMost(config.capacity)
        val timeUntilDebtIsRepaid = currentRefillInterval * (-available).coerceAtLeast(0.0)
        val cooldownElapsed = (elapsed - timeUntilDebtIsRepaid).coerceAtLeast(Duration.ZERO)
        val cooledWarmupDebt =
            if (config.cooldownInterval == Duration.ZERO) {
                0.0
            } else {
                cooldownElapsed / config.cooldownInterval
            }

        return copy(
            available = storedPermits,
            refilledAt = config.timeSource.markNow(),
            // Warmup debt cools down at a constant rate, independent of current warmth.
            warmupPermitsConsumed = (warmupPermitsConsumed - cooledWarmupDebt).coerceAtLeast(0.0),
        )
    }

    fun consume(
        permits: Int,
        config: BucketConfig,
    ): PermitBucket =
        copy(
            available = available - permits,
            warmupPermitsConsumed = updatedWarmupDebt(permits, config),
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

    private fun updatedWarmupDebt(
        permits: Int,
        config: BucketConfig,
    ): Double {
        val consumedFromFutureCapacity = (permits - available.coerceAtLeast(0.0)).coerceAtLeast(0.0)
        return (warmupPermitsConsumed + consumedFromFutureCapacity).coerceIn(0.0, config.maxWarmupPermits)
    }
}
