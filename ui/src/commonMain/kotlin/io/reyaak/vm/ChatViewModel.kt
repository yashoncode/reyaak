package io.reyaak.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.reyaak.core.ReyaakCore
import io.reyaak.core.agent.AgentNotRunningException
import io.reyaak.core.data.ConversationSummary
import io.reyaak.core.data.MessageEntity
import io.reyaak.core.llm.TurnEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One rendered row of the transcript. */
data class TranscriptEntry(
    val id: String,
    val fromUser: Boolean,
    val content: String,
    val streaming: Boolean = false,
    val error: String? = null,
    /** Provider, model, latency, the router's answer to "who served this?" */
    val footnote: String? = null,
    /** A tool the agent is running right now, while this entry streams. */
    val toolNote: String? = null,
)

data class ChatUiState(
    val transcript: List<TranscriptEntry> = emptyList(),
    val streaming: Boolean = false,
    val hasKeys: Boolean = false,
    /** The agent answers, so chat is only live while it is running. */
    val agentRunning: Boolean = false,
    val agentActivity: String = "Stopped",
) {
    val canSend: Boolean get() = agentRunning && hasKeys && !streaming
}

class ChatViewModel(private val core: ReyaakCore) : ViewModel() {

    private val conversationId = MutableStateFlow<Long?>(null)

    /** The reply currently arriving, held here so it survives recomposition. */
    private val pending = MutableStateFlow<TranscriptEntry?>(null)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Every conversation, newest first, for the history screen. */
    val history: StateFlow<List<ConversationSummary>> = core.chat.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Which conversation the transcript is showing, so history can mark it. */
    val activeConversation: StateFlow<Long?> = conversationId.asStateFlow()

    init {
        viewModelScope.launch {
            conversationId.value = core.chat.currentOrNewConversation()
        }

        viewModelScope.launch {
            conversationId
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList())
                    else core.chat.observeMessages(id)
                }
                .combine(pending) { stored, streamingEntry ->
                    stored.map { it.toEntry() } + listOfNotNull(streamingEntry)
                }
                .combine(core.configStore.settings) { transcript, settings ->
                    transcript to settings.configuredPlatforms().isNotEmpty()
                }
                .combine(core.agent.state) { (transcript, hasKeys), agent ->
                    ChatUiState(
                        transcript = transcript,
                        streaming = pending.value != null,
                        hasKeys = hasKeys,
                        agentRunning = agent.running,
                        agentActivity = agent.activity,
                    )
                }
                .collect { _state.value = it }
        }
    }

    fun send(text: String) {
        val id = conversationId.value ?: return
        if (pending.value != null) return

        // Show the assistant bubble immediately, before the first byte arrives,
        // so the send feels acknowledged even while the router is still choosing.
        pending.value = TranscriptEntry(
            id = "pending",
            fromUser = false,
            content = "",
            streaming = true,
        )

        viewModelScope.launch {
            try {
                core.chat.send(id, text).collect { event ->
                    when (event) {
                        // Tool activity belongs in the bubble that is waiting:
                        // "running web_search" is the answer to why nothing has
                        // arrived yet.
                        is TurnEvent.ToolStarted -> pending.update {
                            it?.copy(toolNote = event.name)
                        }
                        is TurnEvent.ToolFinished -> pending.update {
                            it?.copy(toolNote = null)
                        }
                        is TurnEvent.Delta -> pending.update { current ->
                            current?.copy(content = current.content + event.text)
                        }
                        is TurnEvent.Complete,
                        is TurnEvent.Failed,
                            -> {
                            // The engine has persisted the final message, so drop
                            // the placeholder and let the database be the single
                            // source of truth. Keeping both would double the reply.
                            pending.value = null
                        }
                    }
                }
                pending.value = null
            } catch (e: AgentNotRunningException) {
                // Not a crash and not silence: the reason lands in the
                // transcript, where the user is already looking.
                pending.value = TranscriptEntry(
                    id = "pending",
                    fromUser = false,
                    content = "",
                    error = e.message,
                )
            }
        }
    }

    /** Switch the transcript to an existing conversation. */
    fun openConversation(id: Long) {
        if (conversationId.value == id) return
        pending.value = null
        conversationId.value = id
    }

    /**
     * Delete a conversation. Deleting the open one lands on the next most recent,
     * or a new empty one, rather than leaving the transcript pointing at nothing.
     */
    fun deleteConversation(id: Long) = viewModelScope.launch {
        core.chat.deleteConversation(id)
        if (conversationId.value == id) {
            pending.value = null
            conversationId.value = core.chat.currentOrNewConversation()
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            pending.value = null
            conversationId.value = core.chat.startConversation()
        }
    }

    private fun MessageEntity.toEntry(): TranscriptEntry {
        val provenance = if (platform != null && error == null) {
            buildString {
                append(platform)
                modelId?.let { append(" · ").append(it.substringAfterLast('/')) }
                if (latencyMs > 0) append(" · ").append(latencyMs).append("ms")
                if (attempts > 1) append(" · ").append(attempts).append(" attempts")
                val tokens = promptTokens + completionTokens
                if (tokens > 0) append(" · ").append(tokens).append(" tok")
            }
        } else {
            null
        }
        return TranscriptEntry(
            id = "m$id",
            fromUser = role == "user",
            content = content,
            error = error,
            footnote = provenance,
        )
    }
}
