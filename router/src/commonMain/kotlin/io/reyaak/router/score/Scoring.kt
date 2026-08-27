package io.reyaak.router.score

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Candidate scoring for model routing.
 *
 * Every signal is normalized to [0,1] and combined as a CONVEX COMBINATION, so
 * the weights stay interpretable and the base can never leave [0,1]:
 *
 *     base = w_rel*reliability + w_speed*speed + w_intel*intelligence
 *
 * Two always-on guardrails then MULTIPLY that base. They never reorder healthy
 * candidates against each other; they only pull one down as it becomes risky:
 *
 *     effective = base * headroom * rateLimit
 *
 * The alternative — summing hand-tuned bonuses drawn from different units (a
 * probability plus a raw millisecond term plus a capability term) — needs a
 * manual cap on every term just to keep orderings sane. This does not.
 *
 * Reliability is drawn from a Beta posterior (Thompson sampling), so
 * exploration is automatic and proportional to uncertainty: two early failures
 * demote a model without freezing it out forever.
 *
 * Design adapted from freellmapi (MIT) — see NOTICE.
 */

data class RoutingWeights(
    val reliability: Double,
    val speed: Double,
    val intelligence: Double,
)

enum class RoutingStrategy {
    /** Manual chain: take the declared order and skip scoring entirely. */
    PRIORITY,
    BALANCED,
    SMARTEST,
    FASTEST,
    RELIABLE,

    /** A user-tuned weight vector, carried alongside the strategy. */
    CUSTOM;

    /** Wire name, matching the freellmapi declarative-config vocabulary. */
    val wireName: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String?): RoutingStrategy? =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}

/**
 * How to choose BETWEEN several keys of one provider once a model is picked.
 *
 * Deliberately not folded into [RoutingStrategy]: model ranking and key
 * selection are independent choices, and merging them would mean switching key
 * policy also switches which model bandit runs.
 */
enum class KeySelectionStrategy {
    /** Per-key bandit score, falling back to round-robin. */
    AUTO,

    /** Additionally prefer the key with the most observed quota left. */
    LEAST_REMAINING;

    val wireName: String get() = if (this == LEAST_REMAINING) "least-remaining" else "auto"

    companion object {
        fun fromWire(value: String?): KeySelectionStrategy? = when (value?.trim()?.lowercase()) {
            "auto" -> AUTO
            "least-remaining" -> LEAST_REMAINING
            else -> null
        }
    }
}

/** Weight vectors per strategy. One engine, several presets. */
val BANDIT_PRESETS: Map<RoutingStrategy, RoutingWeights> = mapOf(
    // Reliability leads; speed and intelligence split the rest.
    RoutingStrategy.BALANCED to RoutingWeights(0.50, 0.25, 0.25),
    // Intelligence leads, but reliability still carries enough weight that a
    // smart model which keeps failing does not win.
    RoutingStrategy.SMARTEST to RoutingWeights(0.35, 0.10, 0.55),
    // Speed leads; reliability keeps a fast-but-broken model from winning.
    RoutingStrategy.FASTEST to RoutingWeights(0.35, 0.55, 0.10),
    // For callers that just want it to work.
    RoutingStrategy.RELIABLE to RoutingWeights(0.70, 0.15, 0.15),
)

val DEFAULT_STRATEGY: RoutingStrategy = RoutingStrategy.BALANCED

// ── Reliability ─────────────────────────────────────────────────────────────
// Beta(1,1) prior is uniform: an unseen model is genuinely uncertain, not
// assumed good and not assumed bad.

const val PRIOR_SUCCESS: Double = 1.0
const val PRIOR_FAILURE: Double = 1.0

data class BetaPosterior(val alpha: Double, val beta: Double)

fun reliabilityPosterior(successes: Double, failures: Double): BetaPosterior =
    BetaPosterior(
        alpha = successes.coerceAtLeast(0.0) + PRIOR_SUCCESS,
        beta = failures.coerceAtLeast(0.0) + PRIOR_FAILURE,
    )

/** Deterministic posterior mean. Use for display, never for routing. */
fun expectedReliability(successes: Double, failures: Double): Double {
    val (a, b) = reliabilityPosterior(successes, failures)
    return a / (a + b)
}

