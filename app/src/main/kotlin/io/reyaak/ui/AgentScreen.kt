package io.reyaak.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.reyaak.core.ReyaakCore
import io.reyaak.data.GoogleAuth
import io.reyaak.runtime.AgentService
import kotlinx.coroutines.launch

@Composable
fun AgentScreen(
    core: ReyaakCore,
    onOpenUrl: (String) -> Unit = {},
    /** Start the service, asking for the notification permission first if needed. */
    onStartAgent: () -> Unit = {},
    bottomPadding: Dp = NavBarSpace,
) {
    val t = LocalTokens.current
    val feedback = LocalFeedback.current
    val context = LocalContext.current
    val state by core.agent.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    var showLegend by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Gmail sign-in. The token is asked for silently first; Play Services only
    // shows a consent screen the first time, or after the user revokes it, and
    // that screen is an Activity result like any other.
    val googleAuth = remember(context) { GoogleAuth(context) }
    var pendingGmail by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    val gmailConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val onToken = pendingGmail
        pendingGmail = null
        onToken?.invoke(googleAuth.tokenFrom(result.data))
    }

    var notificationsAllowed by remember { mutableStateOf(notificationsEnabled(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }

    // Both can change while backgrounded (the user visits system settings), so
    // they are re-read on resume rather than cached at first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = notificationsEnabled(context)
                batteryExempt = isBatteryExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 2.dp,
            bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Agent", Modifier.padding(top = 14.dp, bottom = 6.dp)) }

        item {
            Box(Modifier.clip(RoundedCornerShape(22.dp))) {
                Glass(
                    radius = 22.dp,
                    padding = PaddingValues(20.dp),
                    highlight = true,
                    modifier = Modifier.animateContentSize(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(state.running, size = 10.dp)
                        Spacer(Modifier.width(11.dp))
                        Text(
                            if (state.running) "Running" else "Stopped",
                            Modifier.weight(1f),
                            color = t.ink,
                            style = rk(600, 16.0, 1.0, tracking = (-0.01).em),
                        )
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(t.g2)
                                .border(1.dp, t.line, CircleShape)
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showLegend = !showLegend
                                },
                            contentAlignment = Alignment.Center,
                        ) { PhIcon(if (showLegend) Ph.X else Ph.QUESTION, 14.0, t.mut) }
                    }
                    Spacer(Modifier.height(16.dp))
                    GlassDivider()
                    Spacer(Modifier.height(4.dp))
                    DetailRow("Doing now", state.activity, showLegend, HELP_ACTIVITY)
                    DetailRow("Turns answered", state.turnsAnswered.toString(), showLegend, HELP_TURNS)
                    DetailRow("Heartbeats", state.heartbeats.toString(), showLegend, HELP_BEATS)
                }
                // The halo says "this is live" from across the room, which is
                // what a status card is for.
                if (state.running) {
                    val breath = rememberBreath(6000)
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 50.dp, y = (-80).dp)
                            .size(200.dp)
                            .scale(1f + 0.14f * breath)
                            .alpha(0.30f + 0.30f * breath)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF5C8CF0).copy(alpha = 0.26f), Color.Transparent)
                                ),
                                CircleShape,
                            )
                    )
                }
            }
        }

        item {
            if (state.running) {
                RkButton(
                    label = "Stop agent",
                    // Stopping kills the service and drops anything mid-turn,
                    // and it used to happen on the first tap.
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        feedback.confirm(
                            Confirmation(
                                title = "Stop the agent?",
                                body = "Chat stops answering and the foreground service " +
                                    "shuts down. Anything mid-turn is dropped.",
                                cta = "Stop agent",
                                danger = true,
                                run = { AgentService.stop(context) },
                            )
                        )
                    },
                    glyph = Ph.STOP,
                    tone = ButtonTone.NEUTRAL,
                    height = 50.dp,
                    radius = 16.dp,
                    fontSize = 14.5,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                RkButton(
                    label = "Start agent",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStartAgent()
                    },
                    glyph = Ph.PLAY,
                    glyphFill = true,
                    tone = ButtonTone.ACCENT,
                    height = 50.dp,
                    radius = 16.dp,
                    fontSize = 14.5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (!notificationsAllowed) {
            item {
                Advisory(
                    glyph = Ph.BELL_SLASH,
                    text = "Notifications are off, so the agent will run without showing what it is doing.",
                    actionLabel = "Open notification settings",
                    onAction = { openAppNotificationSettings(context) },
                )
            }
        }

        if (!batteryExempt) {
            item {
                Advisory(
                    glyph = Ph.BATTERY_WARNING,
                    text = "Battery optimisation can pause the agent when the screen is off. " +
                        "Exempting Reyaak keeps it running on schedule.",
                    actionLabel = "Allow",
                    onAction = { requestBatteryExemption(context) },
                )
            }
        }

        item { Spacer(Modifier.height(10.dp)) }

        item { SkillsCard(core) }

        item {
            ToolsCard(
                core = core,
                onOpenUrl = onOpenUrl,
                onGmailSignIn = { onToken ->
                    scope.launch {
                        val result = googleAuth.authorize()
                        val consent = result?.pendingIntent
                        when {
                            result == null -> onToken(null)
                            consent != null -> {
                                pendingGmail = onToken
                                gmailConsent.launch(IntentSenderRequest.Builder(consent).build())
                            }
                            // Already granted: no screen, just a fresh token.
                            else -> onToken(result.accessToken)
                        }
                    }
                },
            )
        }

        item {
            FootNote(
                "The agent runs as a foreground service so Android keeps the process " +
                    "alive. It heartbeats, answers turns, and calls the tools above " +
                    "when a question needs them; scheduled work arrives in a later phase.",
                Modifier.padding(top = 4.dp),
            )
        }
    }
}

