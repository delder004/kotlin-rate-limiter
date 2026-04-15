package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PeekableRateLimiterTest {
    @Test
    fun `peekWait matches tryAcquire result without mutating fixed interval limiter`() =
        runTest {
            val limiter = BurstyRateLimiter(1, 1.seconds, testTimeSource) as PeekableRateLimiter

            // Fresh limiter has a stored permit: peek should return ZERO (grant).
            assertEquals(Duration.ZERO, limiter.peekWait(1))

            // Peek did not mutate — tryAcquire still grants.
            assertEquals(Permit.Granted, limiter.tryAcquire())

            // After drain, peek returns the same retryAfter tryAcquire would.
            val peek = limiter.peekWait(1)
            val tryResult = limiter.tryAcquire()
            assertIs<Permit.Denied>(tryResult)
            assertEquals(peek, tryResult.retryAfter)
        }

    @Test
    fun `peekWait matches tryAcquire result without mutating warming smooth limiter`() =
        runTest {
            val limiter =
                SmoothRateLimiter(
                    permits = 5,
                    per = 1.seconds,
                    warmup = 2.seconds,
                    timeSource = testTimeSource,
                ) as PeekableRateLimiter

            // Fresh limiter has its initial stored permit at the cold interval:
            // peek should return ZERO (grant). Repeated peeks must also return
            // ZERO — peek is non-mutating, so state doesn't drift between calls.
            assertEquals(Duration.ZERO, limiter.peekWait(1))
            assertEquals(Duration.ZERO, limiter.peekWait(1))
            assertEquals(Duration.ZERO, limiter.peekWait(1))

            // Confirm tryAcquire still grants the stored permit — peek did not
            // commit anything.
            assertEquals(Permit.Granted, limiter.tryAcquire())

            // After consuming the stored permit, peek reports the deny wait.
            val peek = limiter.peekWait(1)
            val tryResult = limiter.tryAcquire()
            assertIs<Permit.Denied>(tryResult)
            assertEquals(peek, tryResult.retryAfter)
        }
}
