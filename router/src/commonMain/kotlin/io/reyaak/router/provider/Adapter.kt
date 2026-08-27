package io.reyaak.router.provider

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.reyaak.router.catalog.Auth
import io.reyaak.router.catalog.ModelSpec
import io.reyaak.router.catalog.Provider
import io.reyaak.router.error.ProviderException
import io.reyaak.router.error.classify
import io.reyaak.router.error.parseRetryAfterHeader
import io.reyaak.router.error.parseStatedRetryMs
import io.reyaak.router.model.ChatRequest
import io.reyaak.router.model.ChatResponse
import io.reyaak.router.model.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One attempt against one (provider, model, key) triple. */
data class AttemptContext(
    val provider: Provider,
    val model: ModelSpec,
    val apiKey: String,
    /** Set when a key carries its own endpoint, e.g. a custom provider. */
    val baseUrlOverride: String? = null,
) {
    val baseUrl: String get() = (baseUrlOverride ?: provider.baseUrl).trimEnd('/')
}

/**
 * A provider's wire protocol.
 *
 * Deliberately narrow: the router handles selection, health, limits, and
 * fallback, so an adapter only has to speak one dialect and translate errors
 * into [ProviderException]. Adding an OpenAI-compatible provider needs no new
 * adapter at all, only a catalog row.
 */
interface ProviderAdapter {

    suspend fun complete(ctx: AttemptContext, request: ChatRequest): ChatResponse

    fun stream(ctx: AttemptContext, request: ChatRequest): Flow<StreamEvent>

    /**
     * Model ids the provider actually serves right now.
     *
     * Exists because a hardcoded catalog rots: providers retire and rename
     * models, so the Router screen can refresh from the source rather than
     * waiting for an app update.
     */
    suspend fun listModels(ctx: AttemptContext): List<String>
}

internal object Http {

    /**
     * One Ktor client for every provider.
     *
     * No engine is named. Each target has exactly one on its classpath
     * (OkHttp on Android and the JVM, Darwin on iOS), and Ktor picks it up, which is
     * what lets a single adapter implementation serve both platforms.
     *
     * Timeouts are per-attempt, not per-request: the router runs a whole
     * fallback chain inside its own wall-clock budget, so one slow provider must
     * not be allowed to eat it. The overall request timeout is infinite on
     * purpose, because a streaming response legitimately stays open for
     * minutes; the socket timeout is what catches a stream that has stopped
     * producing, and the router's budget bounds the rest.
     */
    val client: HttpClient = HttpClient {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun HttpRequestBuilder.applyAuth(ctx: AttemptContext) {
        when (val auth = ctx.provider.auth) {
            is Auth.Bearer -> header(HttpHeaders.Authorization, "Bearer ${ctx.apiKey}")
            is Auth.Header -> header(auth.name, ctx.apiKey)
        }
        // Identity encoding keeps SSE framing intact for line-oriented reading.
        header(HttpHeaders.AcceptEncoding, "identity")
    }

    /**
     * Turn a non-2xx response into a classified [ProviderException].
     *
     * The body is read once, used to extract a stated retry delay, and then
     * discarded apart from a truncated message. Provider error bodies routinely
     * echo request content, so retaining one would put user text into logs and
     * traces that were never meant to hold it.
     *
     * The status and the Retry-After value are passed in rather than the
     * response itself, so this signature carries no HTTP-client type and the
     * classification stays testable without a transport.
     */
    fun error(
        ctx: AttemptContext,
        status: Int,
        retryAfterHeader: String?,
        bodyText: String?,
    ): ProviderException {
        val parsed = bodyText?.let { text ->
            runCatching { json.parseToJsonElement(text) }.getOrNull()
        }
        val message = extractMessage(parsed, bodyText, status)
        val stated = parseRetryAfterHeader(retryAfterHeader)
            ?: parseStatedRetryMs(parsed, bodyText)

        return ProviderException(
            providerId = ctx.provider.id,
            modelId = ctx.model.modelId,
            status = status,
            errorClass = classify(status, message),
            statedRetryAfterMs = stated,
            message = "${ctx.provider.id}/${ctx.model.modelId} HTTP $status: ${message.take(300)}",
        )
    }

    /** Transport-level failure: no status, so classification falls to the text. */
    fun transport(ctx: AttemptContext, cause: Throwable): ProviderException = ProviderException(
        providerId = ctx.provider.id,
        modelId = ctx.model.modelId,
        status = null,
        errorClass = classify(null, cause.messageOrClass()),
        statedRetryAfterMs = null,
        message = "${ctx.provider.id}/${ctx.model.modelId}: ${cause.messageOrClass()}",
        cause = cause,
    )

    private fun Throwable.messageOrClass(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "unknown transport error"

    /**
     * Providers bury the human-readable reason in different places:
     * `error.message` (OpenAI, Gemini), `message`, `detail` (FastAPI-backed
     * relays), or nothing at all. Try each, then fall back to the raw text.
     */
    private fun extractMessage(parsed: JsonElement?, raw: String?, code: Int): String {
        val obj = parsed as? JsonObject
        if (obj != null) {
            stringAt(obj, "error", "message")?.let { return it }
            stringAt(obj, "message")?.let { return it }
            stringAt(obj, "detail")?.let { return it }
            stringAt(obj, "error")?.let { return it }
        }
        return raw?.takeIf { it.isNotBlank() }?.take(300) ?: "HTTP $code"
    }

    private fun stringAt(obj: JsonObject, vararg path: String): String? {
        var node: JsonElement = obj
        for (segment in path) {
            node = (node as? JsonObject)?.get(segment) ?: return null
        }
        val primitive = node as? JsonPrimitive ?: return null
        return primitive.contentOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun JsonPrimitive.contentOrNull(): String? =
        if (isString) content else content.takeIf { it != "null" }
}
