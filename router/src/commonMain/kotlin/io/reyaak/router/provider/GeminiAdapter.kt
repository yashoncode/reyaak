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
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import io.reyaak.router.error.ProviderException
import io.reyaak.router.model.ChatMessage
import io.reyaak.router.model.ChatRequest
import io.reyaak.router.model.ChatResponse
import io.reyaak.router.model.FinishReason
import io.reyaak.router.model.Role
import io.reyaak.router.model.Served
import io.reyaak.router.model.StreamEvent
import io.reyaak.router.model.ToolCall
import io.reyaak.router.model.Usage
import io.reyaak.router.provider.Http.applyAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.TimeSource

// ── Wire types ──────────────────────────────────────────────────────────────

@Serializable
private data class GFunctionCall(val name: String, val args: JsonElement? = null)

@Serializable
private data class GFunctionResponse(val name: String, val response: JsonElement)

@Serializable
private data class GPart(
    val text: String? = null,
    val functionCall: GFunctionCall? = null,
    val functionResponse: GFunctionResponse? = null,
)

@Serializable
private data class GContent(
    /** Gemini says "user" or "model": there is no assistant, and no system. */
    val role: String? = null,
    val parts: List<GPart> = emptyList(),
)

@Serializable
private data class GFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonElement? = null,
)

@Serializable
private data class GTool(val functionDeclarations: List<GFunctionDeclaration>)

@Serializable
private data class GGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

@Serializable
private data class GRequest(
    val contents: List<GContent>,
    val systemInstruction: GContent? = null,
    val tools: List<GTool>? = null,
    val generationConfig: GGenerationConfig? = null,
)

@Serializable
private data class GCandidate(
    val content: GContent? = null,
    val finishReason: String? = null,
)

@Serializable
private data class GUsage(
    @SerialName("promptTokenCount") val promptTokens: Int = 0,
    @SerialName("candidatesTokenCount") val completionTokens: Int = 0,
)

@Serializable
private data class GResponse(
    val candidates: List<GCandidate> = emptyList(),
    val usageMetadata: GUsage? = null,
)

@Serializable
private data class GModelList(val models: List<GModelEntry> = emptyList())

@Serializable
private data class GModelEntry(val name: String)

/**
 * Native Google adapter.
 *
 * Google does publish an OpenAI-compatibility shim, and using it would let the
 * generic adapter cover Gemini too. It is not used, for one concrete reason: the
 * shim flattens error bodies, and Gemini answers a 429 with a
 * `google.rpc.RetryInfo` whose `retryDelay` states exactly how long to wait.
 * That value is the single most useful input to a cooldown decision, so the
 * router keeps the native path to keep the hint.
 *
 * The translation itself is small but genuinely different: system prompts move
 * to `systemInstruction`, `assistant` becomes `model`, and tool results become
 * `functionResponse` parts on a user turn rather than their own role.
 */
class GeminiAdapter : ProviderAdapter {

