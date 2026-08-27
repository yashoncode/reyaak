package io.reyaak.router.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Invariants of the routing score. These are the properties the design depends
 * on, so a change that breaks one is a change that breaks routing — not just a
 * failing number.
 */
class ScoringTest {

    private val eps = 1e-9

    // ── The property the whole model rests on ───────────────────────────────

    @Test
    fun `convex base stays in unit range and guardrails only ever reduce`() {
        val weights = BANDIT_PRESETS.getValue(RoutingStrategy.BALANCED)
        val rng = Random(1)
        repeat(2000) {
            val inputs = ScoreInputs(
                reliability = rng.nextDouble(),
                speed = rng.nextDouble(),
                intelligence = rng.nextDouble(),
                headroom = 1.0,
                rateLimit = 1.0,
            )
            val base = combineScore(inputs, weights)
            assertTrue("base escaped [0,1]: $base", base in -eps..(1.0 + eps))

            val damped = combineScore(
                inputs.copy(headroom = rng.nextDouble(), rateLimit = rng.nextDouble()),
                weights,
            )
            assertTrue("guardrail increased the score", damped <= base + eps)
        }
    }

    @Test
    fun `every preset is a normalized weight vector`() {
        for ((strategy, w) in BANDIT_PRESETS) {
            val sum = w.reliability + w.speed + w.intelligence
            assertEquals("$strategy weights must sum to 1", 1.0, sum, 1e-12)
            assertTrue("$strategy has a negative weight", w.reliability >= 0 && w.speed >= 0 && w.intelligence >= 0)
        }
    }

    @Test
    fun `non-normalized weights are renormalized rather than escaping the range`() {
        val inputs = ScoreInputs(1.0, 1.0, 1.0, 1.0, 1.0)
        val score = combineScore(inputs, RoutingWeights(5.0, 5.0, 5.0))
        assertEquals(1.0, score, eps)
    }

    // ── Reliability ────────────────────────────────────────────────────────

    @Test
    fun `unseen model sits at the uniform prior, not at zero`() {
        assertEquals(0.5, expectedReliability(0.0, 0.0), eps)
    }

    @Test
    fun `posterior mean moves with evidence but never reaches the extremes`() {
        val good = expectedReliability(99.0, 0.0)
        val bad = expectedReliability(0.0, 99.0)
        assertTrue(good > 0.98 && good < 1.0)
        assertTrue(bad < 0.02 && bad > 0.0)
    }

    @Test
    fun `beta sampler stays in unit range and converges on the posterior mean`() {
        val rng = Random(42)
        var sum = 0.0
        val n = 20_000
        repeat(n) {
            val draw = sampleBeta(8.0, 2.0, rng)
            assertTrue("draw out of range: $draw", draw in 0.0..1.0)
            sum += draw
        }
        // Beta(8,2) has mean 0.8. Plenty of slack for sampling noise.
        assertEquals(0.8, sum / n, 0.01)
    }

    @Test
    fun `thompson sampling still explores a model with a couple of failures`() {
        // The point of sampling the posterior rather than its mean: two early
        // failures must demote a model, not freeze it out permanently.
        val rng = Random(7)
        var proven = 0
        val trials = 5000
        repeat(trials) {
            val establishedGood = sampleReliability(successes = 40.0, failures = 2.0, rng = rng)
            val unluckyNewcomer = sampleReliability(successes = 0.0, failures = 2.0, rng = rng)
            if (establishedGood > unluckyNewcomer) proven++
        }
        val winRate = proven.toDouble() / trials
        assertTrue("established model should usually win, got $winRate", winRate > 0.90)
        assertTrue("newcomer must still win sometimes (exploration), got $winRate", winRate < 1.0)
    }

    // ── Speed ──────────────────────────────────────────────────────────────

    @Test
    fun `unmeasured speed returns the exploration prior`() {
        assertEquals(SPEED_PRIOR, speedScore(tokPerSec = 0.0, ttfbMs = null), eps)
    }

