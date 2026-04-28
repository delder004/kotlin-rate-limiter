package io.github.delder004.ratelimiter

import kotlin.time.Duration

/**
 * Internal extension of [RefundableRateLimiter] for limiters that support
 * advance-permit reservation: state is advanced synchronously by [reserve],
 * the caller delays [Reservation.wait], and a successful wait commits the
 * permit. If the caller is cancelled before the wait completes,
 * [Reservation.cancel] rewinds the state precisely (using whatever pre-state
 * snapshot or arithmetic inverse the implementation needs — for warming
 * smooth limiters plain [refund] is not precise enough because heat accrual
 * is path-dependent).
 *
 * Used by [CompositeRateLimiter] to reserve every delegate up front and delay
 * once for `max(wait)`, so a single composite acquire advances every delegate
 * before suspending rather than charging them sequentially while waiting on
 * later delegates. The composite's reserve-all and rollback-all loops are
 * sequential — not globally atomic — so a concurrent observer touching the
 * delegates directly could see partial state mid-loop; the guarantee is only
 * that the composite caller does not suspend mid-reservation. Reservations
 * also let composite preserve borrow-from-future for
 * `permits > delegate.capacity`, which a `tryAcquire`-based retry loop cannot.
 */
internal interface ReservableRateLimiter : RefundableRateLimiter {
    fun reserve(permits: Int): Reservation
}

/**
 * Handle returned by [ReservableRateLimiter.reserve]. The implementation has
 * already advanced its schedule to commit the permit; the caller's job is to
 * wait [wait] and either let the wait complete (commits the reservation) or
 * call [cancel] to roll the state back.
 *
 * [cancel] must be called at most once and only on the cancellation path —
 * not after a successful wait.
 */
internal interface Reservation {
    val wait: Duration

    fun cancel()
}
