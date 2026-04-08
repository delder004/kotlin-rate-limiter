package ratelimiter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Acquires [permits], runs [block], and returns its result.
 *
 * This helper does not automatically refund permits when [block] fails or is
 * cancelled.
 *
 * @param permits number of permits to acquire before invoking [block]
 * @param block work to execute after the permits have been acquired
 */
suspend fun <T> RateLimiter.withPermit(
    permits: Int = 1,
    block: suspend () -> T,
): T {
    acquire(permits)
    return block()
}

/**
 * Delays each emitted element until [limiter] grants [permits].
 *
 * Backpressure is applied before forwarding each upstream value downstream.
 *
 * @param limiter limiter consulted before each emission
 * @param permits number of permits to acquire per emitted element
 */
fun <T> Flow<T>.rateLimit(
    limiter: RateLimiter,
    permits: Int = 1,
): Flow<T> =
    flow {
        collect { value ->
            limiter.acquire(permits)
            emit(value)
        }
    }
