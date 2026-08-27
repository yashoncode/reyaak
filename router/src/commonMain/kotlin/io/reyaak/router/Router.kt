package io.reyaak.router

import io.reyaak.router.catalog.ModelSpec
import io.reyaak.router.catalog.Provider
import io.reyaak.router.catalog.Wire
import io.reyaak.router.config.KeyRecord
import io.reyaak.router.config.RouterSettings
import io.reyaak.router.error.ErrorClass
import io.reyaak.router.error.ProviderException
import io.reyaak.router.health.HealthStore
import io.reyaak.router.limit.RateLimiter
import io.reyaak.router.model.ChatRequest
import io.reyaak.router.model.ChatResponse
import io.reyaak.router.model.Served
import io.reyaak.router.model.StreamEvent
import io.reyaak.router.provider.AttemptContext
import io.reyaak.router.provider.GeminiAdapter
import io.reyaak.router.provider.OpenAiCompatAdapter
import io.reyaak.router.provider.ProviderAdapter
import io.reyaak.router.score.ScoreInputs
import io.reyaak.router.score.RoutingStrategy
import io.reyaak.router.score.combineScore
import io.reyaak.router.score.intelligenceComposite
import io.reyaak.router.score.intelligenceScore
import io.reyaak.router.score.rateLimitFactor
import io.reyaak.router.score.rateWindowHeadroomFactor
import io.reyaak.router.score.sampleReliability
import io.reyaak.router.score.SPEED_PRIOR
import io.reyaak.router.score.speedScore
import io.reyaak.router.time.epochMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/** One routable target: a model, reached with a particular key. */
data class Candidate(
    val provider: Provider,
    val model: ModelSpec,
    val key: KeyRecord,
    val score: Double,
    val healthKey: String,
) {
    fun context() = AttemptContext(
        provider = provider,
        model = model,
        apiKey = key.secret.orEmpty(),
        baseUrlOverride = key.baseUrl,
    )
}

/** Why a candidate was excluded, so the UI can explain an empty chain. */
data class Exclusion(val modelKey: String, val reason: String)

data class Selection(
    val candidates: List<Candidate>,
    val exclusions: List<Exclusion>,
)

/** One recorded attempt, for the trace the UI shows after a reply. */
data class Attempt(
    val platform: String,
    val modelId: String,
    val keyLabel: String,
    val outcome: String,
    val latencyMs: Long,
)