    @Test
    fun `throughput saturates so one very fast model cannot flatten the axis`() {
        val fast = speedScore(300.0, TTFB_BEST_MS)
        val decent = speedScore(60.0, TTFB_BEST_MS)
        assertTrue(fast > decent)
        // Saturation: 5x the throughput must not be anywhere near 5x the score.
        assertTrue("throughput is not saturating", fast < decent * 1.7)
    }

    @Test
    fun `ttfb ramp hits its documented endpoints`() {
        // Pure-TTFB path (no throughput samples).
        assertEquals(1.0, speedScore(0.0, TTFB_BEST_MS), eps)
        assertEquals(0.0, speedScore(0.0, TTFB_WORST_MS), eps)
        assertEquals(0.5, speedScore(0.0, (TTFB_BEST_MS + TTFB_WORST_MS) / 2.0), eps)
    }

    @Test
    fun `a timed-out attempt scores worse than a slow but successful one`() {
        val slowSuccess = speedScore(tokPerSec = 5.0, ttfbMs = 4000.0)
        // A timeout is recorded as its latency with zero output tokens.
        val timeout = speedScore(tokPerSec = 0.0, ttfbMs = TIMEOUT_LATENCY_CAP_MS.toDouble())
        assertTrue("timeout must not score better than a slow success", timeout < slowSuccess)
    }

    // ── Intelligence ───────────────────────────────────────────────────────

    @Test
    fun `tier strictly dominates rank across the whole rank range`() {
        // This is the invariant that justifies the sqrt compression: the WORST
        // rank inside a tier must still beat the BEST rank of the tier below.
        val worstInTier = intelligenceComposite("Large", intelligenceRank = 1000)
        val bestInLowerTier = intelligenceComposite("Medium", intelligenceRank = 1)
        assertTrue(
            "tier dominance broken: $worstInTier <= $bestInLowerTier",
            worstInTier > bestInLowerTier,
        )
    }

    @Test
    fun `rank edits near the top of the range move the axis visibly`() {
        val one = intelligenceComposite("Large", 1)
        val three = intelligenceComposite("Large", 3)
        val ten = intelligenceComposite("Large", 10)
        assertTrue(one > three && three > ten)
        // sqrt compression: the 1-to-3 gap must be a real, non-negligible move.
        assertTrue("rank edit is invisible", one - three > 10.0)
    }

    @Test
    fun `unknown tier is the floor of the axis, not an exclusion`() {
        assertEquals(0, tierValue("nonsense-label"))
        val unknown = intelligenceComposite("nonsense-label", 1)
        assertTrue(unknown < intelligenceComposite("Small", 1000))
        // Still a finite score, so such a model remains routable.
        assertTrue(unknown.isFinite())
    }

    @Test
    fun `all-equal candidates normalize to neutral-high rather than dividing by zero`() {
        assertEquals(1.0, intelligenceScore(composite = 500.0, min = 500.0, max = 500.0), eps)
    }

    // ── Guardrails ─────────────────────────────────────────────────────────

    @Test
    fun `headroom is flat while comfortable then ramps to the floor`() {
        // Unknown budget: no opinion.
        assertEquals(1.0, headroomFactor(usedTokens = 500.0, budgetTokens = 0.0), eps)
        // 50% remaining is above the 20% ramp start.
        assertEquals(1.0, headroomFactor(usedTokens = 50.0, budgetTokens = 100.0), eps)
        // Exactly at the ramp start.
        assertEquals(1.0, headroomFactor(usedTokens = 80.0, budgetTokens = 100.0), eps)
        // Fully exhausted lands on the floor.
        assertEquals(HEADROOM_FLOOR, headroomFactor(usedTokens = 100.0, budgetTokens = 100.0), eps)
        // Halfway down the ramp.
        val half = headroomFactor(usedTokens = 90.0, budgetTokens = 100.0)
        assertTrue(half > HEADROOM_FLOOR && half < 1.0)
    }

