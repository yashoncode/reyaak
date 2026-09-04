package io.reyaak.core.memory

/**
 * Which conversation the agent is serving right now.
 *
 * The tool list is built once, at startup, but the conversation changes every
 * turn. Rather than rebuild the registry per turn, or thread a conversation id
 * through the tool contract where every tool that does not care would still have
 * to declare it, the engine sets this before a turn and the one tool that needs
 * it reads it.
 *
 * Single-threaded by construction: one agent serves one turn at a time, and the
 * engine writes this on the same coroutine that then runs the turn.
 */
class TurnScope {
    var conversationId: Long? = null
}
