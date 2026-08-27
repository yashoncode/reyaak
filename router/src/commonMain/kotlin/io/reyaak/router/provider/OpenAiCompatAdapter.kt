package io.reyaak.router.provider

import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import io.reyaak.router.model.ChatMessage
import io.reyaak.router.model.ChatRequest
import io.reyaak.router.model.ChatResponse
import io.reyaak.router.model.FinishReason
import io.reyaak.router.model.Role
import io.reyaak.router.model.Served
import io.reyaak.router.model.StreamEvent
import io.reyaak.router.model.ToolCall
import io.reyaak.router.model.ToolDefinition
import io.reyaak.router.model.Usage
import io.reyaak.router.provider.Http.applyAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.TimeSource

// ── Wire types ──────────────────────────────────────────────────────────────

@Serializable
internal data class OaiFunction(val name: String, val arguments: String? = null)

@Serializable
internal data class OaiToolCall(
    val id: String? = null,
    val index: Int? = null,
    val type: String? = null,
    val function: OaiFunction? = null,
)

@Serializable
internal data class OaiMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OaiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
private data class OaiToolSpec(
    val type: String = "function",
    val function: OaiFunctionSpec,
)

@Serializable
private data class OaiFunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

@Serializable
private data class OaiRequest(
    val model: String,
    val messages: List<OaiMessage>,
    val tools: List<OaiToolSpec>? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
)

@Serializable
internal data class OaiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)

