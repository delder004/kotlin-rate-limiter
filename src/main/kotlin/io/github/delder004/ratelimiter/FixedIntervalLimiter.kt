package io.github.delder004.ratelimiter

import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Lock-based rate limiter whose schedule is a single `nextPermitAt` time mark.
 *
 * Each caller's wait is derived directly from how far `nextPermitAt` is in the
 * future; there is no signed balance, no refill step, and no warmup. Capacity
 * is encoded as an implicit floor on how far `nextPermitAt` may lag behind
 * "now" — after `capacity * interval` of idleness the bucket is considered
 * fully replenished, which matches the current Guava-style stored-permit cap.
 *
 * Cancellation and refund adjust the schedule state exactly under both
 * single-caller and concurrent conditions, because `nextPermitAt ± N * interval`
 * is a true inverse and the idle floor is idempotent. Callers already suspended
 * inside [acquire]'s `delay()` keep their committed wait — adjustments only
 * benefit callers that arrive after them.
 */
internal class FixedIntervalLimiter(
    private val capacity: Double,
    private val interval: Duration,
    private val timeSource: TimeSource.WithComparableMarks,
) : PeekableRateLimiter,
    ReservableRateLimiter {
    init {
        require(capacity >= 1.0) { "capacity must be at least 1.0, was $capacity" }
        require(interval > Duration.ZERO) { "interval must be positive, was $interval" }
    }

    private val lock = Any()

    // Start with capacity * interval of "idle credit" in the past, representing
    // a fully full bucket at construction. The first acquire of up to `capacity`
    // permits is immediate; the next caller waits for refill.
    private var nextPermitAt: ComparableTimeMark = timeSource.markNow() - interval * capacity

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

    override fun tryAcquire(permits: Int): Permit =
        synchronized(lock) {
            require(permits > 0) { "permits must be positive, was $permits" }
            val now = timeSource.markNow()
            val base = maxOf(nextPermitAt, now - interval * capacity)
            val newNext = base + interval * permits
            if (newNext > now) return Permit.Denied(newNext - now)
            nextPermitAt = newNext
            Permit.Granted
        }

    override fun refund(permits: Int): Unit =
        synchronized(lock) {
            require(permits > 0) { "permits must be positive, was $permits" }
            val now = timeSource.markNow()
            nextPermitAt = maxOf(nextPermitAt - interval * permits, now - interval * capacity)
        }

    override fun peekWait(permits: Int): Duration =
        synchronized(lock) {
            require(permits > 0) { "permits must be positive, was $permits" }
            val now = timeSource.markNow()
            val base = maxOf(nextPermitAt, now - interval * capacity)
            val newNext = base + interval * permits
            if (newNext > now) newNext - now else Duration.ZERO
        }

    override fun reserve(permits: Int): Reservation {
        require(permits > 0) { "permits must be positive, was $permits" }
        val wait =
            synchronized(lock) {
                val now = timeSource.markNow()
                val base = maxOf(nextPermitAt, now - interval * capacity)
                nextPermitAt = base + interval * permits
                if (nextPermitAt > now) nextPermitAt - now else Duration.ZERO
            }
        return FixedReservation(wait, permits)
    }

    private inner class FixedReservation(
        override val wait: Duration,
        private val permits: Int,
    ) : Reservation {
        override fun cancel() = refund(permits)
    }
}
