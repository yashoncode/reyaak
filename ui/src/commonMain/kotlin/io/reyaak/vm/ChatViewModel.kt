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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.reyaak.ui.strategyLabel

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
    /** The model the chain would try first, short enough for a chip. */
    val modelShort: String = "no key",
    /** Which strategy picked it, so the chip says why as well as what. */
    val routerNote: String = "",
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

    /** The turn in flight, kept so the composer's stop button has something to cancel. */
    private var turn: Job? = null

    /**
     * What the composer's chip shows: the chain leader and the strategy.
     *
     * Its own flow rather than another branch of the transcript combine, because
     * previewing the chain walks every routable model and the transcript emits
     * on every streamed token.
     */
    private val chip = core.configStore.settings
        .map { settings ->
            val leader = runCatching { core.llm.preview() }.getOrNull()
                ?.candidates?.firstOrNull()?.model
            val label = leader?.key?.substringAfterLast('/') ?: "no key"
            label to strategyLabel(settings.strategy).lowercase() + " routing"
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "no key" to "")

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
                .combine(chip) { state, (model, note) ->
                    state.copy(modelShort = model, routerNote = note)
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

        turn = viewModelScope.launch {
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

    /**
     * Cancel the turn in flight.
     *
     * The engine persists whatever text already arrived on its way out, so this
     * keeps the half-answer rather than discarding it.
     */
    fun stop() {
        turn?.cancel()
        turn = null
        pending.value = null
    }

    /**
     * Send the last user message again.
     *
     * The failed assistant row stays in the transcript: it is what happened, and
     * hiding it would make the retry look like the first attempt.
     */
    fun retryLast() {
        if (pending.value != null) return
        val last = _state.value.transcript.lastOrNull { it.fromUser } ?: return
        send(last.content)
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
