package io.reyaak.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the agent back after a reboot or an app update, but only if the user
 * had left it running. Starting unconditionally would resurrect an agent the
 * user deliberately stopped.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val relevant = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!relevant) return
        if (!AgentRuntime.wasRunning(context)) return

        // targetSdk is held at 34 specifically so this is allowed. On API 35+ a
        // dataSync foreground service cannot be started from BOOT_COMPLETED, and
        // this would throw ForegroundServiceStartNotAllowedException, at which
        // point the restore has to move to a WorkManager job instead.
        AgentService.start(context)
    }
}
