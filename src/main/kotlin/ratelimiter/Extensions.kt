package ratelimiter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

suspend fun <T> RateLimiter.withPermit(permits: Int, block: () -> T): T {
    acquire(permits)
    return block()
}

fun <T> Flow<T>.rateLimit(limiter: RateLimiter, permits: Int = 1): Flow<T> = flow {
    collect { value ->
        limiter.acquire(permits)
        emit(value)
    }
}