package io.github.delder004.ratelimiter

import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Lock-based smooth rate limiter with warmup ramp.
 *
 * State is a single schedule mark ([nextPermitAt]) plus a warmup-progress
 * accumulator ([heat]). The per-permit interval interpolates linearly between
 * [coldInterval] (heat=0) and [stableInterval] (heat=maxHeat). Heat rises on
 * acquires that borrow from future refill and cools linearly during true idle
 * time — idle excludes any window spent paying down debt already committed to
 * another caller.
 *
 * Cancellation of an in-flight `acquire()` takes a fast path when no other
 * caller has mutated limiter state since the reservation committed: the
 * pre-reservation snapshot is restored and cooling is replayed to the cancel
 * time, giving an exact rewind for any elapsed delay. Under contention, the
 * slow path subtracts the exact heat contribution and tail-adjusts
 * [nextPermitAt] at the current interval — best-effort, with drift bounded by
 * `permits * (coldInterval - stableInterval)` in the schedule and by the
 * reservation's own heat delta in [heat], self-correcting via cooldown. As
 * with [FixedIntervalLimiter], callers already suspended inside [delay] keep
 * their committed wait.
 *
 * The zero-warmup case is routed to [FixedIntervalLimiter] at the factory
 * layer; this class requires [warmup] to be strictly positive so the
 * cooldown arithmetic has no zero-division edge case.
 */
