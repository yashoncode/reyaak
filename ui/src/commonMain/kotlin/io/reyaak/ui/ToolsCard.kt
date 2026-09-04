package io.reyaak.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reyaak.core.ReyaakCore
import io.reyaak.core.tools.Gmail
import io.reyaak.core.tools.guessImapHost
import io.reyaak.core.tools.imapNeedsBridge
import io.reyaak.core.tools.imapServer
import kotlinx.coroutines.launch

/**
 * What the agent can do besides talk.
 *
 * A tool is only offered to the model when it is switched on AND can actually
 * run: a declared tool that answers "not configured" wastes a whole round trip
 * to say so, which is why the switches and the backend live in one card.
 */
@Composable
fun ToolsCard(
    core: ReyaakCore,
    onOpenUrl: (String) -> Unit,
    /**
     * Ask the OS for a Gmail access token, consent screen and all, and hand
     * back the token or null. A lambda for the same reason file picking is one:
     * sign-in is an Activity result, and this module has no Activity.
     */
    onGmailSignIn: ((String?) -> Unit) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val config by core.tools.config.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }
    var gmailBusy by remember { mutableStateOf(false) }
    var gmailError by remember { mutableStateOf<String?>(null) }
    var showImap by remember { mutableStateOf(false) }
    var userDraft by remember(config.imapUser) { mutableStateOf(config.imapUser) }
    var passDraft by remember(config.imapPassword) { mutableStateOf(config.imapPassword) }
    var hostDraft by remember(config.imapHost) { mutableStateOf(config.imapHost) }
    var keyDraft by remember(config.crwApiKey) { mutableStateOf(config.crwApiKey) }
    var urlDraft by remember(config.crwBaseUrl) { mutableStateOf(config.crwBaseUrl) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Tools",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "The agent calls these itself, mid-answer, when a question needs " +
                    "something it does not already know.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            core.tools.all.forEach { tool ->
                val on = config.isEnabled(tool.name)
                val usable = core.tools.usable(tool, config)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            tool.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            tool.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            // Readiness is the tool's own business now, so the
                            // line says which of the two states it is in and
                            // leaves the backend to the sections below.
                            when {
                                usable -> "Active"
                                !on -> "Off"
                                else -> "On, but not connected yet"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = on,
                        onCheckedChange = { next ->
                            scope.launch { core.tools.setEnabled(tool.name, next) }
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Backend: fastCRW (optional)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        // Both web tools are served by it, so its state is
                        // their state.
                        when {
                            config.crwApiKey.isNotBlank() -> "Hosted API, key stored"
                            config.crwBaseUrl.isNotBlank() -> "Self-hosted, no key needed"
                            else -> "Not set. Tools use the built-in backend, " +
                                "which needs no key but does not render JavaScript."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Hide" else "Configure")
                }
            }

            AnimatedVisibility(visible = showKey) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("fastCRW API key") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Self-hosted URL (optional)") },
                        placeholder = { Text("http://192.168.1.10:3000") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    core.tools.update {
                                        it.copy(
                                            crwApiKey = keyDraft.trim(),
                                            crwBaseUrl = urlDraft.trim(),
                                        )
                                    }
                                    showKey = false
                                }
                            },
                            enabled = keyDraft.trim() != config.crwApiKey ||
                                urlDraft.trim() != config.crwBaseUrl,
                        ) { Text("Save") }
                        TextButton(onClick = { onOpenUrl(CRW_SIGNUP) }) { Text("Get a key") }
                    }
                    Text(
                        "Stored encrypted with the same hardware-backed key as your " +
                            "provider keys. A self-hosted crw needs no key at all.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (core.tools.all.any { it.name == GMAIL_TOOL }) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Gmail account",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            gmailError
                                ?: config.gmailAccount.ifBlank {
                                    "Not connected. Sign in to let the agent read your " +
                                        "mail. Read-only: it can never send or delete."
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (gmailError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (config.gmailAccount.isNotBlank()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    core.tools.update { it.copy(gmailAccount = "") }
                                    gmailError = null
                                }
                            },
                        ) { Text("Disconnect") }
                    } else {
                        TextButton(
                            enabled = !gmailBusy,
                            onClick = {
                                gmailBusy = true
                                gmailError = null
                                onGmailSignIn { token ->
                                    scope.launch {
                                        gmailBusy = false
                                        // The address is fetched rather than
                                        // asked for: it proves the grant works,
                                        // and it is the only thing worth storing.
                                        val email = token?.let {
                                            runCatching { Gmail.profileEmail(it) }.getOrDefault("")
                                        }.orEmpty()
                                        if (email.isBlank()) {
                                            gmailError = "Sign-in did not complete."
                                        } else {
                                            // Connecting is the whole intent, so
                                            // the switch follows rather than
                                            // leaving a connected-but-off state.
                                            core.tools.update {
                                                it.copy(
                                                    gmailAccount = email,
                                                    enabled = it.enabled + GMAIL_TOOL,
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                        ) { Text(if (gmailBusy) "Signing in…" else "Sign in") }
                    }
                }
            }

            if (core.tools.all.any { it.name == IMAP_TOOL }) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Mailbox (IMAP)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            when {
                                config.imapNeedsBridge ->
                                    "That provider only serves IMAP through a desktop " +
                                        "bridge, which a phone cannot reach."
                                config.imapUser.isBlank() ->
                                    "Not set. An address and an app password reach " +
                                        "Outlook, Yahoo, Zoho, Fastmail, a work server, " +
                                        "or Gmail, with nothing to register."
                                else -> "${config.imapUser} via ${config.imapServer}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (config.imapNeedsBridge) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showImap = !showImap }) {
                        Text(if (showImap) "Hide" else "Configure")
                    }
                }

                AnimatedVisibility(visible = showImap) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = userDraft,
                            onValueChange = { userDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Email address") },
                            placeholder = { Text("you@example.com") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = passDraft,
                            onValueChange = { passDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("App password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(12.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = hostDraft,
                            onValueChange = { hostDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("IMAP server (optional)") },
                            // The guess for whatever has been typed so far, so
                            // the field can stay empty for almost everyone.
                            placeholder = {
                                Text(guessImapHost(userDraft).ifBlank { "imap.example.com" })
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    // Filling this in is the whole intent, so the
                                    // switch follows rather than leaving a
                                    // configured-but-off state.
                                    core.tools.update {
                                        it.copy(
                                            imapUser = userDraft.trim(),
                                            imapPassword = passDraft.trim(),
                                            imapHost = hostDraft.trim(),
                                            enabled = if (userDraft.isBlank()) it.enabled - IMAP_TOOL
                                            else it.enabled + IMAP_TOOL,
                                        )
                                    }
                                    showImap = false
                                }
                            },
                            enabled = userDraft.trim() != config.imapUser ||
                                passDraft.trim() != config.imapPassword ||
                                hostDraft.trim() != config.imapHost,
                        ) { Text("Save") }
                        Text(
                            "Port 993, TLS. Stored encrypted with the same " +
                                "hardware-backed key as your provider keys. Most " +
                                "providers want an app password rather than your " +
                                "account password: make one in their security settings.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private const val IMAP_TOOL = "mail_read"

private const val GMAIL_TOOL = "gmail_read"

private const val CRW_SIGNUP = "https://fastcrw.com/register"
