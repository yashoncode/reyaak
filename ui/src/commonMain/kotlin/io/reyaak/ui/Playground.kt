package io.reyaak.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reyaak.core.ReyaakCore
import io.reyaak.core.llm.TurnEvent
import io.reyaak.router.model.ChatMessage
import io.reyaak.router.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A bench for the router, one prompt at a time.
 *
 * Deliberately NOT routed through the agent or the chat engine: this is here to
 * answer "does that key work, and what does that model actually say", so it
 * neither needs a running agent nor belongs in the transcript. It goes straight
 * to [ReyaakCore.llm], which is the same door the agent uses, so a reply here
 * proves the whole provider path, not a test harness beside it.
 *
 * Pinning a model narrows the candidate chain to one row; leaving it blank tests
 * the router's own choice, which is the more useful default.
 */
@Composable
fun PlaygroundCard(core: ReyaakCore) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // The pin list comes from the router's own routable set — enabled models on
    // a platform with a usable key — so it cannot offer something the router
    // would refuse to route to anyway.
    val settings by core.configStore.settings.collectAsStateWithLifecycle()
    val pinnable = remember(settings) { settings.routableModels() }

    var prompt by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf<String?>(null) }
    var pinMenu by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var footnote by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Playground",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Send one prompt straight through the router. No agent and no transcript: " +
                    "this is for checking a key or comparing a model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Prompt") },
                maxLines = 5,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { pinMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = pinnable.isNotEmpty(),
                ) {
                    Text(
                        when {
                            pinnable.isEmpty() -> "No routable model. Add a key first."
                            pin == null -> "Model: router chooses"
                            else -> "Model: ${pin!!}"
                        }
                    )
                }
                DropdownMenu(expanded = pinMenu, onDismissRequest = { pinMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Router chooses") },
                        onClick = { pin = null; pinMenu = false },
                    )
                    pinnable.forEach { spec ->
                        DropdownMenuItem(
                            text = { Text("${spec.platform} / ${spec.displayName}") },
                            // The router matches a pin against modelId OR
                            // platform/modelId, so the composite key is what
                            // disambiguates a model served by two providers.
                            onClick = { pin = spec.key; pinMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        reply = ""
                        footnote = null
                        error = null
                        running = true
                        job = scope.launch {
                            try {
                                core.llm.stream(
                                    messages = listOf(ChatMessage(Role.USER, prompt.trim())),
                                    pinnedModel = pin,
                                ).collect { event ->
                                    when (event) {
                                        // The playground declares no tools, so
                                        // these cannot arrive here.
                                        is TurnEvent.ToolStarted,
                                        is TurnEvent.ToolFinished -> Unit
                                        is TurnEvent.Delta -> reply += event.text
                                        is TurnEvent.Complete -> footnote = buildString {
                                            append(event.served.providerId)
                                            append(" · ").append(event.served.modelId)
                                            append(" · ").append(event.served.latencyMs).append("ms")
                                            append(" · ").append(event.served.attempts).append(" attempt")
                                            if (event.served.attempts != 1) append("s")
                                            val tokens = event.usage.promptTokens + event.usage.completionTokens
                                            if (tokens > 0) append(" · ").append(tokens).append(" tok")
                                        }
                                        is TurnEvent.Failed -> {
                                            error = event.message
                                            footnote = event.attempts
                                                .joinToString("\n") { "${it.platform}/${it.modelId}: ${it.outcome}" }
                                                .ifBlank { null }
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                // Stop was pressed. Keep whatever text arrived.
                                throw e
                            } catch (e: Throwable) {
                                error = e.message ?: "the request failed"
                            } finally {
                                running = false
                            }
                        }
                    },
                    enabled = prompt.isNotBlank() && !running,
                ) { Text("Run") }

                if (running) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { job?.cancel() }) { Text("Stop") }
                }
            }

            if (reply.isNotEmpty() || error != null || footnote != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(12.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (reply.isNotEmpty()) {
                            Text(
                                reply,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        error?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        footnote?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
