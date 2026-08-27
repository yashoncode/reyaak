package io.reyaak.runtime

import android.content.Context

/**
 * What the agent is doing right now.
 *
 * [ticks] is the number of completed loop iterations. In phase 1 an iteration is
 * just a heartbeat; the agent loop takes its place in phase 2 without changing
 * anything the UI or the notification reads.
 */
/**
 * The Android-only half of the agent's lifecycle: whether the user wants it
 * running, remembered across process death and reboot.
 *
 * The run STATE itself moved to `core.agent` ([io.reyaak.core.agent.AgentStatus])
 * once the chat engine started gating on it, one source of truth, shared with
 * the UI and with an eventual iOS host. What is left here is genuinely
 * platform-specific and has no business in :core.
 */
object AgentRuntime {

    // ── Intent to run, across process death and reboot ──────────────────────
    // Deliberately SharedPreferences rather than DataStore: BootReceiver reads
    // this on the main thread inside onReceive, where a suspending read has
    // nowhere to suspend. One boolean does not justify the ceremony.

    private const val PREFS = "reyaak.runtime"
    private const val KEY_SHOULD_RUN = "should_run"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when the user last left the agent running, so boot should resume it. */
    fun wasRunning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOULD_RUN, false)

    fun setShouldRun(context: Context, shouldRun: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOULD_RUN, shouldRun).apply()
    }
}
