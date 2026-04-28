package io.github.delder004.ratelimiter

import kotlin.time.Duration

/**
 * Internal extension of [RefundableRateLimiter] exposing a non-mutating
 * computation of what [tryAcquire] would return. Used by
 * [CompositeRateLimiter] to probe delegates for `retryAfter` without
 * committing state changes.
 *
 * This exists because `tryAcquire + refund` is not state-neutral for
 * [WarmingSmoothLimiter]: a granted `tryAcquire` leaves heat unchanged
 * (the grant drew from stored credit, not borrowed future refill), but
 * `refund` unconditionally subtracts `permits` from heat. Using
 * [peekWait] in the composite probe path sidesteps the asymmetry by
 * avoiding mutation entirely.
 *
 * Only the in-repo internal implementations ([FixedIntervalLimiter] and
 * [WarmingSmoothLimiter]) implement this. Third-party
 * [RefundableRateLimiter] implementations in a composite fall back to
 * the probe-and-rollback `tryAcquire + refund` pattern, inheriting
 * whatever semantics their own `refund` provides.
 */
internal interface PeekableRateLimiter : RefundableRateLimiter {
    /**
     * Returns the wait that [tryAcquire] would compute for [permits],
     * without mutating limiter state. `Duration.ZERO` means the request
     * would be granted immediately; any positive value is the
     * `retryAfter` that a denial would carry.
     */
    fun peekWait(permits: Int): Duration
}
