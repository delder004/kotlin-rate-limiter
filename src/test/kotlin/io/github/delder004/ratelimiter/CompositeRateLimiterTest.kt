package io.github.delder004.ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeRateLimiterTest : RateLimiterContractTest() {
    override fun TestScope.createLimiter(
        permits: Int,
        per: Duration,
    ): RateLimiter =
        CompositeRateLimiter(
            BurstyRateLimiter(permits, per, testTimeSource),
            SmoothRateLimiter(permits, per, timeSource = testTimeSource),
        )

    @Test
    fun `acquire succeeds when all limiters have capacity`() =
        runTest {
            val limiter =
                CompositeRateLimiter(
                    BurstyRateLimiter(5, 1.seconds, testTimeSource),
                    BurstyRateLimiter(10, 1.seconds, testTimeSource),
                )
            val before = currentTime
            limiter.acquire()
            assertEquals(0L, currentTime - before)
        }

    @Test
    fun `acquire suspends when any limiter is exhausted`() =
        runTest {
            val fast = BurstyRateLimiter(10, 1.seconds, testTimeSource)
            val slow = BurstyRateLimiter(1, 1.seconds, testTimeSource)
            val limiter = CompositeRateLimiter(fast, slow)

            limiter.acquire() // exhausts slow

            val job = launch { limiter.acquire() }
            runCurrent()
            assertFalse(job.isCompleted)

            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(job.isCompleted)
        }

    @Test
    fun `respects most restrictive limiter`() =
        runTest {
            val limiter =
                CompositeRateLimiter(
                    BurstyRateLimiter(10, 1.seconds, testTimeSource),
                    BurstyRateLimiter(2, 1.seconds, testTimeSource),
                )
            // 2/sec is more restrictive — interval is 500ms
            val delays = recordDelays(limiter, 3)
            assertEquals(listOf(0L, 0L, 500L), delays)
        }

    @Test
    fun `tryAcquire returns Denied if any limiter denies`() =
        runTest {
            val fast = BurstyRateLimiter(10, 1.seconds, testTimeSource)
            val slow = BurstyRateLimiter(1, 1.seconds, testTimeSource)
            val limiter = CompositeRateLimiter(fast, slow)

            limiter.acquire() // exhausts slow but not fast
            assertIs<Permit.Denied>(limiter.tryAcquire())
        }

    @Test
    fun `tryAcquire returns longest retryAfter`() =
        runTest {
            val fast = BurstyRateLimiter(5, 1.seconds, testTimeSource) // interval = 200ms
            val slow = BurstyRateLimiter(2, 1.seconds, testTimeSource) // interval = 500ms
            val limiter = CompositeRateLimiter(fast, slow)

            // exhaust both
            limiter.acquire(5) // exhausts fast (5/5), also takes 5 from slow (but slow only has 2)

            val denied = limiter.tryAcquire()
            assertIs<Permit.Denied>(denied)
            // slow's retryAfter should be longer than fast's
            assertTrue(denied.retryAfter >= 500.milliseconds)
        }

    @Test
    fun `tryAcquire denied does not consume permits from other limiters`() =
        runTest {
            val fast = BurstyRateLimiter(2, 1.seconds, testTimeSource)
            val slow = BurstyRateLimiter(1, 1.seconds, testTimeSource)
            val limiter = CompositeRateLimiter(fast, slow)

            // Exhaust only the slow limiter so composite tryAcquire must deny.
            slow.acquire()

            val denied = limiter.tryAcquire()
            assertIs<Permit.Denied>(denied)

            // If composite tryAcquire is atomic, fast limiter should still have both permits.
            assertEquals(Permit.Granted, fast.tryAcquire())
            assertEquals(Permit.Granted, fast.tryAcquire())
            assertIs<Permit.Denied>(fast.tryAcquire())
        }

    @Test
    fun `acquire cancellation does not consume permits from already-acquired limiters`() =
        runTest {
            val fast = BurstyRateLimiter(2, 1.seconds, testTimeSource)
            val slow = BurstyRateLimiter(1, 1.seconds, testTimeSource)
            val limiter = CompositeRateLimiter(fast, slow)

            // Exhaust slow so composite acquire can consume fast then suspend on slow.
            slow.acquire()

            val job = launch { limiter.acquire() }
            runCurrent()
            job.cancel()
            runCurrent()

            // If acquire rollback is atomic, fast limiter should still have both permits.
            assertEquals(Permit.Granted, fast.tryAcquire())
            assertEquals(Permit.Granted, fast.tryAcquire())
            assertIs<Permit.Denied>(fast.tryAcquire())
        }

    @Test
    fun `both limiters consume permits on acquire`() =
        runTest {
            val a = BurstyRateLimiter(2, 1.seconds, testTimeSource)
            val b = BurstyRateLimiter(2, 1.seconds, testTimeSource)
            val limiter = CompositeRateLimiter(a, b)

            limiter.acquire()

            // both should have consumed 1 permit, leaving 1 each
            assertEquals(Permit.Granted, a.tryAcquire())
            assertEquals(Permit.Granted, b.tryAcquire())

            // now both are exhausted
            assertIs<Permit.Denied>(a.tryAcquire())
            assertIs<Permit.Denied>(b.tryAcquire())
        }

    // Regression test for a pre-existing bug exposed by the migration review:
    // CompositeRateLimiter.collectRetryAfterFromRemaining used to probe delegates
    // via tryAcquire + refund, which over-cools WarmingSmoothLimiter because a
    // granted tryAcquire leaves heat unchanged but refund always decrements it.
    // After the fix, the probe uses the internal peekWait path and the warming
    // delegate's state must be untouched by a composite denial. We compare the
    // post-probe delay sequence on the warming limiter against a baseline
    // sequence recorded in an independent virtual-time scope with no probe.
    @Test
    fun `tryAcquire denial does not mutate warming smooth delegate`() {
        fun snapshotDelays(runProbe: Boolean): List<Long> {
            val captured = mutableListOf<Long>()
            runTest {
                val warm =
                    SmoothRateLimiter(
                        permits = 5,
                        per = 1.seconds,
                        warmup = 2.seconds,
                        timeSource = testTimeSource,
                    )

                // Warm up to heat > 0 and advance into a partially-cooled state
                // where a stored permit is available. This is the only regime
                // where the old tryAcquire + refund probe would have visibly
                // over-cooled the limiter — heat > 0 before the probe, with a
                // grantable permit at probe time so the refund decrement lands
                // on non-zero heat.
                repeat(3) { warm.acquire() }
                advanceTimeBy(680.milliseconds)

                if (runProbe) {
                    val bursty = BurstyRateLimiter(1, 1.seconds, testTimeSource)
                    bursty.acquire() // exhaust
                    val composite = CompositeRateLimiter(bursty, warm)
                    val denied = composite.tryAcquire()
                    assertIs<Permit.Denied>(denied)
                }

                captured += recordDelays(warm, 4)
            }
            return captured
        }

        val baseline = snapshotDelays(runProbe = false)
        val probed = snapshotDelays(runProbe = true)

        assertEquals(
            baseline,
            probed,
            "composite tryAcquire probe must not mutate warming smooth delegate state",
        )
    }

    @Test
    fun `acquire rollback works with warming smooth delegate`() =
        runTest {
            val warm =
                SmoothRateLimiter(
                    permits = 5,
                    per = 1.seconds,
                    warmup = 2.seconds,
                    timeSource = testTimeSource,
                )
            // Warm up so the rollback has non-trivial heat to handle, then
            // advance time so warm has a stored permit at composite-acquire
            // time. With the reserve path, warm.reserve runs before the
            // composite suspends and captures a pre-state snapshot; on
            // cancellation, WarmingReservation.cancel uses that snapshot for
            // an exact fast-path rewind of nextPermitAt and heat. The time
            // advance keeps warm's reserve wait short enough that the test
            // doesn't depend on the slow delegate's wait dominating, and
            // ensures heat is non-zero at reserve time so the rewind has
            // something meaningful to restore.
            repeat(3) { warm.acquire() }
            advanceTimeBy(1.seconds)

            val slow = BurstyRateLimiter(1, 10.seconds, testTimeSource)
            slow.acquire() // exhaust

            val composite = CompositeRateLimiter(warm, slow)

            val job = launch { composite.acquire() }
            runCurrent()
            job.cancel()
            runCurrent()

            // The rollback must have refunded the warm delegate, restoring a
            // stored permit: the next direct acquire on warm should be
            // immediate. If rollback didn't run, warm would still have
            // consumed the permit and the first delay would reflect a
            // borrowed-permit cold-ramp wait.
            val delays = recordDelays(warm, 3)
            assertEquals(
                0L,
                delays[0],
                "rollback should credit back a stored permit, so the next acquire is immediate",
            )
        }

    @Test
    fun `acquire reserves all delegates synchronously before suspending`() =
        runTest {
            // Both delegates are capacity 1 / interval 1s. After the first
            // composite.acquire(), both delegates' nextPermitAt sit at t=0
            // (committed but no refill yet). The second composite.acquire()
            // must reserve BOTH delegates before it suspends — and equal
            // mid-wait retryAfters from a direct tryAcquire on each delegate
            // are the proof. Under the old sequential acquire-with-rollback,
            // only the first delegate would have advanced before suspending,
            // and a mid-wait tryAcquire on the second would compute a
            // strictly smaller retryAfter than the first.
            //
            // Note: total wall time isn't a useful discriminator here. With
            // virtual time, sequential A-then-B and reserve-all max(A,B)
            // both add up to max(wait_A, wait_B) for a single caller — wait
            // elapsed on A counts as refill toward B. Mid-wait state is
            // what differs.
            val a = BurstyRateLimiter(1, 1.seconds, testTimeSource)
            val b = BurstyRateLimiter(1, 1.seconds, testTimeSource)
            val limiter = CompositeRateLimiter(a, b)
            limiter.acquire() // exhaust at t=0; both nextPermitAt = 0

            val job = launch { limiter.acquire() }
            runCurrent()
            assertFalse(job.isCompleted, "composite must be suspended in delay()")

            val aDenied = a.tryAcquire()
            val bDenied = b.tryAcquire()
            assertIs<Permit.Denied>(aDenied)
            assertIs<Permit.Denied>(bDenied)
            assertEquals(
                aDenied.retryAfter,
                bDenied.retryAfter,
                "both delegates should be reserved before composite suspends; " +
                    "unequal retryAfters indicate sequential acquire-with-rollback",
            )

            job.cancel()
        }

    @Test
    fun `acquire supports permits greater than delegate capacity via borrow-from-future`() =
        runTest {
            // slow's capacity is 2, but composite.acquire(5) must still work
            // by reserving future production. A retry loop based on tryAcquire
            // would livelock here because slow.tryAcquire(5) can never grant
            // (stored credit is capped at capacity * interval = 1s = 2 permits).
            val fast = BurstyRateLimiter(5, 1.seconds, testTimeSource) // interval 200ms
            val slow = BurstyRateLimiter(2, 1.seconds, testTimeSource) // interval 500ms
            val limiter = CompositeRateLimiter(fast, slow)

            val before = currentTime
            limiter.acquire(5)
            // slow has 2 stored permits; needs to wait for 3 more at 500ms each = 1500ms.
            // fast can serve all 5 immediately. Composite delays max(0, 1500ms).
            assertEquals(1500L, currentTime - before)
        }

    @Test
    fun `cancellation during reservation wait rolls back all delegates`() =
        runTest {
            val fast = BurstyRateLimiter(2, 1.seconds, testTimeSource)
            val slow = BurstyRateLimiter(1, 10.seconds, testTimeSource)
            val limiter = CompositeRateLimiter(fast, slow)

            slow.acquire() // exhaust slow

            val job = launch { limiter.acquire() }
            runCurrent()
            assertFalse(job.isCompleted, "composite must be waiting for slow's refill")

            job.cancel()
            runCurrent()

            // fast: rolled back to its pre-reservation state. Both permits available.
            assertEquals(Permit.Granted, fast.tryAcquire())
            assertEquals(Permit.Granted, fast.tryAcquire())
            assertIs<Permit.Denied>(fast.tryAcquire())

            // slow: rolled back to its post-direct-acquire state (still exhausted).
            assertIs<Permit.Denied>(slow.tryAcquire())
        }

    @Test
    fun `layered limits work correctly`() =
        runTest {
            val perSecond = BurstyRateLimiter(5, 1.seconds, testTimeSource)
            val perMinute = BurstyRateLimiter(20, 10.minutes, testTimeSource)
            val limiter = CompositeRateLimiter(perSecond, perMinute)

            // short burst of 5 should work
            val before = currentTime
            repeat(5) { limiter.acquire() }
            assertEquals(0L, currentTime - before)

            // next acquire waits for per-second refill
            limiter.acquire()
            assertEquals(200L, currentTime - before)

            // keep acquiring up to 20 total over ~3 seconds
            repeat(14) { limiter.acquire() }

            // now per-minute limit is exhausted (20 total), even though per-second keeps refilling
            val job = launch { limiter.acquire() }
            advanceTimeBy(1.seconds)
            runCurrent()
            assertFalse(job.isCompleted, "per-minute limit should block even though per-second has capacity")
        }
}
