package io.reyaak.core.chat

import io.reyaak.core.agent.AgentNotRunningException
import io.reyaak.core.agent.AgentStatus
import io.reyaak.core.data.ChatDao
import io.reyaak.core.data.MessageEntity
import io.reyaak.core.llm.LLMClient
import io.reyaak.core.memory.MemoryStore
import io.reyaak.core.memory.TurnScope
import io.reyaak.core.persona.Persona
import io.reyaak.core.skills.SkillStore
import io.reyaak.core.tools.ToolRegistry
import io.reyaak.core.llm.TurnEvent
import io.reyaak.router.model.ChatMessage
import io.reyaak.router.model.Role
import io.reyaak.router.time.epochMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * One conversational turn, persisted.
 *
 * This is the seed of the agent loop rather than a separate chat feature: in
 * phase 2 the same method gains tool dispatch and iterates, and memory and
 * skills join [buildContext]. Keeping the persistence and provenance here means
 * that growth does not change the UI contract.
 */
class ChatEngine(
    private val dao: ChatDao,
    private val llm: LLMClient,
    private val agent: AgentStatus,
    /** Read per turn, so editing the persona takes effect on the next message. */
    private val persona: () -> Persona = { Persona() },
    private val tools: ToolRegistry? = null,
    private val skills: SkillStore? = null,
    private val memory: MemoryStore? = null,
    /** Set per turn so a memory the agent writes records where it came from. */
    private val turn: TurnScope? = null,
    private val now: () -> Long = ::epochMillis,
) {

    fun observeConversations() = dao.observeConversations()

    fun observeMessages(conversationId: Long) = dao.observeMessages(conversationId)

    fun observeUsage() = dao.observeUsage()

    suspend fun startConversation(): Long = dao.startConversation("New conversation", now())

    suspend fun currentOrNewConversation(): Long =
        dao.mostRecentConversation()?.id ?: startConversation()

    suspend fun deleteConversation(id: Long) = dao.deleteConversation(id)

    /**
     * Send a message and stream the reply.
     *
     * The user's message is persisted BEFORE the model is called, so a crash or
     * a failed turn never loses what they typed. A failure is persisted too,
     * as a message with `error` set: a turn that went wrong is part of the
     * transcript, not an absence in it.
     */
    fun send(conversationId: Long, userText: String): Flow<TurnEvent> = flow {
        // The agent answers, so a stopped agent has nothing to say. Guarding
        // here rather than only in the UI is what makes that a rule instead of
        // a convention: every caller reaches a turn through this method.
        if (!agent.isRunning) throw AgentNotRunningException()

        val at = now()
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = Role.USER.name.lowercase(),
                content = userText,
                createdAt = at,
            )
        )
        dao.touchConversation(conversationId, at)

        // First real message doubles as the conversation title.
        val history = dao.messagesOf(conversationId)
        if (history.count { it.role == Role.USER.name.lowercase() } == 1) {
            dao.renameConversation(conversationId, userText.take(60).trim().ifBlank { "New conversation" })
        }

        turn?.conversationId = conversationId
        val context = buildContext(history)
        val reply = StringBuilder()
        // Whether a terminal event already wrote the assistant row. Cancellation
        // is the third way this turn can end, and it is the only one that would
        // otherwise leave a half-arrived reply unpersisted.
        var settled = false

        agent.busy("Routing a turn")

        try {
            llm.stream(
                messages = context,
                // Read per turn: a tool the user enables mid-conversation is
                // available on the next message with nothing to restart.
                tools = tools?.definitions() ?: emptyList(),
                toolRunner = tools?.let { registry -> { call -> registry.run(call) } },
            ).collect { event ->
                when (event) {
                    is TurnEvent.ToolStarted -> {
                        agent.busy("Running ${event.name}")
                        emit(event)
                    }
                    is TurnEvent.ToolFinished -> emit(event)
                    is TurnEvent.Delta -> {
                        if (reply.isEmpty()) agent.busy("Answering")
                        reply.append(event.text)
                        emit(event)
                    }
                    is TurnEvent.Complete -> {
                        settled = true
                        dao.insertMessage(
                            MessageEntity(
                                conversationId = conversationId,
                                role = Role.ASSISTANT.name.lowercase(),
                                content = event.text,
                                createdAt = now(),
                                platform = event.served.providerId,
                                modelId = event.served.modelId,
                                promptTokens = event.usage.promptTokens,
                                completionTokens = event.usage.completionTokens,
                                latencyMs = event.served.latencyMs,
                                attempts = event.served.attempts,
                            )
                        )
                        dao.touchConversation(conversationId, now())
                        agent.turnDone("Answered via ${event.served.providerId}")
                        emit(event)
                    }
                    is TurnEvent.Failed -> {
                        settled = true
                        dao.insertMessage(
                            MessageEntity(
                                conversationId = conversationId,
                                role = Role.ASSISTANT.name.lowercase(),
                                // Keep whatever text did arrive: a cut-off answer is
                                // still worth more to the reader than nothing.
                                content = reply.toString(),
                                createdAt = now(),
                                error = event.message,
                            )
                        )
                        dao.touchConversation(conversationId, now())
                        agent.turnDone("Turn failed")
                        emit(event)
                    }
                }
            }
        } finally {
            // Stopping a turn is the third way it can end. Cancellation
            // unwinds before any terminal event arrives, so without this the
            // text already on screen would be dropped on the way out.
            if (!settled && reply.isNotEmpty()) withContext(NonCancellable) {
                dao.insertMessage(
                    MessageEntity(
                        conversationId = conversationId,
                        role = Role.ASSISTANT.name.lowercase(),
                        content = reply.toString(),
                        createdAt = now(),
                    )
                )
                dao.touchConversation(conversationId, now())
                agent.turnDone("Turn stopped")
            }
        }
    }

    /**
     * Assemble the prompt.
     *
     * Sections in a fixed order with an explicit budget, trimmed tail-first so
     * the turn degrades instead of failing when the budget binds. Right now the
     * only sections are the system prompt and history; memory, the user profile,
     * and activated skills slot in ahead of history in phase 2.
     */
    private suspend fun buildContext(history: List<MessageEntity>): List<ChatMessage> {
        // The persona is appended to the base prompt rather than replacing it:
        // the rules above it are what keep the agent honest about which model is
        // answering, and a persona must not be able to switch those off.
        // The tool note is added only when tools are actually offered: telling a
        // model it has tools it was not given is how you get invented calls.
        val toolNote = if (tools?.definitions()?.isNotEmpty() == true) TOOL_PROMPT else null
        val system = ChatMessage(
            role = Role.SYSTEM,
            // Persona last, so the user's own voice wins over a skill telling
            // the agent how to sound.
            // Memory before skills, skills before persona. Memory is what is
            // true, a skill is how to work, and the persona is how to sound: an
            // instruction that contradicts a fact should lose, and the user's
            // own voice should still win over both.
            content = listOfNotNull(
                SYSTEM_PROMPT,
                toolNote,
                memory?.promptSection(),
                skills?.promptSection(),
                persona().promptSection(),
            )
                .joinToString(SECTION_GAP),
        )

        val turns = history
            .filter { it.error == null && it.content.isNotBlank() }
            .map {
                ChatMessage(
                    role = when (it.role) {
                        "user" -> Role.USER
                        "assistant" -> Role.ASSISTANT
                        "tool" -> Role.TOOL
                        else -> Role.USER
                    },
                    content = it.content,
                )
            }

        // Keep the newest turns that fit, then restore chronological order.
        val kept = ArrayDeque<ChatMessage>()
        var budget = HISTORY_TOKEN_BUDGET
        for (message in turns.asReversed()) {
            val cost = message.content.length / 4 + 4
            if (cost > budget && kept.isNotEmpty()) break
            kept.addFirst(message)
            budget -= cost
        }

        return listOf(system) + kept
    }

    private companion object {
        /**
         * Conservative, because it must fit the SMALLEST context window the
         * router might pick. A model with a bigger window simply gets a shorter
         * prompt than it could take, which costs nothing; overshooting would
         * exclude every small model from the candidate set.
         */
        const val HISTORY_TOKEN_BUDGET = 6_000

        /** Blank line between prompt sections. */
        const val SECTION_GAP = "\n\n"

        val TOOL_PROMPT = """
            You have tools. Use them instead of guessing whenever a question
            turns on something current, specific, or checkable: search first,
            then read the page that looks right. Say what you found and where
            it came from. If a tool fails, say so rather than filling the gap
            with a plausible answer.

            You also have a memory that survives this conversation. Write to it
            when you learn something that will still be true next week: a
            preference, a decision, how something is set up, a name for a thing.
            Do not write down the answer to the question you were just asked, or
            anything the user is clearly telling you only for now. One
            self-contained sentence per memory, stated as fact, with no
            reference to this conversation.

            Search your memory when a question leans on something you were told
            before and cannot see in front of you. What you already know about
            the user is given to you above; you do not need to search for that.
        """.trimIndent()

        val SYSTEM_PROMPT = REYAAK_SYSTEM_PROMPT
    }
}
