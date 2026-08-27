package io.reyaak.router.limit

import io.reyaak.router.catalog.ModelSpec
import io.reyaak.router.time.epochMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client-side rate limiting against declared free-tier windows.
 *
 * Two jobs, and the second is the interesting one:
 *
 *  1. Refuse locally instead of spending a round trip to earn a 429.
 *  2. Report how full a window is, so the headroom guardrail can DEMOTE a model
 *     before it is exhausted. Without that, window limits are purely binary: a
 *     model stays ranked first until the very request that exhausts it, then
 *     everything behind it inherits a hard failure.
 *
 * Sliding windows rather than fixed buckets, so recovery needs no bookkeeping:
 * utilization falls on its own as time passes, and there is nothing to reset.
 *
 * The methods suspend because the state is guarded by a coroutine [Mutex]
 * instead of a JVM monitor. Kotlin has no common `synchronized` and no
 * concurrent map, and the router is coroutine-driven end to end, so a mutex is
 * the mechanism that exists on every target without adding a dependency.
 */
class RateLimiter(private val now: () -> Long = ::epochMillis) {

    private data class Event(val atMs: Long, val tokens: Int)

    private val events = mutableMapOf<String, MutableList<Event>>()

    // ponytail: one lock for every bucket. Buckets are per (model, key) and hold
    // at most a day of timestamps, so the critical sections are microseconds and
    // contention is invisible next to a network round trip. Split it per bucket
    // only if profiling ever says otherwise.
    private val lock = Mutex()

    /**
     * Whether a request may proceed, and if not, roughly how long to wait.
     *
     * Returns null when it may proceed. A non-null value is the delay until the
     * binding window frees up, which the router can report rather than guess.
     */
    suspend fun retryAfterMs(spec: ModelSpec, keyLabel: String, estimatedTokens: Int): Long? {
        val nowMs = now()
        return lock.withLock {
            val bucket = bucketFor(spec, keyLabel)
            prune(bucket, nowMs)

            spec.rpmLimit?.let { limit ->
                val inWindow = bucket.count { nowMs - it.atMs < MINUTE_MS }
                if (inWindow >= limit) return@withLock waitFor(bucket, nowMs, MINUTE_MS)
            }
            spec.rpdLimit?.let { limit ->
                val inWindow = bucket.count { nowMs - it.atMs < DAY_MS }
                if (inWindow >= limit) return@withLock waitFor(bucket, nowMs, DAY_MS)
            }
            spec.tpmLimit?.let { limit ->
                val used = bucket.filter { nowMs - it.atMs < MINUTE_MS }.sumOf { it.tokens }
                if (used + estimatedTokens > limit) return@withLock waitFor(bucket, nowMs, MINUTE_MS)
            }
            spec.tpdLimit?.let { limit ->
                val used = bucket.filter { nowMs - it.atMs < DAY_MS }.sumOf { it.tokens }
                if (used + estimatedTokens > limit) return@withLock waitFor(bucket, nowMs, DAY_MS)
            }
            null
        }
    }

    suspend fun record(spec: ModelSpec, keyLabel: String, tokens: Int) {
        val nowMs = now()
        lock.withLock {
            val bucket = bucketFor(spec, keyLabel)
            prune(bucket, nowMs)
            bucket += Event(nowMs, tokens)
        }
    }

    /**
     * How full the *binding* window is, 0.0 (idle) to 1.0 (exhausted), or null
     * when this model declares no limits.
     *
     * The maximum across windows is the right answer, not the average: a model
     * at 5% of its daily cap but 95% of its per-minute cap is about to fail, and
     * averaging would hide that.
     */
    suspend fun utilization(spec: ModelSpec, keyLabel: String): Double? {
        val nowMs = now()
        return lock.withLock {
            val bucket = bucketFor(spec, keyLabel)
            prune(bucket, nowMs)
            val fractions = buildList {
                spec.rpmLimit?.let { limit ->
                    add(bucket.count { nowMs - it.atMs < MINUTE_MS }.toDouble() / limit)
                }
                spec.rpdLimit?.let { limit ->
                    add(bucket.count { nowMs - it.atMs < DAY_MS }.toDouble() / limit)
                }
                spec.tpmLimit?.let { limit ->
                    add(bucket.filter { nowMs - it.atMs < MINUTE_MS }.sumOf { it.tokens }.toDouble() / limit)
                }
                spec.tpdLimit?.let { limit ->
                    add(bucket.filter { nowMs - it.atMs < DAY_MS }.sumOf { it.tokens }.toDouble() / limit)
                }
            }
            fractions.maxOrNull()?.coerceIn(0.0, 1.0)
        }
    }

    suspend fun reset() = lock.withLock { events.clear() }

    private fun bucketFor(spec: ModelSpec, keyLabel: String): MutableList<Event> =
        events.getOrPut("${spec.key}|$keyLabel") { mutableListOf() }

    /** Drop anything past the widest window we care about. */
    private fun prune(bucket: MutableList<Event>, nowMs: Long) {
        bucket.removeAll { nowMs - it.atMs >= DAY_MS }
    }

    private fun waitFor(bucket: List<Event>, nowMs: Long, windowMs: Long): Long {
        val oldestInWindow = bucket.filter { nowMs - it.atMs < windowMs }.minOfOrNull { it.atMs }
            ?: return windowMs
        return (oldestInWindow + windowMs - nowMs).coerceAtLeast(0L)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
