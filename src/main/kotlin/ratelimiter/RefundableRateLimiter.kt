package ratelimiter

interface RefundableRateLimiter : RateLimiter {
    fun refund(permits: Int)
}
