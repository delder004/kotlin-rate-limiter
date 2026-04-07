package ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal data class PermitBucket(
    val available: Double,
    val capacity: Double,
    val timeSource: TimeSource,
    val refilledAt: TimeMark,
    val refillInterval: Duration,
) {
    fun refill(): PermitBucket {
        val elapsed = refilledAt.elapsedNow()

        if (elapsed <= Duration.ZERO) return this

        val refillAmount = elapsed / refillInterval

        return this.copy(
            available = (available + refillAmount).coerceAtMost(capacity),
            refilledAt = timeSource.markNow()
        )
    }

    fun remove(permits: Int): PermitBucket {
        val refilled = refill()
        return refilled.copy(
            available = refilled.available - permits,
        )
    }

    fun replace(permits: Int): PermitBucket {
        val refilled = refill()
        return refilled.copy(
            available = (refilled.available + permits).coerceAtMost(capacity),
        )
    }
}