package ratelimiter


interface RateLimiter {
    suspend fun acquire(permits: Int = 1): Unit
    fun tryAcquire(permits: Int = 1): Permit
}