internal class WarmingSmoothLimiter(
    private val stableInterval: Duration,
    warmup: Duration,
    private val timeSource: TimeSource.WithComparableMarks,
) : PeekableRateLimiter,
    ReservableRateLimiter {
    init {
        require(stableInterval > Duration.ZERO) { "stableInterval must be positive, was $stableInterval" }
        require(warmup > Duration.ZERO) { "warmup must be positive, was $warmup" }
    }

    private val coldInterval = stableInterval * 3
    private val maxHeat = warmup * 2.0 / (coldInterval + stableInterval)
    private val cooldownInterval = warmup / maxHeat

    private val lock = Any()

    // Seed all three marks from a single `initialNow` so the "one stored cold
    // permit" invariant holds against a single wall-clock instant.
    private val initialNow: ComparableTimeMark = timeSource.markNow()
    private var nextPermitAt: ComparableTimeMark = initialNow - coldInterval
    private var heat: Double = 0.0
    private var heatUpdatedAt: ComparableTimeMark = initialNow
    private var version: Long = 0L

    private data class PreState(
        val nextPermitAt: ComparableTimeMark,
        val heat: Double,
        val heatUpdatedAt: ComparableTimeMark,
        val version: Long,
    )

    /**
     * Reads the four pre-mutation fields. This helper exists to enforce the
     * ordering invariant that the snapshot must be captured before
     * [coolHeatTo] runs — if a future edit moves this call below `coolHeatTo`,
     * the fast-path cancellation path will restore already-cooled state and
     * replay cooling against the same window twice. Keep the `capturePreState`
     * call the *first* thing inside [reserve]'s `synchronized` block.
     */
    private fun capturePreState(): PreState = PreState(nextPermitAt, heat, heatUpdatedAt, version)

    override suspend fun acquire(permits: Int) {
        require(permits > 0) { "permits must be positive, was $permits" }
        val reservation = reserve(permits)
        try {
            delay(reservation.wait)
        } catch (e: CancellationException) {
            reservation.cancel()
            throw e
        }
    }

    override fun reserve(permits: Int): Reservation {
        require(permits > 0) { "permits must be positive, was $permits" }
        return synchronized(lock) {
            val pre = capturePreState()
            val now = timeSource.markNow()
            coolHeatTo(now)
            val (wait, heatDelta) = reserveAfterCool(now, permits)
            version++
            WarmingReservation(wait, permits, heatDelta, pre)
        }
    }

    private inner class WarmingReservation(
        override val wait: Duration,
        private val permits: Int,
        private val heatDelta: Double,
        private val pre: PreState,
    ) : Reservation {
        override fun cancel() {
            synchronized(lock) {
                val now = timeSource.markNow()
                if (version == pre.version + 1) {
                    // Fast path: restore pre-reservation state and advance cooling
                    // to now. Exact for any elapsed delay.
                    nextPermitAt = pre.nextPermitAt
                    heat = pre.heat
                    heatUpdatedAt = pre.heatUpdatedAt
                    coolHeatTo(now)
                } else {
                    // Slow path: concurrent mutations moved the tail while we
                    // waited. Tail-adjust at the current interval and subtract
                    // the exact heat contribution this reservation made.
                    coolHeatTo(now)
                    val interval = currentInterval()
                    nextPermitAt = maxOf(nextPermitAt - interval * permits, now - interval)
                    heat = (heat - heatDelta).coerceAtLeast(0.0)
                }
                version++
            }
        }
    }

    override fun tryAcquire(permits: Int): Permit =
        synchronized(lock) {
            require(permits > 0) { "permits must be positive, was $permits" }

            val now = timeSource.markNow()
            coolHeatTo(now)
            val interval = currentInterval()
            val base = maxOf(nextPermitAt, now - interval)
            val newNext = base + interval * permits

            if (newNext > now) {
                // Denial still commits the cooling mutation from coolHeatTo.
                version++
                return Permit.Denied(newNext - now)
            }

            // A granted tryAcquire never borrows from the future, so heat is
            // unchanged: wait == 0 forces nextPermitAt <= now - interval, which
            // means a full stored permit was available and borrowed = 0.
            nextPermitAt = newNext
            version++
            Permit.Granted
        }

    override fun refund(permits: Int): Unit =
        synchronized(lock) {
            require(permits > 0) { "permits must be positive, was $permits" }

            val now = timeSource.markNow()
            coolHeatTo(now)
            val interval = currentInterval()
            nextPermitAt = maxOf(nextPermitAt - interval * permits, now - interval)
            heat = (heat - permits).coerceAtLeast(0.0)
            version++
        }

    // Simulates `coolHeatTo(now)` into a local `simHeat` and runs the grant/deny
    // arithmetic without touching [heat], [heatUpdatedAt], [nextPermitAt], or
    // [version]. Used by [CompositeRateLimiter] to probe retryAfter without
    // committing state changes that `tryAcquire + refund` can't cleanly undo
    // (granted tryAcquire leaves heat unchanged; refund always decrements it).
    override fun peekWait(permits: Int): Duration =
        synchronized(lock) {
            require(permits > 0) { "permits must be positive, was $permits" }
            val now = timeSource.markNow()

            val idleStart = maxOf(heatUpdatedAt, nextPermitAt)
            val simHeat =
                if (idleStart < now) {
                    (heat - (now - idleStart) / cooldownInterval).coerceAtLeast(0.0)
                } else {
                    heat
                }

            val f = (simHeat / maxHeat).coerceIn(0.0, 1.0)
            val interval = coldInterval + (stableInterval - coldInterval) * f
            val base = maxOf(nextPermitAt, now - interval)
            val newNext = base + interval * permits
            if (newNext > now) newNext - now else Duration.ZERO
        }

    private fun currentInterval(): Duration {
        val f = (heat / maxHeat).coerceIn(0.0, 1.0)
        return coldInterval + (stableInterval - coldInterval) * f
    }

    // Cools heat from true idle only. The window `[heatUpdatedAt, nextPermitAt]`
    // (when `nextPermitAt` is the later of the two) was spent paying down debt,
    // not idling, so it does not contribute to cooling.
    private fun coolHeatTo(now: ComparableTimeMark) {
        val idleStart = maxOf(heatUpdatedAt, nextPermitAt)
        if (idleStart < now) {
            heat = (heat - (now - idleStart) / cooldownInterval).coerceAtLeast(0.0)
        }
        heatUpdatedAt = now
    }

    // Precondition: `coolHeatTo(now)` has just run. Reads the pre-consume
    // interval once — the acquire pays the rate it observes, not the rate its
    // own heat accrual is about to cause.
    private fun reserveAfterCool(
        now: ComparableTimeMark,
        permits: Int,
    ): Pair<Duration, Double> {
        val interval = currentInterval()

        val stored =
            if (nextPermitAt <= now) {
                ((now - nextPermitAt) / interval).coerceAtMost(1.0)
            } else {
                0.0
            }
        val borrowed = (permits - stored).coerceAtLeast(0.0)

        val newHeat = (heat + borrowed).coerceIn(0.0, maxHeat)
        val heatDelta = newHeat - heat
        heat = newHeat

        val base = maxOf(nextPermitAt, now - interval)
        nextPermitAt = base + interval * permits

        val wait = if (nextPermitAt > now) nextPermitAt - now else Duration.ZERO
        return wait to heatDelta
    }
}
