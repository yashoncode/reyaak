package io.reyaak.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    val t = LocalTokens.current
    val feedback = LocalFeedback.current
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

    Glass(radius = 20.dp, modifier = Modifier.animateContentSize()) {
        CardTitle("Tools")
        Spacer(Modifier.height(5.dp))
        CardBody(
            "The agent calls these itself, mid-answer, when a question needs " +
                "something it does not already know."
        )
        Spacer(Modifier.height(14.dp))
        GlassDivider()

        core.tools.all.forEach { tool ->
            val on = config.isEnabled(tool.name)
            val usable = core.tools.usable(tool, config)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(tool.label, color = t.ink, style = rk(400, 13.5, 1.3))
                        Spacer(Modifier.width(8.dp))
                        MonoText(tool.name, size = 10.5, tint = t.faint)
                    }
                    Spacer(Modifier.height(6.dp))
                    // Readiness was caption text in the same colour as the body
                    // copy beside it, which made the one line that answers "why
                    // is this not working" the easiest line to miss.
                    ReadinessBadge(
                        readiness = when {
                            usable -> Readiness.ACTIVE
                            !on -> Readiness.OFF
                            else -> Readiness.BLOCKED
                        },
                        label = when {
                            usable -> "Active"
                            !on -> "Off"
                            else -> "On, but not connected yet"
                        },
                    )
                }
                Spacer(Modifier.width(11.dp))
                RkSwitch(
                    checked = on,
                    onChange = { next -> scope.launch { core.tools.setEnabled(tool.name, next) } },
                )
            }
            GlassDivider()
        }

        ToolSection(
            title = "fastCRW backend",
            // Both web tools are served by it, so its state is their state.
            status = when {
                config.crwApiKey.isNotBlank() -> "Hosted API, key stored"
                config.crwBaseUrl.isNotBlank() -> "Self-hosted, no key needed"
                else -> "Not set. Tools use the built-in backend, " +
                    "which needs no key but does not render JavaScript."
            },
            cta = if (showKey) "Hide" else "Configure",
            onCta = { showKey = !showKey },
            top = 16.dp,
        ) {
            AnimatedVisibility(visible = showKey) {
                Column(
                    Modifier.padding(top = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    RkField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        placeholder = "fastCRW API key",
                        mono = true,
                        masked = true,
                    )
                    RkField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        placeholder = "http://192.168.1.10:3000",
                        mono = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DirtySave(
                            dirty = keyDraft.trim() != config.crwApiKey ||
                                urlDraft.trim() != config.crwBaseUrl,
                            onClick = {
                                scope.launch {
                                    core.tools.update {
                                        it.copy(
                                            crwApiKey = keyDraft.trim(),
                                            crwBaseUrl = urlDraft.trim(),
                                        )
                                    }
                                    showKey = false
                                    feedback.say("fastCRW credentials stored, encrypted.")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(9.dp))
                        RkTextAction("Get a key", onClick = { onOpenUrl(CRW_SIGNUP) })
                    }
                    SecretNote(
                        "Stored encrypted with the same hardware-backed key as your " +
                            "provider keys. A self-hosted crw needs no key at all."
                    )
                }
            }
        }

        if (core.tools.all.any { it.name == GMAIL_TOOL }) {
            ToolSection(
                title = "Gmail",
                status = gmailError
                    ?: config.gmailAccount.ifBlank {
                        "Not connected. Sign in to let the agent read your " +
                            "mail. Read-only: it can never send or delete."
                    },
                statusTint = if (gmailError != null) t.err else null,
                cta = when {
                    config.gmailAccount.isNotBlank() -> "Disconnect"
                    gmailBusy -> "Signing in…"
                    else -> "Sign in"
                },
                ctaEnabled = !gmailBusy,
                onCta = {
                    if (config.gmailAccount.isNotBlank()) {
                        scope.launch {
                            core.tools.update { it.copy(gmailAccount = "") }
                            gmailError = null
                            feedback.say("Gmail disconnected.")
                        }
                    } else {
                        gmailBusy = true
                        gmailError = null
                        onGmailSignIn { token ->
                            scope.launch {
                                gmailBusy = false
                                // The address is fetched rather than asked for:
                                // it proves the grant works, and it is the only
                                // thing worth storing.
                                val email = token?.let {
                                    runCatching { Gmail.profileEmail(it) }.getOrDefault("")
                                }.orEmpty()
                                if (email.isBlank()) {
                                    gmailError = "Sign-in did not complete."
                                    feedback.fail("Gmail sign-in did not complete.")
                                } else {
                                    // Connecting is the whole intent, so the
                                    // switch follows rather than leaving a
                                    // connected-but-off state.
                                    core.tools.update {
                                        it.copy(
                                            gmailAccount = email,
                                            enabled = it.enabled + GMAIL_TOOL,
                                        )
                                    }
                                    feedback.say("Gmail connected, read-only.")
                                }
                            }
                        }
                    }
                },
            ) {}
        }

        if (core.tools.all.any { it.name == IMAP_TOOL }) {
            ToolSection(
                title = "Mailbox (IMAP)",
                status = when {
                    config.imapNeedsBridge ->
                        "That provider only serves IMAP through a desktop " +
                            "bridge, which a phone cannot reach."
                    config.imapUser.isBlank() ->
                        "Not set. An address and an app password reach " +
                            "Outlook, Yahoo, Zoho, Fastmail, a work server, " +
                            "or Gmail, with nothing to register."
                    else -> "${config.imapUser} via ${config.imapServer}"
                },
                statusTint = if (config.imapNeedsBridge) t.err else null,
                cta = if (showImap) "Hide" else "Configure",
                onCta = { showImap = !showImap },
            ) {
                AnimatedVisibility(visible = showImap) {
                    Column(
                        Modifier.padding(top = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        RkField(
                            value = userDraft,
                            onValueChange = { userDraft = it },
                            placeholder = "you@example.com",
                            mono = true,
                        )
                        RkField(
                            value = passDraft,
                            onValueChange = { passDraft = it },
                            placeholder = "App password",
                            mono = true,
                            masked = true,
                        )
                        RkField(
                            value = hostDraft,
                            onValueChange = { hostDraft = it },
                            // The guess for whatever has been typed so far, so
                            // the field can stay empty for almost everyone.
                            placeholder = guessImapHost(userDraft).ifBlank { "imap.example.com" },
                            mono = true,
                        )
                        DirtySave(
                            dirty = userDraft.trim() != config.imapUser ||
                                passDraft.trim() != config.imapPassword ||
                                hostDraft.trim() != config.imapHost,
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
                                    feedback.say("Mailbox credentials stored, encrypted.")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SecretNote(
                            "Port 993, TLS. Stored encrypted with the same " +
                                "hardware-backed key as your provider keys. Most " +
                                "providers want an app password rather than your " +
                                "account password: make one in their security settings."
                        )
                    }
                }
            }
        }
    }
}

/**
 * A backend inside the Tools card: what it is, what state it is in, one action.
 *
 * fastCRW, Gmail and the mailbox are the same shape, so they are the same
 * composable. The nested panel is what says "this configures a tool above"
 * rather than "this is a fourth tool".
 */
@Composable
private fun ToolSection(
    title: String,
    status: String,
    cta: String,
    onCta: () -> Unit,
    statusTint: Color? = null,
    ctaEnabled: Boolean = true,
    top: androidx.compose.ui.unit.Dp = 9.dp,
    content: @Composable () -> Unit,
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(15.dp)
    Column(
        Modifier
            .padding(top = top)
            .fillMaxWidth()
            .clip(shape)
            .background(t.g1)
            .border(1.dp, t.line2, shape)
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = t.ink, style = rk(400, 13.0, 1.3))
                Spacer(Modifier.height(3.dp))
                Text(status, color = statusTint ?: t.mut, style = rk(400, 11.5, 1.5))
            }
            Spacer(Modifier.width(10.dp))
            RkChipButton(cta, onClick = onCta, enabled = ctaEnabled)
        }
        content()
    }
}

/** The recurring encryption promise, with the marker that makes it visible. */
@Composable
private fun SecretNote(text: String) {
    val t = LocalTokens.current
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Top) {
        PhIcon(Ph.SHIELD_CHECK, 14.0, t.accLt, Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = t.faint, style = rk(400, 11.0, 1.5))
    }
}

private const val IMAP_TOOL = "mail_read"

private const val GMAIL_TOOL = "gmail_read"

private const val CRW_SIGNUP = "https://fastcrw.com/register"
