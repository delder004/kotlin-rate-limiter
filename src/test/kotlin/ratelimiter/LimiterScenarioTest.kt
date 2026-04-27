package ratelimiter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Integration scenario walk-through for the limiter implementations using
// virtual test time. Each test exercises one scenario end-to-end and writes
// its observed behavior to build/limiter-scenarios.log — a human-readable
// narrative of the delay sequence, cancellation path, or composite
// interaction.
//
// Because virtual time is deterministic, the log is reproducible across runs
// and assertions are exact equality rather than bounded ranges. The log
// serves as a canonical reference for expected behavior; a regression in any
// section will both fail the assertion and show up as a diff in the log.
@OptIn(ExperimentalCoroutinesApi::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LimiterScenarioTest {
    companion object {
        private val log = File("build/limiter-scenarios.log")

        @JvmStatic
        @BeforeAll
        fun initLog() {
            log.parentFile?.mkdirs()
            log.writeText("Integration scenario walk-through — ${Instant.now()}\n")
        }
    }

    private fun section(title: String) {
        log.appendText("\n=== $title ===\n")
    }

    private fun line(s: String) {
        log.appendText("$s\n")
    }

    @Test
    @Order(1)
    fun `bursty — burst then throttle`() =
        runTest {
            section("Bursty(5 permits / 1s) — burst then throttle")
            val limiter = BurstyRateLimiter(permits = 5, per = 1.seconds, timeSource = testTimeSource)

            val timings = mutableListOf<Long>()
            repeat(7) { i ->
                val before = currentTime
                limiter.acquire()
                val elapsed = currentTime - before
                timings.add(elapsed)
                line("acquire #${i + 1}: ${elapsed}ms")
            }

            // First 5 immediate (fresh bucket holds 5), next 2 paced at
            // per/permits = 200ms.
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 200L, 200L), timings)
        }

    @Test
    @Order(2)
    fun `bursty — tryAcquire and cancellation refund`() =
        runTest {
            section("Bursty(1 permit / 1s) — tryAcquire + cancellation refund")
            val limiter = BurstyRateLimiter(permits = 1, per = 1.seconds, timeSource = testTimeSource)

            limiter.acquire()
            line("drained initial permit at t=${currentTime}ms")

            val immediate = limiter.tryAcquire()
            assertIs<Permit.Denied>(immediate)
            line("tryAcquire right after drain: $immediate")
            assertEquals(1000.milliseconds, immediate.retryAfter)

            val job = launch { limiter.acquire() }
            runCurrent() // let the launched acquire reach its delay
            advanceTimeBy(100.milliseconds)
            job.cancel()
            runCurrent() // let the cancel handler run refund()

            val afterCancel = limiter.tryAcquire()
            assertIs<Permit.Denied>(afterCancel)
            line("tryAcquire after cancel at t=${currentTime}ms: $afterCancel")
            // After refund, retryAfter should reflect only the remaining
            // interval relative to the original drain: 1000 - 100 = 900ms.
            // Broken refund would report ~1900ms (the cancelled reservation
            // would have persisted).
            assertEquals(900.milliseconds, afterCancel.retryAfter)
        }

    @Test
    @Order(3)
    fun `smooth no warmup — steady pacing`() =
        runTest {
            section("Smooth(10 permits / 1s, no warmup)")
            val limiter = SmoothRateLimiter(permits = 10, per = 1.seconds, timeSource = testTimeSource)

            val timings = mutableListOf<Long>()
            repeat(6) { i ->
                val before = currentTime
                limiter.acquire()
                val elapsed = currentTime - before
                timings.add(elapsed)
                line("acquire #${i + 1}: ${elapsed}ms")
            }

            // First acquire immediate (stored permit), subsequent paced at
            // per/permits = 100ms via FixedIntervalLimiter(capacity=1).
            assertEquals(listOf(0L, 100L, 100L, 100L, 100L, 100L), timings)
        }

    @Test
    @Order(4)
    fun `smooth with warmup — cold to stable ramp`() =
        runTest {
            section("Smooth(10 permits / 1s, warmup=1s) — ramp from cold to stable")
            // stable=100ms, cold=300ms (3x), warmup=1s, maxHeat=5, cooldown=200ms.
            val limiter =
                SmoothRateLimiter(
                    permits = 10,
                    per = 1.seconds,
                    warmup = 1.seconds,
                    timeSource = testTimeSource,
                )

            val timings = mutableListOf<Long>()
            repeat(15) { i ->
                val before = currentTime
                limiter.acquire()
                val elapsed = currentTime - before
                timings.add(elapsed)
                line("acquire #${i + 1}: ${elapsed}ms")
            }

            // Hand-traced: first acquire consumes the stored permit (wait=0).
            // Subsequent acquires borrow one permit each, advancing heat from
            // 0 to saturation (maxHeat=5) while the per-permit interval linearly
            // interpolates from cold (300ms) to stable (100ms). Heat saturates
            // by acquire 7, after which every acquire waits exactly stable.
            val expected =
                listOf(
                    0L,
                    300L,
                    260L,
                    220L,
                    180L,
                    140L,
                    100L,
                    100L,
                    100L,
                    100L,
                    100L,
                    100L,
                    100L,
                    100L,
                    100L,
                )
            assertEquals(expected, timings)
        }

    @Test
    @Order(5)
    fun `composite — smooth dominates bursty`() =
        runTest {
            section("Composite(Bursty(5/s), Smooth(2/s)) — smooth dominates")
            val limiter =
                CompositeRateLimiter(
                    BurstyRateLimiter(permits = 5, per = 1.seconds, timeSource = testTimeSource),
                    SmoothRateLimiter(permits = 2, per = 1.seconds, timeSource = testTimeSource),
                )

            val timings = mutableListOf<Long>()
            repeat(5) { i ->
                val before = currentTime
                limiter.acquire()
                val elapsed = currentTime - before
                timings.add(elapsed)
                line("acquire #${i + 1}: ${elapsed}ms")
            }

            // First acquire: both sub-limiters hold a stored permit, immediate.
            // Subsequent: bursty's interval (200ms) is faster than smooth's
            // (500ms), so bursty always grants immediately and the composite
            // wait is dominated by smooth's sequential delay.
            assertEquals(listOf(0L, 500L, 500L, 500L, 500L), timings)
        }

    @Test
    @Order(6)
    fun `composite — peek path is state-neutral`() =
        runTest {
            section("Composite(exhaustedBursty, warmingSmooth) — peek path state-neutral")

            // Warming smooth: stable=200ms, warmup=2s, cold=600ms, maxHeat=5.
            val warm =
                SmoothRateLimiter(
                    permits = 5,
                    per = 1.seconds,
                    warmup = 2.seconds,
                    timeSource = testTimeSource,
                )
            val bursty = BurstyRateLimiter(permits = 1, per = 10.seconds, timeSource = testTimeSource)

            // Three back-to-back acquires drive heat to 2. Last completes at
            // t = 0 + 600 + 520 = 1120ms.
            repeat(3) { warm.acquire() }
            line("warmed smooth with 3 acquires, at t=${currentTime}ms heat=2 nextPermitAt=1120")

            bursty.acquire()
            line("drained bursty at t=${currentTime}ms")

            // Natural cooling: heat 2 -> 0.25 over 700ms at cooldown=400ms.
            advanceTimeBy(700.milliseconds)
            line("advanced 700ms to t=${currentTime}ms (natural cooling: heat 2 -> 0.25)")

            val composite = CompositeRateLimiter(bursty, warm)

            val denied = composite.tryAcquire()
            assertIs<Permit.Denied>(denied)
            line("composite.tryAcquire: $denied")
            // Bursty's retryAfter = 10000ms - (t=1820 - drain@1120) = 9300ms.
            // Warm's peekWait returns 0 (grant, stored permit available), so
            // composite returns max(9300, 0) = 9300ms.
            assertEquals(9300.milliseconds, denied.retryAfter)

            // Peek-path state-neutrality: warm is unchanged by the composite
            // probe. A direct tryAcquire sees the stored permit and grants.
            // Pre-fix, warm would have had its heat over-cooled by the
            // tryAcquire + refund probe, and this tryAcquire would deny.
            val afterProbe = warm.tryAcquire()
            line("warm.tryAcquire after composite probe: $afterProbe")
            assertEquals(Permit.Granted, afterProbe)
        }

    @Test
    @Order(7)
    fun `composite — warming rollback on cancellation`() =
        runTest {
            section("Composite(warmingSmooth, exhaustedSlow) — acquire rollback on cancel")

            val warm =
                SmoothRateLimiter(
                    permits = 5,
                    per = 1.seconds,
                    warmup = 2.seconds,
                    timeSource = testTimeSource,
                )
            val slow = BurstyRateLimiter(permits = 1, per = 10.seconds, timeSource = testTimeSource)

            repeat(3) { warm.acquire() }
            slow.acquire()
            line("warmed smooth + drained slow at t=${currentTime}ms")

            // Advance past warm's debt so composite.acquire() grants warm,
            // adds it to `acquired`, and then blocks on slow. Without this
            // advance, cancellation would land in warm's own catch path
            // instead of composite's rollback.
            advanceTimeBy(1000.milliseconds)
            line("advanced 1000ms to t=${currentTime}ms (warm has a stored permit)")

            val composite = CompositeRateLimiter(warm, slow)

            val job = launch { composite.acquire() }
            runCurrent() // let composite grant warm and suspend on slow
            job.cancel()
            runCurrent() // let cancel handler run composite's rollback

            // Rollback must have refunded warm: the next direct acquire is
            // immediate because the stored permit was credited back. Without
            // rollback, warm would still have consumed the permit and this
            // acquire would wait 600ms at cold interval.
            val before = currentTime
            warm.acquire()
            val rollbackDelay = currentTime - before
            line("warm.acquire after composite rollback: ${rollbackDelay}ms")
            assertEquals(0L, rollbackDelay)
        }
}
