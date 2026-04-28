package io.github.delder004.ratelimiter

/**
 * A [RateLimiter] whose acquired permits can be returned.
 */
public interface RefundableRateLimiter : RateLimiter {
    /**
     * Returns [permits] previously acquired from this limiter.
     *
     * @param permits number of permits to return; must be positive
     * @throws IllegalArgumentException if [permits] is not positive
     */
    public fun refund(permits: Int)
}
