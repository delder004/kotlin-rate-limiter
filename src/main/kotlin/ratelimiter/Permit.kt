package ratelimiter

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
     * @property retryAfter minimum time to wait before retrying the same request
     */
    data class Denied(
        val retryAfter: Duration,
    ) : Permit
}
