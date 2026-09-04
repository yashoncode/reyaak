package io.reyaak.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.reyaak.core.ReyaakCore
import io.reyaak.data.GoogleAuth
import io.reyaak.runtime.AgentService
import kotlinx.coroutines.launch

@Composable
fun AgentScreen(core: ReyaakCore, onOpenUrl: (String) -> Unit = {}) {
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

    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = granted
        AgentService.start(context)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = io.reyaak.ui.NavBarSpace),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (state.running) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(10.dp),
                        ) {}
                        Spacer(Modifier.size(10.dp))
                        Text(
                            if (state.running) "Running" else "Stopped",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        // A plain glyph rather than an icon dependency: this
                        // screen ships no icon set, and a Material icons
                        // artifact for one question mark is not worth it.
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .size(28.dp)
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showLegend = !showLegend
                                },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    if (showLegend) "\u00d7" else "?",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(14.dp))
                    DetailRow("Doing now", state.activity)
                    Spacer(Modifier.height(8.dp))
                    DetailRow("Turns answered", state.turnsAnswered.toString())
                    Spacer(Modifier.height(8.dp))
                    DetailRow("Heartbeats", state.heartbeats.toString())

                    if (showLegend) {
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(12.dp))
                        Legend(
                            "Doing now",
                            "What the agent is working on this second: listening, " +
                                "routing a turn, answering, or how long it has been idle.",
                        )
                        Spacer(Modifier.height(10.dp))
                        Legend(
                            "Turns answered",
                            "Chat turns the agent has completed since it started, " +
                                "successful or failed. This is the number that means work got done.",
                        )
                        Spacer(Modifier.height(10.dp))
                        Legend(
                            "Heartbeats",
                            "The loop checking in on a timer to prove Android has not " +
                                "frozen the process. Useful only for diagnosing that; it is " +
                                "not a measure of activity.",
                        )
                    }
                }
            }
        }

        item {
            if (state.running) {
                OutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        AgentService.stop(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop agent") }
            } else {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (needsNotificationPermission(notificationsAllowed)) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            AgentService.start(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start agent") }
            }
        }

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
            Text(
                "The agent runs as a foreground service so Android keeps the process " +
                    "alive. It heartbeats, answers turns, and calls the tools above " +
                    "when a question needs them; scheduled work arrives in a later phase.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!notificationsAllowed) {
            item {
                Advisory(
                    text = "Notifications are off, so the agent will run without showing what it is doing.",
                    actionLabel = "Open notification settings",
                    onAction = { openAppNotificationSettings(context) },
                )
            }
        }

        if (!batteryExempt) {
            item {
                Advisory(
                    text = "Battery optimisation can pause the agent when the screen is off. " +
                        "Exempting Reyaak keeps it running on schedule.",
                    actionLabel = "Allow",
                    onAction = { requestBatteryExemption(context) },
                )
            }
        }
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One labelled explanation inside the help panel. */
@Composable
private fun Legend(label: String, text: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun Advisory(text: String, actionLabel: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
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
