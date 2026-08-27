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
import kotlinx.coroutines.launch

/**
 * What the agent can do besides talk.
 *
 * A tool is only offered to the model when it is switched on AND can actually
 * run: a declared tool that answers "not configured" wastes a whole round trip
 * to say so, which is why the switches and the backend live in one card.
 */
@Composable
fun ToolsCard(core: ReyaakCore, onOpenUrl: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val config by core.tools.config.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }
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
                            when {
                                !usable -> "Off"
                                config.usesCrw -> "Active, via crw"
                                else -> "Active, built-in backend"
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
        }
    }
}

private const val CRW_SIGNUP = "https://fastcrw.com/register"
