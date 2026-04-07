package ratelimiter

import kotlin.time.Duration

sealed interface Permit {
    data object Granted : Permit

    data class Denied(
        val retryAfter: Duration,
    ) : Permit
}
