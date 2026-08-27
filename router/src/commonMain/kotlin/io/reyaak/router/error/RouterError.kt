package io.reyaak.router.error

import io.ktor.http.fromHttpToGmtDate
import io.reyaak.router.time.epochMillis
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * What went wrong, and what the router should do about it.
 *
 * The class decides three independent things: whether to try another candidate,
 * what to bench, and for how long. Keeping those separate is what stops a
 * per-model problem from disabling a working key, and vice versa.
 */
enum class ErrorClass(
    val failOver: Boolean,
    val bench: Bench,
) {
    /** Transient upstream fault: 5xx, 408, 409, timeouts, transport errors. */
    TRANSIENT(failOver = true, bench = Bench.NOTHING),

    /** 429 or an explicit rate-limit message. Honour any stated delay. */
    RATE_LIMITED(failOver = true, bench = Bench.KEY),

    /** Free-tier allowance is spent. Bench the key until its window resets. */
    QUOTA_EXHAUSTED(failOver = true, bench = Bench.KEY),

    /** 401, or an invalid/revoked key. Nothing else on this key will work. */
    KEY_INVALID(failOver = true, bench = Bench.KEY),

    /**
     * 403 with a valid key: this model is off the key's tier. Not fatal:
     * another model is reachable, so fail over and remember the pairing.
     */
    MODEL_FORBIDDEN(failOver = true, bench = Bench.MODEL),

    /** 404/410: the model was retired upstream. It will not come back here. */
    MODEL_GONE(failOver = true, bench = Bench.MODEL),

    /**
     * 413, or a context-length rejection. Another candidate may have a bigger
     * window, so this fails over; if every candidate refuses, the caller gets an
     * honest context error rather than a generic failure.
     */
    CONTEXT_TOO_LARGE(failOver = true, bench = Bench.NOTHING),

    /** 400/422: the request shape itself is wrong. Worth one fail-over. */
    BAD_REQUEST(failOver = true, bench = Bench.NOTHING),

    /** 402: billing required. No amount of retrying fixes it. */
    PAYMENT_REQUIRED(failOver = true, bench = Bench.KEY),

    /** Nothing above matched, and it does not look retryable. */
    FATAL(failOver = false, bench = Bench.NOTHING);

    enum class Bench { NOTHING, KEY, MODEL }
}

/** An upstream failure, carrying everything the router needs to decide. */
class ProviderException(
    val providerId: String,
    val modelId: String,
    val status: Int?,
    val errorClass: ErrorClass,
    /** A delay the provider explicitly stated, if it did. Beats any heuristic. */
    val statedRetryAfterMs: Long?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Upper bound on any provider-supplied backoff. */
const val MAX_RETRY_AFTER_MS: Long = 24 * 60 * 60 * 1000L

private val PROTOBUF_DURATION = Regex("""^(\d+(?:\.\d+)?)s$""")

/**
 * Anchored on an explicit retry phrase, so an unrelated number elsewhere in an
 * error message can never be mistaken for a backoff.
 */
private val PROSE_RETRY = Regex(
    """(?:try again|retry)\s+(?:in|after)\s+(\d+(?:\.\d+)?)\s*""" +
        """(ms|s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hour|hours)\b""",
    RegexOption.IGNORE_CASE,
)

private val UNIT_MS = mapOf(
    "ms" to 1L,
    "s" to 1_000L, "sec" to 1_000L, "secs" to 1_000L, "second" to 1_000L, "seconds" to 1_000L,
    "m" to 60_000L, "min" to 60_000L, "mins" to 60_000L, "minute" to 60_000L, "minutes" to 60_000L,
    "h" to 3_600_000L, "hour" to 3_600_000L, "hours" to 3_600_000L,
)

private fun clampRetry(ms: Long?): Long? =
    if (ms == null || ms < 0) null else ms.coerceAtMost(MAX_RETRY_AFTER_MS)

/**
 * Parse an HTTP `Retry-After`: either delta-seconds or an HTTP-date.
 *
 * Clamped, because a malformed or hostile value (`Retry-After: 99999999999`)
 * feeds a cooldown expiry directly and would otherwise bench a key effectively
 * forever. A day is longer than any real free-tier reset window, so clamping
 * cannot mask a genuine hint.
 */
fun parseRetryAfterHeader(value: String?, nowMs: Long = epochMillis()): Long? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    trimmed.toLongOrNull()?.let { return clampRetry(it * 1000L) }
    val date = runCatching { parseHttpDate(trimmed) }.getOrNull() ?: return null
    return clampRetry((date - nowMs).coerceAtLeast(0L))
}

/**
 * IMF-fixdate only: `Thu, 01 Jan 1970 00:00:00 GMT`.
 *
 * RFC 9110 says a recipient should also accept the obsolete RFC-850 and asctime
 * forms, and the JVM version of this did. Nothing on this path is a general HTTP
 * client, though: it reads `Retry-After` from LLM providers, all of which emit
 * IMF-fixdate. An unparsed value simply falls through to the heuristic ladder,
 * which is the same place a missing header lands, so the cost of narrowing is a
 * slightly worse cooldown guess in a case that does not occur.
 */
private fun parseHttpDate(value: String): Long? =
    runCatching { value.fromHttpToGmtDate().timestamp }.getOrNull()

/**
 * The backoff a provider stated inside its error BODY.
 *
 * Not every provider uses the header. Gemini answers a 429 with
 * `error.details[]` carrying a `google.rpc.RetryInfo` whose `retryDelay` reads
 * `"17s"`, and several OpenAI-compatible tiers only say it in prose. Walking the
 * tree is more durable than a list of exact paths, because the shape varies by
 * provider and even by endpoint within one provider. Depth-capped so a
 * pathological body cannot spin.
 */
