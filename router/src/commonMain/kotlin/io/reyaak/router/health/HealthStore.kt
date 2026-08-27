package io.reyaak.router.health

import io.reyaak.router.error.ErrorClass
import io.reyaak.router.error.msUntilNextUtcMidnight
import io.reyaak.router.score.TIMEOUT_LATENCY_CAP_MS
import io.reyaak.router.time.epochMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * What has been observed about one routable target.
 *
 * Counts are decay-weighted rather than raw, so a provider that was broken last
 * week does not stay demoted forever and one that broke an hour ago is not
 * excused by a long good history.
 */
data class HealthSnapshot(
    val successes: Double = 0.0,
    val failures: Double = 0.0,
    /** Exponentially-weighted output throughput, tokens per second. */
    val tokensPerSecond: Double = 0.0,
    /** Exponentially-weighted time to first byte. Null until measured. */
    val ttfbMs: Double? = null,
    /** Wall-clock instant this target becomes usable again. */
    val cooldownUntilMs: Long = 0L,
    val lastErrorClass: ErrorClass? = null,
    val lastErrorMessage: String? = null,
    /** 429 penalty, 0..MAX_PENALTY, feeding the rate-limit guardrail. */
    val penalty: Double = 0.0,
    val lastUpdatedMs: Long = 0L,
    /** Failure timestamps inside the breaker window. */
    val recentFailures: List<Long> = emptyList(),
) {
    fun benched(nowMs: Long) = nowMs < cooldownUntilMs

    fun cooldownRemainingMs(nowMs: Long) = max(0L, cooldownUntilMs - nowMs)
}

/** Somewhere durable to keep observations across process death. */
interface HealthPersistence {
    suspend fun load(): Map<String, HealthSnapshot>
    suspend fun save(key: String, snapshot: HealthSnapshot)

    object None : HealthPersistence {
        override suspend fun load(): Map<String, HealthSnapshot> = emptyMap()
        override suspend fun save(key: String, snapshot: HealthSnapshot) = Unit
    }
}

/**
 * Passive health tracking.
 *
 * There is deliberately no background prober. freellmapi checks every key every
 * five minutes, which on a server is free and on a phone is a recurring radio
 * wake that produces no user-visible benefit. Everything here is learned from
 * requests the agent was going to make anyway; a benched target is re-probed
 * lazily, by simply being tried again once its cooldown expires.
 *
 * Reads suspend for the same reason as [io.reyaak.router.limit.RateLimiter]:
 * common Kotlin has no concurrent map, so the state is guarded by a coroutine
 * mutex rather than a JVM monitor.
 */