/** One Thompson draw. Use for routing, so exploration comes for free. */
fun sampleReliability(successes: Double, failures: Double, rng: Random): Double {
    val (a, b) = reliabilityPosterior(successes, failures)
    return sampleBeta(a, b, rng)
}

// ── Speed: throughput and TTFB blended into one axis ────────────────────────

/** tok/s at which the throughput term reaches ~0.63. */
const val SPEED_SCALE_TOK_S: Double = 60.0
const val TTFB_BEST_MS: Double = 300.0
const val TTFB_WORST_MS: Double = 5000.0
private const val THROUGHPUT_WEIGHT = 0.6
private const val TTFB_WEIGHT = 0.4

/** Optimistic, so an unmeasured model still gets explored on this axis. */
const val SPEED_PRIOR: Double = 0.6

/**
 * A timeout IS the model being slow, so it feeds this axis as its wall-clock
 * latency with zero output tokens. Capped, because a provider that holds a
 * socket open for twenty minutes would otherwise flatten the axis for every
 * model behind it. Two minutes sits above any normal per-attempt deadline, so
 * only genuine outliers are clipped.
 */
const val TIMEOUT_LATENCY_CAP_MS: Long = 120_000L

/** Saturating, so one very fast tiny model cannot make a fine larger one look broken. */
private fun throughputScore(tokPerSec: Double): Double =
    if (tokPerSec <= 0.0) 0.0 else 1.0 - exp(-tokPerSec / SPEED_SCALE_TOK_S)

private fun ttfbScore(ttfbMs: Double): Double = when {
    ttfbMs <= TTFB_BEST_MS -> 1.0
    ttfbMs >= TTFB_WORST_MS -> 0.0
    else -> 1.0 - (ttfbMs - TTFB_BEST_MS) / (TTFB_WORST_MS - TTFB_BEST_MS)
}

/**
 * `tokPerSec <= 0` means no successful samples; `ttfbMs == null` means no
 * first-byte timing. With neither, return the exploration prior rather than
 * guessing zero — an unmeasured model is not a slow one.
 */
fun speedScore(tokPerSec: Double, ttfbMs: Double?): Double {
    if (tokPerSec <= 0.0 && ttfbMs == null) return SPEED_PRIOR
    val tp = throughputScore(tokPerSec)
    if (ttfbMs == null) return tp
    if (tokPerSec <= 0.0) return ttfbScore(ttfbMs)
    return THROUGHPUT_WEIGHT * tp + TTFB_WEIGHT * ttfbScore(ttfbMs)
}

// ── Intelligence: tier dominates, rank differentiates within a tier ─────────

/**
 * Cross-provider capability tier. A rank seeded from one provider's own catalog
 * is not comparable across providers, which is why tier exists at all.
 */
val TIER_VALUE: Map<String, Int> = mapOf(
    "Frontier" to 4,
    "Large" to 3,
    "Medium" to 2,
    "Small" to 1,
)

/** An unrecognized label is the FLOOR of this axis, not an exclusion from routing. */
fun tierValue(sizeLabel: String): Int = TIER_VALUE[sizeLabel] ?: 0

private const val RANK_SCALE = 31.0

/**
 * Rank is 1..1000, 1 = best. The sqrt compression makes a rank edit visible on
 * the axis while tier keeps strict dominance: the worst in-tier rank
 * (sqrt(1000)*31 ~= 980 < 1000) still beats the best rank of the tier below.
 * A linear rank term would be dwarfed by tier*1000 and look like a no-op.
 */
fun intelligenceComposite(sizeLabel: String, intelligenceRank: Int): Double =
    tierValue(sizeLabel) * 1000.0 - sqrt(intelligenceRank.coerceAtLeast(1).toDouble()) * RANK_SCALE

/** Min-max normalize a composite against the candidate set. All-equal means neutral-high. */
fun intelligenceScore(composite: Double, min: Double, max: Double): Double =
    if (max <= min) 1.0 else (composite - min) / (max - min)

// ── Guardrails ──────────────────────────────────────────────────────────────

const val HEADROOM_FLOOR: Double = 0.1

/** Start protecting a candidate once only this fraction of its quota remains. */
const val HEADROOM_RAMP_START: Double = 0.2

data class HeadroomThresholds(val rampStart: Double? = null, val floor: Double? = null)