@Serializable
internal data class OaiChoice(
    val message: OaiMessage? = null,
    val delta: OaiMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class OaiResponse(
    val choices: List<OaiChoice> = emptyList(),
    val usage: OaiUsage? = null,
)

@Serializable
private data class OaiModelList(val data: List<OaiModelEntry> = emptyList())

@Serializable
private data class OaiModelEntry(val id: String)

/**
 * The generic adapter, and the reason Reyaak needs only two.
 *
 * Groq, Cerebras, Mistral, NVIDIA, OpenRouter and effectively every other free
 * relay speak this dialect; they differ only in base URL, auth header, and small
 * quirks that live on the catalog row. Adding one of them is a table entry, not
 * a class.
 */
class OpenAiCompatAdapter : ProviderAdapter {

    override suspend fun complete(ctx: AttemptContext, request: ChatRequest): ChatResponse {
        val clock = TimeSource.Monotonic.markNow()
        val payload = Http.json.encodeToString(
            OaiRequest.serializer(),
            request.toWire(ctx.model.modelId, stream = false),
        )

        val response: HttpResponse = try {
            Http.client.post("${ctx.baseUrl}/chat/completions") {
                applyAuth(ctx)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Http.transport(ctx, e)
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw response.toError(ctx, body)

        val parsed = runCatching {
            Http.json.decodeFromString(OaiResponse.serializer(), body)
        }.getOrElse { throw Http.transport(ctx, it) }

        val choice = parsed.choices.firstOrNull()
        val message = choice?.message
        return ChatResponse(
            message = ChatMessage(
                role = Role.ASSISTANT,
                content = message?.content.orEmpty(),
                toolCalls = message?.toolCalls?.mapNotNull { it.toDomain() } ?: emptyList(),
            ),
            finishReason = finishReasonOf(choice?.finishReason),
            usage = Usage(
                promptTokens = parsed.usage?.promptTokens ?: 0,
                completionTokens = parsed.usage?.completionTokens ?: 0,
            ),
            served = Served(
                providerId = ctx.provider.id,
                modelId = ctx.model.modelId,
                attempts = 1,
                latencyMs = clock.elapsedNow().inWholeMilliseconds,
            ),
        )
    }

    override fun stream(ctx: AttemptContext, request: ChatRequest): Flow<StreamEvent> = flow {
        val clock = TimeSource.Monotonic.markNow()
        val payload = Http.json.encodeToString(
            OaiRequest.serializer(),
            request.toWire(ctx.model.modelId, stream = true),
        )

        val statement = Http.client.preparePost("${ctx.baseUrl}/chat/completions") {
            applyAuth(ctx)
            accept(ContentType.Text.EventStream)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        try {
            statement.execute { response ->
                if (!response.status.isSuccess()) {
                    throw response.toError(ctx, response.bodyAsText())
                }
                val channel = response.bodyAsChannel()

                // Tool-call fragments arrive keyed by index across many chunks,
                // so they are accumulated here and only emitted once complete.
                // Callers must never see partial JSON arguments.
                val toolAccumulator = ToolCallAccumulator()
                var finish = FinishReason.UNKNOWN
                var usage = Usage()

                while (true) {
                    val line = channel.readLine() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith(DATA_PREFIX)) continue
                    val data = line.removePrefix(DATA_PREFIX).trim()
                    if (data == DONE_SENTINEL) break

                    val chunk = decodeOaiChunk(data) ?: continue

                    chunk.usage?.let { usage = Usage(it.promptTokens, it.completionTokens) }
                    val choice = chunk.choices.firstOrNull() ?: continue
                    choice.finishReason?.let { finish = finishReasonOf(it) }

                    val delta = choice.delta ?: continue
                    delta.content?.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.Text(it)) }
                    delta.toolCalls?.forEach {
                        toolAccumulator.accept(it.index, it.id, it.function?.name, it.function?.arguments)
                    }
                }

                toolAccumulator.complete().forEach { emit(StreamEvent.Tool(it)) }
                if (toolAccumulator.any() && finish == FinishReason.UNKNOWN) {
                    finish = FinishReason.TOOL_CALLS
                }

                emit(
                    StreamEvent.Done(
                        finishReason = finish,
                        usage = usage,
                        served = Served(
                            providerId = ctx.provider.id,
                            modelId = ctx.model.modelId,
                            attempts = 1,
                            latencyMs = clock.elapsedNow().inWholeMilliseconds,
                        ),
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: io.reyaak.router.error.ProviderException) {
            throw e
        } catch (e: Exception) {
            throw Http.transport(ctx, e)
        }
    }

    override suspend fun listModels(ctx: AttemptContext): List<String> {
        val response: HttpResponse = try {
            Http.client.get("${ctx.baseUrl}/models") { applyAuth(ctx) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Http.transport(ctx, e)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw response.toError(ctx, body)
        return runCatching {
            Http.json.decodeFromString(OaiModelList.serializer(), body).data.map { it.id }
        }.getOrElse { emptyList() }
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private fun ChatRequest.toWire(modelId: String, stream: Boolean) = OaiRequest(
        model = modelId,
        messages = messages.map { it.toWire() },
        tools = tools.takeIf { it.isNotEmpty() }?.map { it.toWire() },
        temperature = temperature,
        maxTokens = maxOutputTokens,
        stream = stream,
    )

    private fun ChatMessage.toWire() = OaiMessage(
        role = role.name.lowercase(),
        content = content.takeIf { it.isNotEmpty() },
        toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map {
            OaiToolCall(id = it.id, type = "function", function = OaiFunction(it.name, it.arguments))
        },
        toolCallId = toolCallId,
    )

    private fun ToolDefinition.toWire() = OaiToolSpec(
        function = OaiFunctionSpec(
            name = name,
            description = description,
            parameters = runCatching { Http.json.parseToJsonElement(parameters) }
                .getOrElse { JsonObject(emptyMap()) },
        )
    )

    private fun OaiToolCall.toDomain(): ToolCall? {
        val fn = function ?: return null
        return ToolCall(
            id = id ?: "call_${index ?: 0}",
            name = fn.name,
            arguments = fn.arguments.orEmpty().ifBlank { "{}" },
        )
    }

    private companion object {
        const val DATA_PREFIX = "data:"
        const val DONE_SENTINEL = "[DONE]"

        fun finishReasonOf(raw: String?): FinishReason = when (raw) {
            "stop", "end_turn" -> FinishReason.STOP
            "length", "max_tokens" -> FinishReason.LENGTH
            "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
            "content_filter" -> FinishReason.CONTENT_FILTER
            else -> FinishReason.UNKNOWN
        }
    }
}

/**
 * Decode one SSE `data:` payload, or null when it is not a chunk we understand.
 *
 * Extracted so the decoding can be tested without a transport. It has already
 * earned that once: every field of a streamed delta except `content` is
 * optional on the wire, and one non-nullable field here silently dropped every
 * chunk of every reply.
 */
internal fun decodeOaiChunk(data: String): OaiResponse? = runCatching {
    Http.json.decodeFromString(OaiResponse.serializer(), data)
}.getOrNull()

/** Shared shorthand: classify a failed response, keeping the Retry-After hint. */
internal fun HttpResponse.toError(ctx: AttemptContext, body: String?) =
    Http.error(ctx, status.value, headers[HttpHeaders.RetryAfter], body)

/**
 * Reassembles index-keyed tool-call fragments from a stream.
 *
 * Providers split one call across many chunks: the first usually carries the id
 * and name, later ones append argument text. Emitting as they arrive would hand
 * callers unparseable half-JSON, so nothing is emitted until the stream ends.
 */
internal class ToolCallAccumulator {
    private data class Partial(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )

    private val byIndex = linkedMapOf<Int, Partial>()

    fun accept(index: Int?, id: String?, name: String?, argumentDelta: String?) {
        val slot = byIndex.getOrPut(index ?: byIndex.size) { Partial() }
        id?.let { slot.id = it }
        name?.takeIf { it.isNotBlank() }?.let { slot.name = it }
        argumentDelta?.let { slot.arguments.append(it) }
    }

    fun any(): Boolean = byIndex.values.any { it.name != null }

    fun complete(): List<ToolCall> = byIndex.entries.mapNotNull { (index, partial) ->
        val name = partial.name ?: return@mapNotNull null
        ToolCall(
            id = partial.id ?: "call_$index",
            name = name,
            arguments = partial.arguments.toString().ifBlank { "{}" },
        )
    }
}