class RouterException(
    message: String,
    val attempts: List<Attempt>,
    val lastErrorClass: ErrorClass? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The agent's only path to a model.
 *
 * Everything provider-specific lives below this line: selection, health, limits,
 * fallback, and the two wire dialects. Callers hand over a [ChatRequest] and
 * receive text, they never learn which provider served it, beyond the [Served]
 * record attached for display.
 */
class Router(
    private val settingsProvider: () -> RouterSettings,
    private val health: HealthStore = HealthStore(),
    private val limiter: RateLimiter = RateLimiter(),
    private val rng: Random = Random.Default,
    private val now: () -> Long = ::epochMillis,
    private val adapters: Map<Wire, ProviderAdapter> = mapOf(
        Wire.OPENAI to OpenAiCompatAdapter(),
        Wire.GEMINI to GeminiAdapter(),
    ),
) {

    val settings: RouterSettings get() = settingsProvider()

    // ── Selection ───────────────────────────────────────────────────────────

    /**
     * Filter, score, rank. The resulting order IS the fallback chain, there is
     * no separate fallback config to drift out of sync with the ranking.
     *
     * Suspends because health and rate-window state are mutex-guarded rather
     * than held in a concurrent map, see [HealthStore]. Every caller was
     * already inside a coroutine, so nothing about the call sites changed.
     */
    suspend fun select(request: ChatRequest): Selection {
        val s = settings
        val nowMs = now()
        val exclusions = mutableListOf<Exclusion>()
        val estimatedTokens = estimateTokens(request)

        data class Scored(val provider: Provider, val model: ModelSpec, val key: KeyRecord, val composite: Double)

        val viable = mutableListOf<Scored>()

        for (model in s.models) {
            if (!model.enabled) {
                exclusions += Exclusion(model.key, "disabled")
                continue
            }
            if (request.model != null && model.modelId != request.model && model.key != request.model) {
                continue
            }
            val provider = s.provider(model.platform)
            if (provider == null) {
                exclusions += Exclusion(model.key, "no provider")
                continue
            }
            if (request.tools.isNotEmpty() && !model.supportsTools) {
                exclusions += Exclusion(model.key, "no tool support")
                continue
            }
            if (request.needsVision && !model.supportsVision) {
                exclusions += Exclusion(model.key, "no vision")
                continue
            }
            if (request.needsJsonMode && !model.supportsJsonMode) {
                exclusions += Exclusion(model.key, "no JSON mode")
                continue
            }
            val window = model.contextWindow
            if (window != null && estimatedTokens > window) {
                exclusions += Exclusion(model.key, "context too small ($estimatedTokens > $window)")
                continue
            }
            val keys = s.keysFor(model.platform)
            if (keys.isEmpty()) {
                exclusions += Exclusion(model.key, "no key for ${model.platform}")
                continue
            }

            for (key in keys) {
                val healthKey = HealthStore.keyOf(model.platform, model.modelId, key.label)
                val snapshot = health.snapshot(healthKey)
                if (snapshot.benched(nowMs)) {
                    exclusions += Exclusion(
                        model.key,
                        "cooling down ${snapshot.cooldownRemainingMs(nowMs) / 1000}s (${snapshot.lastErrorClass})",
                    )
                    continue
                }
                viable += Scored(
                    provider = provider,
                    model = model,
                    key = key,
                    composite = intelligenceComposite(model.sizeLabel, model.intelligenceRank),
                )
            }
        }

        if (viable.isEmpty()) return Selection(emptyList(), exclusions)

        // Both seeded axes are min-max normalized across the CANDIDATE SET, not
        // over their nominal 1..1000 range. That matters: the convex combination
        // assumes every signal spans a comparable [0,1], and normalizing
        // intelligence over the candidates while normalizing speedRank over
        // 1..1000 made the speed axis almost flat (ranks 1 and 9 landed 0.008
        // apart) so no weighting of "fastest" could outvote intelligence.
        val minComposite = viable.minOf { it.composite }
        val maxComposite = viable.maxOf { it.composite }
        val minSpeedRank = viable.minOf { it.model.speedRank }
        val maxSpeedRank = viable.maxOf { it.model.speedRank }
        val weights = s.weights()

        val candidates = viable.map { scored ->
            val healthKey = HealthStore.keyOf(scored.model.platform, scored.model.modelId, scored.key.label)
            val snapshot = health.snapshot(healthKey)

            // Real measurements win; until they exist, the catalog speedRank
            // seeds the axis so a known-fast model is not treated identically to
            // an unknown one. Lower rank is faster, hence the inversion. With a
            // single candidate, or all ranks equal, there is nothing to
            // discriminate and the neutral prior applies.
            val seedSpeed = if (maxSpeedRank <= minSpeedRank) {
                SPEED_PRIOR
            } else {
                (maxSpeedRank - scored.model.speedRank).toDouble() /
                    (maxSpeedRank - minSpeedRank)
            }
            val speed = if (snapshot.tokensPerSecond > 0 || snapshot.ttfbMs != null) {
                speedScore(snapshot.tokensPerSecond, snapshot.ttfbMs)
            } else {
                seedSpeed
            }

            val score = if (s.strategy == RoutingStrategy.PRIORITY) {
                // Manual chain: position in fallbackOrder is the whole ranking.
                val index = s.fallbackOrder.indexOf(scored.model.key)
                if (index >= 0) 1.0 - index / 1000.0 else 0.0
            } else {
                combineScore(
                    ScoreInputs(
                        reliability = sampleReliability(snapshot.successes, snapshot.failures, rng),
                        speed = speed,
                        intelligence = intelligenceScore(scored.composite, minComposite, maxComposite),
                        // Only the rate-window guardrail is live. The monthly
                        // token budget is declarative-config-only for now, so
                        // folding headroomFactor() in here would contribute a
                        // constant 1.0 and read as if it did something.
                        headroom = rateWindowHeadroomFactor(
                            limiter.utilization(scored.model, scored.key.label)
                        ),
                        rateLimit = rateLimitFactor(snapshot.penalty),
                    ),
                    weights,
                )
            }

            Candidate(
                provider = scored.provider,
                model = scored.model,
                key = scored.key,
                score = score,
                healthKey = healthKey,
            )
        }.sortedWith(
            compareByDescending<Candidate> { it.score }
                // Explicit order breaks ties, so a user preference still shows
                // through when two candidates score identically.
                .thenBy { s.fallbackOrder.indexOf(it.model.key).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
                .thenBy { it.model.key }
        )

        return Selection(candidates, exclusions)
    }

    // ── Execution ───────────────────────────────────────────────────────────

    suspend fun complete(request: ChatRequest): ChatResponse {
        val deadline = now() + FALLBACK_BUDGET_MS
        val selection = select(request)
        if (selection.candidates.isEmpty()) throw noCandidates(selection)

        val attempts = mutableListOf<Attempt>()
        var lastError: ProviderException? = null

        for ((index, candidate) in selection.candidates.withIndex()) {
            if (index >= MAX_ATTEMPTS) break
            if (now() >= deadline) break

            val estimated = estimateTokens(request)
            val waitMs = limiter.retryAfterMs(candidate.model, candidate.key.label, estimated)
            if (waitMs != null) {
                attempts += Attempt(
                    candidate.model.platform, candidate.model.modelId, candidate.key.label,
                    "skipped: local rate limit, ${waitMs / 1000}s", 0,
                )
                continue
            }

            val adapter = adapters[candidate.provider.wire] ?: continue
            val started = now()
            try {
                val response = adapter.complete(candidate.context(), request)
                val elapsed = now() - started
                limiter.record(candidate.model, candidate.key.label, response.usage.totalTokens)
                health.recordSuccess(
                    candidate.healthKey,
                    latencyMs = elapsed,
                    ttfbMs = null,
                    outputTokens = response.usage.completionTokens,
                )
                attempts += Attempt(
                    candidate.model.platform, candidate.model.modelId, candidate.key.label,
                    "ok", elapsed,
                )
                return response.copy(
                    served = response.served.copy(attempts = attempts.size)
                )
            } catch (e: ProviderException) {
                val elapsed = now() - started
                lastError = e
                health.recordFailure(
                    candidate.healthKey, e.errorClass, e.message, e.statedRetryAfterMs,
                )
                attempts += Attempt(
                    candidate.model.platform, candidate.model.modelId, candidate.key.label,
                    "${e.errorClass.name.lowercase()}${e.status?.let { " ($it)" } ?: ""}", elapsed,
                )
                if (!e.errorClass.failOver) break
            }
        }

        throw RouterException(
            message = exhaustionMessage(lastError, attempts),
            attempts = attempts,
            lastErrorClass = lastError?.errorClass,
            cause = lastError,
        )
    }

    /**
     * Streaming with fallback, and the one place the rule has to be strict:
     * before the first token, failing over is free; after it, the attempt is
     * COMMITTED. Switching providers mid-stream would splice two models' output
     * into one reply, so a late failure is surfaced rather than papered over.
     */
    fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val deadline = now() + FALLBACK_BUDGET_MS
        val selection = select(request)
        if (selection.candidates.isEmpty()) throw noCandidates(selection)

        val attempts = mutableListOf<Attempt>()
        var lastError: ProviderException? = null

        for ((index, candidate) in selection.candidates.withIndex()) {
            if (index >= MAX_ATTEMPTS) break
            if (now() >= deadline) break

            val estimated = estimateTokens(request)
            if (limiter.retryAfterMs(candidate.model, candidate.key.label, estimated) != null) {
                attempts += Attempt(
                    candidate.model.platform, candidate.model.modelId, candidate.key.label,
                    "skipped: local rate limit", 0,
                )
                continue
            }

            val adapter = adapters[candidate.provider.wire] ?: continue
            val started = now()
            var committed = false
            var firstTokenAt: Long? = null
            var completionTokens = 0

            try {
                adapter.stream(candidate.context(), request).collect { event ->
                    when (event) {
                        is StreamEvent.Text -> {
                            if (!committed) {
                                committed = true
                                firstTokenAt = now()
                            }
                            emit(event)
                        }
                        is StreamEvent.Tool -> {
                            committed = true
                            emit(event)
                        }
                        is StreamEvent.Done -> {
                            completionTokens = event.usage.completionTokens
                            emit(
                                event.copy(
                                    served = event.served.copy(attempts = attempts.size + 1)
                                )
                            )
                        }
                    }
                }
                val elapsed = now() - started
                limiter.record(candidate.model, candidate.key.label, completionTokens + estimated)
                health.recordSuccess(
                    candidate.healthKey,
                    latencyMs = elapsed,
                    ttfbMs = firstTokenAt?.let { it - started },
                    outputTokens = completionTokens,
                )
                attempts += Attempt(
                    candidate.model.platform, candidate.model.modelId, candidate.key.label,
                    "ok", elapsed,
                )
                return@flow
            } catch (e: ProviderException) {
                val elapsed = now() - started
                lastError = e
                health.recordFailure(candidate.healthKey, e.errorClass, e.message, e.statedRetryAfterMs)
                attempts += Attempt(
                    candidate.model.platform, candidate.model.modelId, candidate.key.label,
                    "${e.errorClass.name.lowercase()}${if (committed) ", mid-stream" else ""}", elapsed,
                )
                if (committed) {
                    throw RouterException(
                        "the reply was cut off by ${candidate.provider.label}: ${e.message}",
                        attempts, e.errorClass, e,
                    )
                }
                if (!e.errorClass.failOver) break
            }
        }

        throw RouterException(
            message = exhaustionMessage(lastError, attempts),
            attempts = attempts,
            lastErrorClass = lastError?.errorClass,
            cause = lastError,
        )
    }

    suspend fun listModels(platform: String): List<String> {
        val s = settings
        val provider = s.provider(platform) ?: return emptyList()
        val key = s.keysFor(platform).firstOrNull() ?: return emptyList()
        val adapter = adapters[provider.wire] ?: return emptyList()
        val spec = s.models.firstOrNull { it.platform == platform }
            ?: ModelSpec(platform, "probe", "probe", "Medium")
        return adapter.listModels(
            AttemptContext(provider, spec, key.secret.orEmpty(), key.baseUrl)
        )
    }

    suspend fun healthSnapshot() = health.all()

    suspend fun utilizationOf(model: ModelSpec, keyLabel: String) =
        limiter.utilization(model, keyLabel)

    suspend fun clearCooldown(healthKey: String) = health.clearCooldown(healthKey)

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun noCandidates(selection: Selection): RouterException {
        val detail = selection.exclusions.take(4).joinToString("; ") { "${it.modelKey}: ${it.reason}" }
        val hint = if (settings.configuredPlatforms().isEmpty()) {
            "no provider key has been added yet"
        } else {
            detail.ifBlank { "every candidate was filtered out" }
        }
        return RouterException("No model could serve this request: $hint", emptyList())
    }

    private fun exhaustionMessage(last: ProviderException?, attempts: List<Attempt>): String {
        if (last == null) return "No model could serve this request after ${attempts.size} attempts"
        return when (last.errorClass) {
            ErrorClass.KEY_INVALID -> "Every key was rejected. Check the API keys on the Router screen."
            ErrorClass.QUOTA_EXHAUSTED -> "Every provider is out of free quota for now."
            ErrorClass.RATE_LIMITED -> "Every provider is rate limited right now. Try again shortly."
            ErrorClass.CONTEXT_TOO_LARGE -> "This conversation is too long for every available model."
            else -> "All ${attempts.size} attempts failed. Last error: ${last.message}"
        }
    }

    companion object {
        /** Wall clock is the real stopping condition; a phone user is waiting. */
        const val FALLBACK_BUDGET_MS = 45_000L

        /** Lower than the 20 a server would use, for the same reason. */
        const val MAX_ATTEMPTS = 8

        /**
         * Rough token estimate: 4 characters per token, plus per-message
         * overhead.
         *
         * A real tokenizer on-device is a large dependency for a decision that
         * only needs to be approximately right, and being wrong is bounded:
         * context-length errors are fail-over-able, so an underestimate costs
         * one attempt rather than a failed request.
         */
        fun estimateTokens(request: ChatRequest): Int {
            val body = request.messages.sumOf { message ->
                message.content.length / 4 + 4 +
                    message.toolCalls.sumOf { it.arguments.length / 4 + it.name.length }
            }
            val tools = request.tools.sumOf { it.parameters.length / 4 + it.description.length / 4 }
            return body + tools
        }
    }
}