/**
 * The ramp both headroom guardrails ride: flat at 1.0 while `remaining` is
 * comfortable, then linear down to `floor` at exhaustion. Shared so the
 * monthly-budget and rate-window guardrails cannot drift apart.
 */
private fun headroomRamp(remainingRaw: Double, opts: HeadroomThresholds?): Double {
    val rampStart = clampUnit(opts?.rampStart, HEADROOM_RAMP_START)
    val floor = clampUnit(opts?.floor, HEADROOM_FLOOR)
    val remaining = remainingRaw.coerceIn(0.0, 1.0)
    if (remaining >= rampStart) return 1.0
    return floor + (1.0 - floor) * (remaining / rampStart)
}

/** Monthly token budget. Unknown budget means no opinion, not zero headroom. */
fun headroomFactor(usedTokens: Double, budgetTokens: Double, opts: HeadroomThresholds? = null): Double {
    if (budgetTokens <= 0.0) return 1.0
    return headroomRamp(1.0 - usedTokens / budgetTokens, opts)
}

/**
 * Live rate-window utilization (the common free-tier shape: rpm/tpm/rpd/tpd).
 * Without this, window limits are purely binary — a model stays ranked first
 * until the very request that exhausts it, then eats a 429. Recovery needs no
 * bookkeeping: the windows slide, so utilization falls on its own.
 *
 * A null `usedFraction` means the model declares no window limits, so no opinion.
 */
fun rateWindowHeadroomFactor(usedFraction: Double?, opts: HeadroomThresholds? = null): Double {
    if (usedFraction == null || !usedFraction.isFinite()) return 1.0
    return headroomRamp(1.0 - usedFraction, opts)
}

const val MAX_PENALTY: Double = 10.0
const val RATE_LIMIT_MAX_DAMP: Double = 0.6

/** At max penalty a candidate keeps 40% of its score: demoted hard, never excluded. */
fun rateLimitFactor(penalty: Double): Double {
    val p = penalty.coerceIn(0.0, MAX_PENALTY)
    return 1.0 - (p / MAX_PENALTY) * RATE_LIMIT_MAX_DAMP
}

/** Out-of-range input falls back to the default rather than silently clamping. */
private fun clampUnit(n: Double?, fallback: Double): Double =
    if (n == null || !n.isFinite() || n < 0.0 || n > 1.0) fallback else n

// ── The combined score ──────────────────────────────────────────────────────

data class ScoreInputs(
    /** [0,1] — sampled for routing, expected for display. */
    val reliability: Double,
    val speed: Double,
    val intelligence: Double,
    val headroom: Double,
    val rateLimit: Double,
)

/**
 * Convex base times the two guardrails. Weights are renormalized if a caller
 * passes a vector that does not sum to 1, so the base never escapes [0,1].
 */
fun combineScore(inputs: ScoreInputs, weights: RoutingWeights): Double {
    val wSum = (weights.reliability + weights.speed + weights.intelligence)
        .let { if (it == 0.0) 1.0 else it }
    val base = (
        weights.reliability * inputs.reliability +
            weights.speed * inputs.speed +
            weights.intelligence * inputs.intelligence
        ) / wSum
    return base * inputs.headroom * inputs.rateLimit
}

// ── Beta sampler (two Gamma draws, Marsaglia and Tsang) ─────────────────────

private const val EPS = 1e-12

private fun randomNormal(rng: Random): Double {
    val u1 = rng.nextDouble().coerceAtLeast(EPS)
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * rng.nextDouble())
}

private fun sampleGamma(shape: Double, rng: Random): Double {
    if (shape < 1.0) {
        return sampleGamma(shape + 1.0, rng) * rng.nextDouble().coerceAtLeast(EPS).pow(1.0 / shape)
    }
    val d = shape - 1.0 / 3.0
    val c = 1.0 / sqrt(9.0 * d)
    while (true) {
        var x: Double
        var v: Double
        do {
            x = randomNormal(rng)
            v = 1.0 + c * x
        } while (v <= 0.0)
        v = v * v * v
        val u = rng.nextDouble()
        if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
        if (ln(u.coerceAtLeast(EPS)) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
    }
}

fun sampleBeta(alpha: Double, beta: Double, rng: Random): Double {
    val x = sampleGamma(alpha, rng)
    val y = sampleGamma(beta, rng)
    val sum = x + y
    return if (sum > 0.0) x / sum else 0.5
}
