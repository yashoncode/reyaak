package io.reyaak.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.reyaak.MainActivity
import io.reyaak.ReyaakApp
import io.reyaak.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground host for the agent loop.
 *
 * This class owns the process lifetime and the notification, and nothing else,
 * no agent logic, no provider knowledge. In phase 2 the heartbeat in [runLoop]
 * is replaced by the real agent loop; the service around it does not change.
 */
class AgentService : Service() {

    /** The run state the whole app reads, including the chat gate. */
    private val agent by lazy { (application as ReyaakApp).core.agent }

    /** Curated on idle, so the store does not grow without bound. */
    private val memory by lazy { (application as ReyaakApp).core.memory }

    /** Curated the same way: a procedure the agent wrote and never used again. */
    private val skills by lazy { (application as ReyaakApp).core.skills }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us after START_STICKY, which
        // is exactly the case where we do want to come back up running.
        if (intent?.action == ACTION_STOP) {
            stopAgent()
            return START_NOT_STICKY
        }
        startAgent()
        return START_STICKY
    }

    private fun startAgent() {
        ensureChannel(this)

        // Must happen promptly after the start request or the system kills us.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Listening", ticks = 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (loop?.isActive == true) return

        AgentRuntime.setShouldRun(this, true)
        agent.started(System.currentTimeMillis())
        loop = scope.launch { runLoop() }
    }

    /**
     * The heartbeat, and the one thing the agent does unprompted.
     *
     * Maintenance is triggered by inactivity rather than by a schedule. A
     * scheduling daemon would mean WorkManager, a second execution path, and a
     * job that fires while the user is mid-sentence; a quiet stretch is both a
     * better signal that now is a good time and something this loop already
     * knows about.
     *
     * Uses [SystemClock.elapsedRealtime] for elapsed time because it keeps
     * counting through deep sleep, unlike uptimeMillis, and cannot jump when the
     * wall clock is corrected.
     */
    private suspend fun runLoop() {
        val startedAt = SystemClock.elapsedRealtime()
        var idleSince = startedAt
        var turnsSeen = agent.state.value.turnsAnswered
        // One pass per quiet stretch. Without this the curator would re-run
        // every five seconds for as long as the phone sat on the table.
        var curatedThisIdle = false

        // The current coroutine, not `scope`: cancelling the loop job alone must
        // end this loop, and scope.isActive would still be true in that case.
        while (currentCoroutineContext().isActive) {
            delay(TICK_INTERVAL_MS)
            val nowElapsed = SystemClock.elapsedRealtime()

            val turns = agent.state.value.turnsAnswered
            if (turns != turnsSeen) {
                turnsSeen = turns
                idleSince = nowElapsed
                curatedThisIdle = false
            }

            val idleFor = nowElapsed - idleSince
            if (!curatedThisIdle && idleFor >= MAINTENANCE_AFTER_MS) {
                curatedThisIdle = true
                curate()
            }

            val alive = nowElapsed - startedAt
            val activity = "Idle for ${formatElapsed(alive)}"
            agent.heartbeat(activity)
            notifyState(activity, agent.state.value.turnsAnswered)
        }
    }

    /**
     * The cheap deterministic curation pass.
     *
     * Deliberately not the model-driven one: this runs unattended, so it does
     * only what a SQL predicate can justify. Asking a model which memories are
     * worth keeping costs a turn and can be wrong, which is why it stays an
     * explicit action rather than something that happens while nobody is
     * looking.
     *
     * Failures are swallowed on purpose. Maintenance is a nicety, and an
     * exception here would take the heartbeat down with it, which is the one
     * thing the loop exists to keep running.
     */
    private suspend fun curate() {
        val memories = runCatching { memory.archiveStale() }.getOrNull().orEmpty()
        if (memories.isNotEmpty()) agent.heartbeat("Archived ${memories.size} stale memories")
        val procedures = runCatching { skills.archiveStale() }.getOrNull().orEmpty()
        if (procedures.isNotEmpty()) agent.heartbeat("Archived ${procedures.size} unused skills")
    }

    private fun stopAgent() {
        AgentRuntime.setShouldRun(this, false)
        loop?.cancel()
        loop = null
        agent.stopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        // If the system killed us rather than the user stopping us, leave
        // shouldRun alone so boot and START_STICKY can bring the agent back.
        if (agent.state.value.running) agent.stopped()
        super.onDestroy()
    }

    // ── Notification ────────────────────────────────────────────────────────

    private fun notifyState(activity: String, ticks: Int) {
        // Posting is a no-op without the runtime permission, which is fine: the
        // service still runs, the user just does not see it. Guarding avoids a
        // SecurityException on API 33+ when the permission was refused.
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        NotificationManagerCompat.from(this)
            .notify(NOTIFICATION_ID, buildNotification(activity, ticks))
    }

    private fun buildNotification(activity: String, ticks: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(activity)
            .setSubText(if (ticks > 0) "$ticks cycles" else null)
            .setSmallIcon(R.drawable.ic_stat_reyaak)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "reyaak.agent"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "io.reyaak.action.STOP"

        private const val TICK_INTERVAL_MS = 5_000L

        /**
         * How long a quiet stretch has to be before maintenance runs.
         *
         * Long enough that it never fires between two messages in one
         * conversation, short enough that a phone left alone overnight gets
         * tidied before morning.
         */
        private const val MAINTENANCE_AFTER_MS = 30 * 60 * 1000L

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_agent),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_agent_desc)
                    setShowBadge(false)
                }
            )
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AgentService::class.java))
        }

        /**
         * Safe to call when the agent is not running. startService on a stopped
         * service throws IllegalStateException on API 26+ if the caller is in
         * the background, which the notification stop action can be.
         */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AgentService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        internal fun formatElapsed(ms: Long): String {
            val totalSeconds = ms / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return when {
                hours > 0 -> "%dh %02dm".format(hours, minutes)
                minutes > 0 -> "%dm %02ds".format(minutes, seconds)
                else -> "${seconds}s"
            }
        }
    }
}
