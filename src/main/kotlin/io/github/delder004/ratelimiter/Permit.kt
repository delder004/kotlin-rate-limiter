package io.github.delder004.ratelimiter

import kotlin.time.Duration

/**
 * Result of a non-suspending permit acquisition attempt.
 */
sealed interface Permit {
    /**
     * Indicates that the requested permits were acquired immediately.
     */
    data object Granted : Permit

    /**
     * Indicates that the requested permits are not yet available.
     *
     * @property retryAfter the wait a suspending acquisition would need from
     *   the limiter's current state. Not a guarantee that a later
     *   non-suspending [RateLimiter.tryAcquire] will succeed: under contention
     *   another caller may grab the freed credit first, and when the request
     *   exceeds a delegate's burst capacity `tryAcquire` cannot borrow from
     *   future refill at all (only suspending [RateLimiter.acquire] can).
     */
    data class Denied(
        val retryAfter: Duration,
    ) : Permit
}
