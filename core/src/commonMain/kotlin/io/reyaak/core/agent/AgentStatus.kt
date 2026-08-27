package io.reyaak.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the agent is doing right now.
 *
 * The two counters are deliberately separate because they answer different
 * questions. [turnsAnswered] is work the user asked for and can recognise.
 * [heartbeats] is the loop proving it is still alive, which matters when
 * diagnosing whether the OS has frozen the process, so it is diagnostics,
 * not a headline number.
 */
data class AgentState(
    val running: Boolean = false,
    val startedAtMs: Long? = null,
    val heartbeats: Int = 0,
    val turnsAnswered: Int = 0,
    val activity: String = "Stopped",
)

/**
 * The agent's run state, and the gate on whether it may answer.
 *
 * This lives in :core rather than the Android host because two things now
 * depend on it that are not Android: the chat engine refuses a turn when the
 * agent is stopped, and the shared UI renders the run state. The host still
 * owns the *process* (a foreground service on Android, a background task on
 * iOS) and drives this from there, so what is platform-specific stays in the
 * host, and what the agent and the UI agree on lives here.
 */
class AgentStatus {

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value.running

    fun started(nowMs: Long) = _state.update {
        it.copy(
            running = true,
            startedAtMs = nowMs,
            heartbeats = 0,
            turnsAnswered = 0,
            activity = "Listening",
        )
    }

    /** The loop proving it is still alive. */
    fun heartbeat(activity: String) = _state.update {
        it.copy(heartbeats = it.heartbeats + 1, activity = activity)
    }

    /** A turn the agent finished, answered or failed. */
    fun turnDone(activity: String) = _state.update {
        it.copy(turnsAnswered = it.turnsAnswered + 1, activity = activity)
    }

    /** In-progress work, which advances no counter. */
    fun busy(activity: String) = _state.update {
        if (it.running) it.copy(activity = activity) else it
    }

    fun stopped() = _state.update {
        AgentState(
            running = false,
            startedAtMs = null,
            heartbeats = it.heartbeats,
            turnsAnswered = it.turnsAnswered,
            activity = "Stopped",
        )
    }
}

/**
 * Thrown when something asks the agent to work while it is stopped.
 *
 * The guard lives in the engine rather than only in the UI: the UI disables the
 * composer, but the engine is what every caller routes through, so this is the
 * one place that cannot be bypassed.
 */
class AgentNotRunningException : IllegalStateException(
    "The agent is not running. Start it on the Agent tab and try again."
)
