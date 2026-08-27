package io.reyaak.router.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Who produced a message. Wire names match the OpenAI vocabulary. */
@Serializable
enum class Role {
    @SerialName("system") SYSTEM,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("tool") TOOL,
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    /** Raw JSON arguments. Left as text because providers stream it in fragments. */
    val arguments: String,
)

@Serializable
data class ChatMessage(
    val role: Role,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    /** Set on TOOL messages to say which call this answers. */
    val toolCallId: String? = null,
)

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments object. */
    val parameters: String,
)

/**
 * What a caller needs from a model. The router turns this into a candidate
 * filter, so anything declared here narrows the catalog before scoring.
 */
data class ChatRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    /** Pin a specific model, bypassing scoring. Still subject to health checks. */
    val model: String? = null,
    val needsVision: Boolean = false,
    val needsJsonMode: Boolean = false,
)

@Serializable
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

/** Why the model stopped. */
enum class FinishReason { STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, UNKNOWN }

/** Which provider and model actually served a request, after fallback. */
data class Served(
    val providerId: String,
    val modelId: String,
    val attempts: Int,
    val latencyMs: Long,
)

data class ChatResponse(
    val message: ChatMessage,
    val finishReason: FinishReason,
    val usage: Usage,
    val served: Served,
)

/**
 * Normalized streaming events. Every adapter emits this shape, so nothing
 * downstream of the router needs to know how a given provider frames SSE.
 */
sealed interface StreamEvent {
    /** A chunk of assistant text. */
    data class Text(val delta: String) : StreamEvent

    /**
     * A tool call, already reassembled. Adapters accumulate index-keyed
     * argument fragments and only emit once the call is complete, so callers
     * never see partial JSON.
     */
    data class Tool(val call: ToolCall) : StreamEvent

    /** Terminal event. Carries the totals a provider reports at the end. */
    data class Done(
        val finishReason: FinishReason,
        val usage: Usage,
        val served: Served,
    ) : StreamEvent
}
