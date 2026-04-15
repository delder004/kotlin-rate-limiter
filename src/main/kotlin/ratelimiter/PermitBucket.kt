package ratelimiter

import kotlin.time.Duration
import kotlin.time.TimeMark

/**
 * Immutable state for the lock-free rate limiter.
 *
 * [balance] is signed: `> 0` means stored permits are available immediately,
 * `< 0` means permits have already been reserved against future refill
 * (Guava's "borrow from the future" pattern — the next caller waits for the
 * debt to clear before taking its own permits). [permitsOwed] exposes the
 * magnitude of that debt as a non-negative value.
 *
 * Every update refills first, repaying any negative balance *before* cooling
 * warmup state. The order matters: if warmup cooled over the full elapsed
 * window, the time spent repaying debt would be double-counted as idle.
 *
 * [warmupProgress] tracks how far the limiter has advanced through its warmup
 * budget, in permits. It starts at 0 (fully cold) and saturates at
 * `config.maxWarmupPermits` (fully warm); a higher value means a faster
 * refill interval for the next caller. See [BucketConfig] for the derivation.
 */
internal data class PermitBucket(
    val balance: Double,
    val asOf: TimeMark,
    val warmupProgress: Double = 0.0,
) {
    val permitsOwed: Double get() = (-balance).coerceAtLeast(0.0)

    fun warmupFraction(config: BucketConfig): Double =
        when (config.warmup) {
            Duration.ZERO -> 1.0
            else -> (warmupProgress / config.maxWarmupPermits).coerceIn(0.0, 1.0)
        }

    fun refillInterval(config: BucketConfig): Duration =
        config.coldRefillInterval + (config.stableRefillInterval - config.coldRefillInterval) * warmupFraction(config)

    // Sequence: repay any negative balance first, then cool warmup state only
    // with the elapsed time remaining after debt repayment, then clamp stored
    // permits to capacity. Swapping the first two steps would double-count
    // debt-repayment time as idle cooldown.
    fun refill(config: BucketConfig): PermitBucket {
        val elapsed = asOf.elapsedNow()
        if (elapsed <= Duration.ZERO) return this

        val interval = refillInterval(config)
        val repaid = repayDebt(interval, elapsed)
        val cooledProgress = cooledWarmupProgress(config, repaid.elapsedRemaining)

        return copy(
            balance = repaid.balance.coerceAtMost(config.capacity),
            asOf = config.timeSource.markNow(),
            warmupProgress = cooledProgress,
        )
    }

    // Allowed to drive [balance] negative. A negative balance means the
    // permits returned here were reserved from future refill and the caller
    // is expected to wait for the bucket to climb back to zero.
    fun consume(
        permits: Int,
        config: BucketConfig,
    ): PermitBucket =
        copy(
            balance = balance - permits,
            warmupProgress = warmupProgressAfterConsume(permits, config),
        )

    // Refill to "now" before refunding — refunding against stale state would
    // credit the returned permits against elapsed time that should only have
    // counted toward ordinary refill.
    //
    // Best-effort on warmup state: subtracts the nominal `permits` from
    // warmupProgress rather than the amount the matching consume actually
    // added. When the consume found fractional positive balance, the two
    // differ by `max(balance, 0)`. The cancellation path in AtomicRateLimiter
    // uses [refundCancelled] instead, which takes the exact delta from the
    // AcquireTransition that produced this state.
    fun refund(
        permits: Int,
        config: BucketConfig,
    ): PermitBucket {
        val refilled = refill(config)
        return refilled.copy(
            balance = (refilled.balance + permits).coerceAtMost(config.capacity),
            warmupProgress = (refilled.warmupProgress - permits).coerceAtLeast(0.0),
        )
    }

    // Slow-path cancellation refund, used only when another caller has
    // modified bucket state since the cancelled acquire CAS'd its reservation.
    // The common single-caller case is handled by an exact rewind in
    // AtomicRateLimiter.acquire — see there for the branching. This helper
    // subtracts the exact `warmupDelta` produced by the matching consume, but
    // it cannot un-do the phantom debt the cancelled acquire contributed to
    // the refill interval during the elapsed delay, so for elapsed > 0 it only
    // approximates "as if the acquire never happened". The remaining drift is
    // config- and timing-dependent.
    internal fun refundCancelled(
        permits: Int,
        warmupDelta: Double,
        config: BucketConfig,
    ): PermitBucket {
        val refilled = refill(config)
        return refilled.copy(
            balance = (refilled.balance + permits).coerceAtMost(config.capacity),
            warmupProgress = (refilled.warmupProgress - warmupDelta).coerceAtLeast(0.0),
        )
    }

    private data class RepaidDebt(
        val balance: Double,
        val elapsedRemaining: Duration,
    )

    private fun repayDebt(
        interval: Duration,
        elapsed: Duration,
    ): RepaidDebt {
        val refillAmount = elapsed / interval
        val newBalance = balance + refillAmount
        val debtRepaymentDuration = interval * (-balance).coerceAtLeast(0.0)
        val remaining = (elapsed - debtRepaymentDuration).coerceAtLeast(Duration.ZERO)
        return RepaidDebt(newBalance, remaining)
    }

    private fun cooledWarmupProgress(
        config: BucketConfig,
        elapsedRemaining: Duration,
    ): Double {
        if (config.cooldownInterval == Duration.ZERO) return warmupProgress
        val cooled = elapsedRemaining / config.cooldownInterval
        return (warmupProgress - cooled).coerceAtLeast(0.0)
    }

    private fun warmupProgressAfterConsume(
        permits: Int,
        config: BucketConfig,
    ): Double = (warmupProgress + permitsBorrowedFromFuture(permits)).coerceIn(0.0, config.maxWarmupPermits)

    private fun permitsBorrowedFromFuture(permits: Int): Double = (permits - balance.coerceAtLeast(0.0)).coerceAtLeast(0.0)
}