fun parseStatedRetryMs(body: JsonElement?, rawText: String? = null): Long? {
    if (body != null) {
        findStatedDelay(body, 0)?.let { return it }
    }
    // Prose last: a stated field is a promise, a sentence is only an observation.
    val text = rawText ?: body?.toString()
    val match = text?.let { PROSE_RETRY.find(it) }
    if (match != null) {
        val unit = UNIT_MS[match.groupValues[2].lowercase()]
        val amount = match.groupValues[1].toDoubleOrNull()
        if (unit != null && amount != null) return clampRetry((amount * unit).toLong())
    }
    return null
}

private fun findStatedDelay(node: JsonElement, depth: Int): Long? {
    if (depth > 6) return null
    when (node) {
        is JsonArray -> for (item in node) findStatedDelay(item, depth + 1)?.let { return it }
        is JsonObject -> for ((key, value) in node) {
            val normalized = key.lowercase().replace("_", "").replace("-", "")
            if (normalized in RETRY_KEYS) {
                readDelay(value)?.let { return it }
            }
            findStatedDelay(value, depth + 1)?.let { return it }
        }
        else -> return null
    }
    return null
}

private val RETRY_KEYS = setOf("retrydelay", "retryafter", "retryafterseconds")

private fun readDelay(value: JsonElement): Long? {
    if (value !is JsonPrimitive) return null
    value.doubleOrNull?.let { return clampRetry((it * 1000).toLong()) }
    val text = value.contentOrNull?.trim() ?: return null
    PROTOBUF_DURATION.find(text)?.let { m ->
        return clampRetry((m.groupValues[1].toDouble() * 1000).toLong())
    }
    text.toDoubleOrNull()?.let { return clampRetry((it * 1000).toLong()) }
    return null
}

/**
 * Classify an upstream failure.
 *
 * The HTTP status is the primary, structured signal and is trusted first. The
 * message rules below are the fallback for providers that put a code in prose
 * without setting a usable status, and for transport errors, which have no
 * status at all.
 */
fun classify(status: Int?, message: String?): ErrorClass {
    val msg = message?.lowercase().orEmpty()

    when (status) {
        401 -> return ErrorClass.KEY_INVALID
        402 -> return ErrorClass.PAYMENT_REQUIRED
        403 -> return if (looksLikeInvalidKey(msg)) ErrorClass.KEY_INVALID
        else ErrorClass.MODEL_FORBIDDEN
        404, 410 -> return ErrorClass.MODEL_GONE
        408, 409 -> return ErrorClass.TRANSIENT
        413 -> return ErrorClass.CONTEXT_TOO_LARGE
        429 -> return if (looksLikeDailyQuota(msg)) ErrorClass.QUOTA_EXHAUSTED
        else ErrorClass.RATE_LIMITED
        400, 422 -> return if (isContextTooLargeText(msg)) ErrorClass.CONTEXT_TOO_LARGE
        else ErrorClass.BAD_REQUEST
    }
    if (status != null && status >= 500) return ErrorClass.TRANSIENT

    // No usable status: fall back to the text.
    if (isContextTooLargeText(msg)) return ErrorClass.CONTEXT_TOO_LARGE
    if (looksLikeDailyQuota(msg)) return ErrorClass.QUOTA_EXHAUSTED
    if (RATE_LIMIT_MARKERS.any { it in msg }) return ErrorClass.RATE_LIMITED
    if (KEY_MARKERS.any { it in msg }) return ErrorClass.KEY_INVALID
    if (GONE_MARKERS.any { it in msg }) return ErrorClass.MODEL_GONE
    if (TRANSPORT_MARKERS.any { it in msg }) return ErrorClass.TRANSIENT
    return ErrorClass.FATAL
}

private val RATE_LIMIT_MARKERS = listOf(
    "rate limit", "too many requests", "resource_exhausted", "429",
)

private val QUOTA_MARKERS = listOf(
    "quota exceeded", "daily limit", "per day", "requests per day",
    "insufficient_quota", "out of credits", "exhausted your", "free tier limit",
)

private val KEY_MARKERS = listOf(
    "invalid api key", "invalid_api_key", "api key not valid", "unauthorized",
    "incorrect api key", "no auth credentials", "authentication failed",
)

private val GONE_MARKERS = listOf(
    "no endpoints found", "model not found", "does not exist", "has been deprecated",
    "no longer available",
)

private val TRANSPORT_MARKERS = listOf(
    "timeout", "timed out", "etimedout", "econnreset", "econnrefused",
    "connection reset", "connection refused", "unexpected end of stream",
    "failed to connect", "unable to resolve host", "stream was reset",
    "unavailable", "internal server error", "socket closed", "canceled due to",
)

private val CONTEXT_MARKERS = listOf(
    "context length", "maximum context", "context window", "prompt is too long",
    "too many tokens", "input token count", "reduce the length",
    "payload too large", "request entity too large", "content too large",
    "string too long", "exceeds the maximum",
)

private fun looksLikeInvalidKey(msg: String) = KEY_MARKERS.any { it in msg }

private fun looksLikeDailyQuota(msg: String) = QUOTA_MARKERS.any { it in msg }

fun isContextTooLargeText(msg: String) = CONTEXT_MARKERS.any { it in msg.lowercase() }

/** Milliseconds until the next UTC midnight, for daily-quota cooldowns. */
fun msUntilNextUtcMidnight(nowMs: Long = epochMillis()): Long {
    val dayMs = 24 * 60 * 60 * 1000L
    return dayMs - (nowMs % dayMs)
}
