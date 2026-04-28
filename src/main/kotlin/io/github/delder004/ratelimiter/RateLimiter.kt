package io.github.delder004.ratelimiter

/**
 * Controls access to a finite stream of permits over time.
 *
 * Implementations are expected to be safe for concurrent use.
 */
public interface RateLimiter {
    /**
     * Suspends until [permits] can be acquired.
     *
     * @param permits number of permits to acquire; must be positive
     * @throws IllegalArgumentException if [permits] is not positive
     */
    public suspend fun acquire(permits: Int = 1)

    /**
     * Attempts to acquire [permits] immediately without suspending.
     *
     * @param permits number of permits to acquire; must be positive
     * @return [Permit.Granted] when the permits were acquired immediately, or
     * [Permit.Denied] with the delay until a retry may succeed
     * @throws IllegalArgumentException if [permits] is not positive
     */
    public fun tryAcquire(permits: Int = 1): Permit
}
