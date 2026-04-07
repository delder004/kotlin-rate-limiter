package ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal data class PermitBucket(
    val available: Double,
    val capacity: Double,
    val timeSource: TimeSource,
    val refilledAt: TimeMark,
    val stableRefillInterval: Duration,
    val warmup: Duration = Duration.ZERO,
    val warmupPermitsConsumed: Double = 0.0,
) {
    val coldRefillInterval: Duration = stableRefillInterval * 3
    val maxWarmupPermits = warmup * 2.0 / (coldRefillInterval + stableRefillInterval)
    val warmth: Double get() =
        when (warmup) {
            Duration.ZERO -> 1.0
            else -> (warmupPermitsConsumed / maxWarmupPermits).coerceIn(0.0, 1.0)
        }
    val refillInterval: Duration get() {
        return coldRefillInterval + (stableRefillInterval - coldRefillInterval) * warmth
    }

    fun refill(): PermitBucket {
        val elapsed = refilledAt.elapsedNow()

        if (elapsed <= Duration.ZERO) return this

        val refillAmount = elapsed / refillInterval
        val newlyAvailable = available + refillAmount
        val excessRefill = (newlyAvailable - capacity).coerceAtLeast(0.0)

        return this.copy(
            available = newlyAvailable.coerceAtMost(capacity),
            refilledAt = timeSource.markNow(),
            warmupPermitsConsumed = (warmupPermitsConsumed - excessRefill).coerceAtLeast(0.0),
        )
    }

    fun remove(permits: Int): PermitBucket {
        val refilled = refill()
        return refilled.copy(
            available = refilled.available - permits,
            warmupPermitsConsumed = (refilled.warmupPermitsConsumed + permits).coerceAtMost(maxWarmupPermits),
        )
    }

    fun replace(permits: Int): PermitBucket {
        val refilled = refill()
        return refilled.copy(
            available = (refilled.available + permits).coerceAtMost(capacity),
            warmupPermitsConsumed = refilled.warmupPermitsConsumed - permits,
        )
    }
}
