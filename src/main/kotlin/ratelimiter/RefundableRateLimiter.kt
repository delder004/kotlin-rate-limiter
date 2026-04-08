package ratelimiter

internal interface RefundableRateLimiter : RateLimiter {
    fun refund(permits: Int)
}