class HealthStore(
    private val persistence: HealthPersistence = HealthPersistence.None,
    private val now: () -> Long = ::epochMillis,
) {
    private val snapshots = mutableMapOf<String, HealthSnapshot>()
    private val lock = Mutex()

    suspend fun warmUp() {
        val loaded = persistence.load()
        lock.withLock { snapshots.putAll(loaded) }
    }

    suspend fun snapshot(key: String): HealthSnapshot {
        val nowMs = now()
        return lock.withLock { decayed(snapshots[key] ?: HealthSnapshot(), nowMs) }
    }

    suspend fun all(): Map<String, HealthSnapshot> {
        val nowMs = now()
        return lock.withLock {
            snapshots.mapValues { (_, snapshot) -> decayed(snapshot, nowMs) }
        }
    }

    suspend fun recordSuccess(
        key: String,
        latencyMs: Long,
        ttfbMs: Long?,
        outputTokens: Int,
    ) {
        val nowMs = now()
        val updated = lock.withLock {
            mutate(key, nowMs) { base ->
                val tokPerSec = if (latencyMs > 0 && outputTokens > 0) {
                    outputTokens * 1000.0 / latencyMs
                } else {
                    null
                }
                base.copy(
                    successes = base.successes + 1.0,
                    tokensPerSecond = ewma(base.tokensPerSecond.takeIf { it > 0 }, tokPerSec),
                    ttfbMs = ewmaOrNull(base.ttfbMs, ttfbMs?.toDouble()),
                    // A success clears the bench and relaxes the penalty rather
                    // than zeroing it: one good call is evidence, not proof.
                    cooldownUntilMs = 0L,
                    penalty = (base.penalty - 1.0).coerceAtLeast(0.0),
                    lastErrorClass = null,
                    lastErrorMessage = null,
                    recentFailures = emptyList(),
                    lastUpdatedMs = nowMs,
                )
            }
        }
        persistence.save(key, updated)
    }

    /**
     * Record a failure and decide the bench.
     *
     * A stated retry delay always wins over the heuristic ladder: the provider
     * knows when its window resets and guessing shorter just earns another 429.
     */
    suspend fun recordFailure(
        key: String,
        errorClass: ErrorClass,
        message: String?,
        statedRetryAfterMs: Long?,
    ) {
        val nowMs = now()
        val updated = lock.withLock {
            mutate(key, nowMs) { base ->
                failed(base, errorClass, message, statedRetryAfterMs, nowMs)
            }
        }
        persistence.save(key, updated)
    }

    /**
     * A timeout IS the model being slow, so it also feeds the speed axis: its
     * wall-clock latency with zero output tokens. Without this a model that
     * hangs on half its calls keeps a pristine speed score.
     *
     * Both effects apply under one lock. Calling [recordFailure] from here would
     * deadlock: a coroutine [Mutex] is not reentrant, unlike the JVM monitor
     * this used to hold.
     */
    suspend fun recordTimeout(key: String, elapsedMs: Long, message: String?) {
        val capped = elapsedMs.coerceAtMost(TIMEOUT_LATENCY_CAP_MS)
        val nowMs = now()
        val updated = lock.withLock {
            mutate(key, nowMs) { base ->
                val slowed = base.copy(
                    tokensPerSecond = ewma(base.tokensPerSecond.takeIf { it > 0 }, 0.0),
                    ttfbMs = ewmaOrNull(base.ttfbMs, capped.toDouble()),
                    lastUpdatedMs = nowMs,
                )
                failed(slowed, ErrorClass.TRANSIENT, message, null, nowMs)
            }
        }
        persistence.save(key, updated)
    }

    /** Clear a bench, for a user-initiated retry. */
    suspend fun clearCooldown(key: String) {
        val updated = lock.withLock {
            val base = snapshots[key] ?: HealthSnapshot()
            base.copy(cooldownUntilMs = 0L, recentFailures = emptyList())
                .also { snapshots[key] = it }
        }
        persistence.save(key, updated)
    }

    /** Clear every bench, for a user-initiated router restart. */
    suspend fun clearAllCooldowns() {
        val updated = lock.withLock {
            snapshots.keys.toList().map { key ->
                val cleared = snapshots.getValue(key)
                    .copy(cooldownUntilMs = 0L, recentFailures = emptyList())
                snapshots[key] = cleared
                key to cleared
            }
        }
        updated.forEach { (key, snapshot) -> persistence.save(key, snapshot) }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /** Read-decay-write. The caller must hold [lock]. */
    private fun mutate(
        key: String,
        nowMs: Long,
        block: (HealthSnapshot) -> HealthSnapshot,
    ): HealthSnapshot {
        val base = decayed(snapshots[key] ?: HealthSnapshot(), nowMs)
        return block(base).also { snapshots[key] = it }
    }

    /** The failure half of an update, pure so a timeout can reuse it. */
    private fun failed(
        base: HealthSnapshot,
        errorClass: ErrorClass,
        message: String?,
        statedRetryAfterMs: Long?,
        nowMs: Long,
    ): HealthSnapshot {
        val recent = (base.recentFailures + nowMs).filter { nowMs - it <= BREAKER_WINDOW_MS }

        val heuristic = when (errorClass) {
            ErrorClass.QUOTA_EXHAUSTED -> msUntilNextUtcMidnight(nowMs)
            ErrorClass.RATE_LIMITED -> RATE_LIMIT_COOLDOWN_MS
            ErrorClass.KEY_INVALID -> AUTH_FAILURE_COOLDOWN_MS
            ErrorClass.PAYMENT_REQUIRED -> msUntilNextUtcMidnight(nowMs)
            ErrorClass.MODEL_GONE -> MODEL_GONE_COOLDOWN_MS
            ErrorClass.MODEL_FORBIDDEN -> MODEL_GONE_COOLDOWN_MS
            // A breaker trip is the only thing that benches a merely flaky
            // target: one 500 should not take it out of rotation.
            else -> if (recent.size >= BREAKER_THRESHOLD) BREAKER_COOLDOWN_MS else 0L
        }
        val cooldown = statedRetryAfterMs ?: heuristic

        return base.copy(
            failures = base.failures + 1.0,
            cooldownUntilMs = if (cooldown > 0) nowMs + cooldown else base.cooldownUntilMs,
            lastErrorClass = errorClass,
            lastErrorMessage = message?.take(300),
            penalty = if (errorClass == ErrorClass.RATE_LIMITED ||
                errorClass == ErrorClass.QUOTA_EXHAUSTED
            ) {
                (base.penalty + 2.0).coerceAtMost(MAX_PENALTY_VALUE)
            } else {
                base.penalty
            },
            recentFailures = recent,
            lastUpdatedMs = nowMs,
        )
    }

    /**
     * Fade counts toward zero with a 24-hour half-life.
     *
     * Applied on read rather than on a timer, so there is nothing to schedule
     * and no wakeup: the decay is a function of elapsed time, and elapsed time
     * is already known whenever the value is needed.
     */
    private fun decayed(snapshot: HealthSnapshot, nowMs: Long): HealthSnapshot {
        if (snapshot.lastUpdatedMs == 0L) return snapshot
        val elapsed = nowMs - snapshot.lastUpdatedMs
        if (elapsed <= 0) return snapshot
        val factor = exp(-LN2 * elapsed / HALF_LIFE_MS)
        if (factor > 0.999) return snapshot
        return snapshot.copy(
            successes = snapshot.successes * factor,
            failures = snapshot.failures * factor,
            penalty = snapshot.penalty * factor,
            recentFailures = snapshot.recentFailures.filter { nowMs - it <= BREAKER_WINDOW_MS },
        )
    }

    /** Blend a new sample into a running average. No sample leaves it alone. */
    private fun ewma(current: Double?, sample: Double?): Double =
        when {
            sample == null -> current ?: 0.0
            current == null || current == 0.0 -> sample
            else -> EWMA_ALPHA * sample + (1 - EWMA_ALPHA) * current
        }

    /**
     * As [ewma], but keeps null meaning "never measured" rather than collapsing
     * it to zero: the speed axis treats an unmeasured TTFB and a zero TTFB
     * very differently.
     */
    private fun ewmaOrNull(current: Double?, sample: Double?): Double? =
        if (sample == null && current == null) null else ewma(current, sample)

    companion object {
        /** 3 failures inside 15 minutes trips the breaker for 10 minutes. */
        const val BREAKER_WINDOW_MS = 15 * 60 * 1000L
        const val BREAKER_THRESHOLD = 3
        const val BREAKER_COOLDOWN_MS = 10 * 60 * 1000L

        const val AUTH_FAILURE_COOLDOWN_MS = 5 * 60 * 1000L
        const val RATE_LIMIT_COOLDOWN_MS = 60 * 1000L
        const val MODEL_GONE_COOLDOWN_MS = 6 * 60 * 60 * 1000L

        const val MAX_PENALTY_VALUE = 10.0
        const val HALF_LIFE_MS = 24 * 60 * 60 * 1000.0
        const val EWMA_ALPHA = 0.3

        private val LN2 = ln(2.0)

        /** Health is tracked per provider, model, AND key: free tiers are per-key. */
        fun keyOf(platform: String, modelId: String, keyLabel: String) =
            "$platform|$modelId|$keyLabel"
    }
}
