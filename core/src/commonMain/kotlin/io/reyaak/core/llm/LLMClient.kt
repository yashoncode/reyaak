package io.reyaak.core.llm

import io.reyaak.router.Attempt
import io.reyaak.router.Router
import io.reyaak.router.RouterException
import io.reyaak.router.model.ChatMessage
import io.reyaak.router.model.ChatRequest
import io.reyaak.router.model.Role
import io.reyaak.router.model.ToolCall
import io.reyaak.router.model.Served
import io.reyaak.router.model.StreamEvent
import io.reyaak.router.model.ToolDefinition
import io.reyaak.router.model.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What one model turn produces, in the vocabulary the agent cares about. */
sealed interface TurnEvent {
    data class Delta(val text: String) : TurnEvent

    data class Complete(
        val text: String,
        val served: Served,
        val usage: Usage,
    ) : TurnEvent

    /** A tool call the model asked for, so the UI can say what is happening. */
    data class ToolStarted(val name: String) : TurnEvent

    data class ToolFinished(val name: String) : TurnEvent

    /**
     * The turn failed. Carries the attempt trace so the UI can show *why*
     * rather than a bare "something went wrong".
     */
    data class Failed(
        val message: String,
        val attempts: List<Attempt>,
        /** True when text had already been delivered before the failure. */
        val partial: Boolean,
    ) : TurnEvent
}

/**
 * The agent's single door to a model.
 *
 * This exists to keep the boundary honest: the agent loop, memory, skills, and
 * everything else in :core talk to LLMClient and never to [Router]. That is what
 * makes "the agent contains no provider-specific logic" a structural property
 * rather than a habit, there is no provider type reachable from here.
 */
class LLMClient(private val router: Router) {

    /**
     * One turn, including any tool calls it needs on the way.
     *
     * The loop lives here rather than in the caller because a tool round is not
     * a new turn: the user asked one question, and the model calling a tool and
     * reading the result is how it answers. The caller sees text, tool notices,
     * and one Complete.
     *
     * [toolRunner] is what actually executes a call. Passing it in keeps this
     * class free of any tool implementation, the same way it is free of any
     * provider: it knows the shape of a tool call and nothing about what tools
     * exist.
     */
    fun stream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        temperature: Double? = null,
        maxOutputTokens: Int? = null,
        pinnedModel: String? = null,
        toolRunner: (suspend (ToolCall) -> String)? = null,
    ): Flow<TurnEvent> = flow {
        val history = messages.toMutableList()
        val text = StringBuilder()
        var usage = Usage()
        var served: Served? = null
        var round = 0

        while (true) {
            val request = ChatRequest(
                messages = history,
                // Tools are dropped once the budget is spent, which is what
                // forces the model to answer with what it already has instead
                // of calling forever.
                tools = if (round < MAX_TOOL_ROUNDS) tools else emptyList(),
                temperature = temperature,
                maxOutputTokens = maxOutputTokens,
                model = pinnedModel,
            )

            val calls = mutableListOf<ToolCall>()
            val roundText = StringBuilder()

            try {
                router.stream(request).collect { event ->
                    when (event) {
                        is StreamEvent.Text -> {
                            roundText.append(event.delta)
                            text.append(event.delta)
                            emit(TurnEvent.Delta(event.delta))
                        }
                        is StreamEvent.Tool -> calls += event.call
                        is StreamEvent.Done -> {
                            served = event.served
                            usage = Usage(
                                promptTokens = usage.promptTokens + event.usage.promptTokens,
                                completionTokens = usage.completionTokens + event.usage.completionTokens,
                            )
                        }
                    }
                }
            } catch (e: RouterException) {
                emit(
                    TurnEvent.Failed(
                        message = e.message ?: "the request failed",
                        attempts = e.attempts,
                        partial = text.isNotEmpty(),
                    )
                )
                return@flow
            }

            if (calls.isEmpty() || toolRunner == null) {
                val finalServed = served
                if (finalServed == null) {
                    emit(
                        TurnEvent.Failed(
                            "the provider closed the stream without answering",
                            emptyList(),
                            text.isNotEmpty(),
                        )
                    )
                    return@flow
                }
                emit(
                    TurnEvent.Complete(
                        text = text.toString(),
                        served = finalServed,
                        // Providers routinely omit usage on streamed responses,
                        // so fall back to the estimator rather than reporting a
                        // confident zero.
                        usage = if (usage.totalTokens == 0) {
                            Usage(
                                promptTokens = Router.estimateTokens(request),
                                completionTokens = text.length / 4,
                            )
                        } else {
                            usage
                        },
                    )
                )
                return@flow
            }

            // The assistant turn that requested the calls has to go back in the
            // history verbatim: a tool result with no matching call is a 400 on
            // every provider that validates it.
            history += ChatMessage(
                role = Role.ASSISTANT,
                content = roundText.toString(),
                toolCalls = calls,
            )
            for (call in calls) {
                emit(TurnEvent.ToolStarted(call.name))
                val result = toolRunner(call)
                history += ChatMessage(
                    role = Role.TOOL,
                    content = result,
                    toolCallId = call.id,
                )
                emit(TurnEvent.ToolFinished(call.name))
            }
            round++
        }
    }

    /** Candidate chain for the current settings, for the Router screen. */
    suspend fun preview(messages: List<ChatMessage> = emptyList()) =
        router.select(ChatRequest(messages = messages))

    suspend fun listModels(platform: String) = router.listModels(platform)

    private companion object {
        /**
         * How many times the model may call tools before it has to answer.
         *
         * Six rather than four since the agent also writes down what it learned:
         * search, read, read, then remember and record a procedure is a normal
         * shape for one turn now, and cutting the tools off before the writing
         * rounds would drop exactly the part that makes the next turn cheaper.
         * Still low enough that a model stuck in a loop costs seconds, not a quota.
         */
        const val MAX_TOOL_ROUNDS = 6
    }
}