    @Test
    fun `headroom is monotonic in remaining quota`() {
        var previous = 0.0
        for (used in 100 downTo 0) {
            val factor = headroomFactor(used.toDouble(), 100.0)
            assertTrue("headroom must not decrease as quota frees up", factor >= previous - eps)
            previous = factor
        }
    }

    @Test
    fun `rate window guardrail demotes before exhaustion`() {
        assertEquals(1.0, rateWindowHeadroomFactor(null), eps)
        assertEquals(1.0, rateWindowHeadroomFactor(0.0), eps)
        // The reporting case: 82% utilized should already be demoted.
        val at82 = rateWindowHeadroomFactor(0.82)
        assertTrue("82% utilization should be demoted, got $at82", at82 < 1.0)
        assertEquals(HEADROOM_FLOOR, rateWindowHeadroomFactor(1.0), eps)
    }

    @Test
    fun `operator thresholds outside the unit range fall back to defaults`() {
        val bogus = HeadroomThresholds(rampStart = 42.0, floor = -3.0)
        assertEquals(
            headroomFactor(100.0, 100.0),
            headroomFactor(100.0, 100.0, bogus),
            eps,
        )
    }

    @Test
    fun `rate limit penalty demotes hard but never excludes`() {
        assertEquals(1.0, rateLimitFactor(0.0), eps)
        assertEquals(1.0 - RATE_LIMIT_MAX_DAMP, rateLimitFactor(MAX_PENALTY), eps)
        // Out-of-range penalties clamp rather than going negative.
        assertEquals(1.0 - RATE_LIMIT_MAX_DAMP, rateLimitFactor(999.0), eps)
        assertEquals(1.0, rateLimitFactor(-5.0), eps)
        assertTrue("a maxed-out candidate must stay routable", rateLimitFactor(MAX_PENALTY) > 0.0)
    }

    // ── Preset behaviour ───────────────────────────────────────────────────

    @Test
    fun `fastest and smartest disagree about a fast dumb model versus a slow smart one`() {
        val fastDumb = ScoreInputs(reliability = 0.8, speed = 0.95, intelligence = 0.1, headroom = 1.0, rateLimit = 1.0)
        val slowSmart = ScoreInputs(reliability = 0.8, speed = 0.15, intelligence = 0.95, headroom = 1.0, rateLimit = 1.0)

        val fastest = BANDIT_PRESETS.getValue(RoutingStrategy.FASTEST)
        val smartest = BANDIT_PRESETS.getValue(RoutingStrategy.SMARTEST)

        assertTrue(combineScore(fastDumb, fastest) > combineScore(slowSmart, fastest))
        assertTrue(combineScore(slowSmart, smartest) > combineScore(fastDumb, smartest))
    }

    @Test
    fun `reliable preset refuses a fast smart model that keeps failing`() {
        val brokenButBrilliant = ScoreInputs(reliability = 0.05, speed = 0.95, intelligence = 0.95, headroom = 1.0, rateLimit = 1.0)
        val dependableAndDull = ScoreInputs(reliability = 0.97, speed = 0.4, intelligence = 0.3, headroom = 1.0, rateLimit = 1.0)
        val reliable = BANDIT_PRESETS.getValue(RoutingStrategy.RELIABLE)
        assertTrue(combineScore(dependableAndDull, reliable) > combineScore(brokenButBrilliant, reliable))
    }

    @Test
    fun `a nearly exhausted top model yields to a healthy peer`() {
        val weights = BANDIT_PRESETS.getValue(RoutingStrategy.BALANCED)
        val best = ScoreInputs(0.9, 0.9, 0.9, headroom = 1.0, rateLimit = 1.0)
        val peer = ScoreInputs(0.75, 0.7, 0.7, headroom = 1.0, rateLimit = 1.0)
        assertTrue(combineScore(best, weights) > combineScore(peer, weights))

        val exhausted = best.copy(headroom = rateWindowHeadroomFactor(0.99))
        assertTrue(
            "an exhausted leader must yield",
            combineScore(peer, weights) > combineScore(exhausted, weights),
        )
    }
}