    override suspend fun complete(ctx: AttemptContext, request: ChatRequest): ChatResponse {
        val clock = TimeSource.Monotonic.markNow()
        val payload = Http.json.encodeToString(GRequest.serializer(), request.toWire())

        val response: HttpResponse = try {
            Http.client.post("${ctx.baseUrl}/models/${ctx.model.modelId}:generateContent") {
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
            Http.json.decodeFromString(GResponse.serializer(), body)
        }.getOrElse { throw Http.transport(ctx, it) }

        val candidate = parsed.candidates.firstOrNull()
        val parts = candidate?.content?.parts.orEmpty()

        return ChatResponse(
            message = ChatMessage(
                role = Role.ASSISTANT,
                content = parts.mapNotNull { it.text }.joinToString(""),
                toolCalls = parts.mapIndexedNotNull { index, part ->
                    part.functionCall?.toDomain(index)
                },
            ),
            finishReason = finishReasonOf(candidate?.finishReason),
            usage = Usage(
                promptTokens = parsed.usageMetadata?.promptTokens ?: 0,
                completionTokens = parsed.usageMetadata?.completionTokens ?: 0,
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
        val payload = Http.json.encodeToString(GRequest.serializer(), request.toWire())

        val statement = Http.client.preparePost(
            // alt=sse is required; without it Google streams a JSON array, not SSE.
            "${ctx.baseUrl}/models/${ctx.model.modelId}:streamGenerateContent?alt=sse"
        ) {
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

                var finish = FinishReason.UNKNOWN
                var usage = Usage()
                val calls = mutableListOf<ToolCall>()

                while (true) {
                    val line = channel.readLine() ?: break
                    if (line.isBlank() || !line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue

                    val chunk = runCatching {
                        Http.json.decodeFromString(GResponse.serializer(), data)
                    }.getOrNull() ?: continue

                    chunk.usageMetadata?.let { usage = Usage(it.promptTokens, it.completionTokens) }
                    val candidate = chunk.candidates.firstOrNull() ?: continue
                    candidate.finishReason?.let { finish = finishReasonOf(it) }

                    candidate.content?.parts?.forEach { part ->
                        part.text?.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.Text(it)) }
                        // Google sends a function call whole, not in fragments,
                        // so there is nothing to accumulate here.
                        part.functionCall?.let { calls += it.toDomain(calls.size) }
                    }
                }

                calls.forEach { emit(StreamEvent.Tool(it)) }
                if (calls.isNotEmpty() && finish == FinishReason.UNKNOWN) {
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
        } catch (e: ProviderException) {
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
            Http.json.decodeFromString(GModelList.serializer(), body)
                .models.map { it.name.removePrefix("models/") }
        }.getOrElse { emptyList() }
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private fun ChatRequest.toWire(): GRequest {
        // Tool results carry only a call id, but Gemini identifies a
        // functionResponse by NAME, so recover the name from the call that
        // produced it.
        val nameByCallId = messages
            .flatMap { it.toolCalls }
            .associate { it.id to it.name }

        val system = messages.filter { it.role == Role.SYSTEM }
            .map { it.content }
            .filter { it.isNotBlank() }

        val contents = messages.mapNotNull { message ->
            when (message.role) {
                Role.SYSTEM -> null // hoisted into systemInstruction
                Role.USER -> GContent("user", listOf(GPart(text = message.content)))
                Role.ASSISTANT -> {
                    val parts = buildList {
                        message.content.takeIf { it.isNotBlank() }?.let { add(GPart(text = it)) }
                        message.toolCalls.forEach { call ->
                            add(
                                GPart(
                                    functionCall = GFunctionCall(
                                        name = call.name,
                                        args = runCatching {
                                            Http.json.parseToJsonElement(call.arguments)
                                        }.getOrElse { JsonObject(emptyMap()) },
                                    )
                                )
                            )
                        }
                    }
                    parts.takeIf { it.isNotEmpty() }?.let { GContent("model", it) }
                }
                Role.TOOL -> {
                    val name = message.toolCallId?.let { nameByCallId[it] } ?: message.toolCallId
                    GContent(
                        role = "user",
                        parts = listOf(
                            GPart(
                                functionResponse = GFunctionResponse(
                                    name = name ?: "tool",
                                    // Gemini requires an object here, so wrap
                                    // plain text rather than sending a bare string.
                                    response = wrapToolResult(message.content),
                                )
                            )
                        ),
                    )
                }
            }
        }

        return GRequest(
            contents = contents,
            systemInstruction = system.takeIf { it.isNotEmpty() }
                ?.let { GContent(parts = listOf(GPart(text = it.joinToString("\n\n")))) },
            tools = tools.takeIf { it.isNotEmpty() }?.let { defs ->
                listOf(
                    GTool(
                        defs.map { def ->
                            GFunctionDeclaration(
                                name = def.name,
                                description = def.description,
                                parameters = runCatching {
                                    Http.json.parseToJsonElement(def.parameters)
                                }.getOrNull(),
                            )
                        }
                    )
                )
            },
            generationConfig = if (temperature != null || maxOutputTokens != null) {
                GGenerationConfig(temperature, maxOutputTokens)
            } else {
                null
            },
        )
    }

    private fun wrapToolResult(content: String): JsonElement {
        val parsed = runCatching { Http.json.parseToJsonElement(content) }.getOrNull()
        return if (parsed is JsonObject) parsed
        else JsonObject(mapOf("result" to JsonPrimitive(content)))
    }

    private fun GFunctionCall.toDomain(index: Int) = ToolCall(
        id = "call_${index}_$name",
        name = name,
        arguments = args?.toString() ?: "{}",
    )

    private companion object {
        fun finishReasonOf(raw: String?): FinishReason = when (raw) {
            "STOP" -> FinishReason.STOP
            "MAX_TOKENS" -> FinishReason.LENGTH
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> FinishReason.CONTENT_FILTER
            else -> FinishReason.UNKNOWN
        }
    }
}