private const val HELP_ACTIVITY =
    "What the agent is working on this second: listening, routing a turn, " +
        "answering, or how long it has been idle."

private const val HELP_TURNS =
    "Chat turns the agent has completed since it started, successful or failed. " +
        "This is the number that means work got done."

private const val HELP_BEATS =
    "The loop checking in on a timer to prove Android has not frozen the process. " +
        "Useful only for diagnosing that; it is not a measure of activity."

/**
 * One counter, with its explanation folded in.
 *
 * The help text used to be a separate block below all three rows, which meant
 * reading it was a matter of counting down to the right paragraph. Now each
 * number carries its own.
 */
@Composable
internal fun DetailRow(
    label: String,
    value: String,
    showHelp: Boolean = false,
    help: String? = null,
) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, Modifier.weight(1f), color = t.mut, style = rk(400, 13.0, 1.3))
            Spacer(Modifier.width(12.dp))
            MonoText(value, size = 12.5, tint = t.ink)
        }
        AnimatedVisibility(visible = showHelp && help != null) {
            Text(
                help.orEmpty(),
                Modifier.padding(top = 7.dp),
                color = t.faint,
                style = rk(400, 12.0, 1.55),
            )
        }
    }
}

@Composable
internal fun Advisory(
    glyph: String,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val t = LocalTokens.current
    Glass(radius = 16.dp, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            PhIcon(glyph, 16.0, t.accLt, Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(text, color = t.mut, style = rk(400, 12.5, 1.55))
                Spacer(Modifier.height(4.dp))
                RkTextAction(actionLabel, onClick = onAction)
            }
        }
    }
}

// ── Platform state helpers ──────────────────────────────────────────────────

internal fun needsNotificationPermission(alreadyAllowed: Boolean): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !alreadyAllowed

internal fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

internal fun isBatteryExempt(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

internal fun openAppNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Sends the user to the battery-optimisation prompt for this app.
 *
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS names the app, so the user can see
 * exactly what they are exempting. Never triggered automatically, only from an
 * advisory the user chose to act on.
 */
internal fun requestBatteryExemption(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        // Some OEM builds omit this screen; fall back rather than crash on a
        // missing activity.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
