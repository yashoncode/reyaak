package io.reyaak.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
fun PlaygroundScreen(core: ReyaakCore) {
    val t = LocalTokens.current
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 30.dp)
    ) {
        Text(
            "Send one prompt straight through the router. No agent and no transcript: " +
                "this is for checking a key or comparing a model.",
            color = t.mut,
            style = rk(400, 13.0, 1.6),
        )

        Spacer(Modifier.height(18.dp))
        Text("Prompt", color = t.mut, style = rk(400, 11.5, 1.0))
        Spacer(Modifier.height(7.dp))
        RkField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = "Ask one thing",
            minLines = 4,
            maxLines = 8,
        )

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth()) {
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(shape)
                    .background(t.g1)
                    .border(1.dp, t.line, shape)
                    .clickable(enabled = pinnable.isNotEmpty()) { pinMenu = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoText(
                    when {
                        pinnable.isEmpty() -> "No routable model. Add a key first."
                        pin == null -> "Model: router chooses"
                        else -> "Model: ${pin!!}"
                    },
                    Modifier.weight(1f),
                    size = 13.0,
                    maxLines = 1,
                )
                PhIcon(Ph.CARET_DOWN, 13.0, t.faint)
            }
            DropdownMenu(expanded = pinMenu, onDismissRequest = { pinMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Router chooses", color = t.ink, style = rk(400, 13.0, 1.3)) },
                    onClick = { pin = null; pinMenu = false },
                )
                pinnable.forEach { spec ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${spec.platform} / ${spec.displayName}",
                                color = t.ink,
                                style = rk(400, 13.0, 1.3),
                            )
                        },
                        // The router matches a pin against modelId OR
                        // platform/modelId, so the composite key is what
                        // disambiguates a model served by two providers.
                        onClick = { pin = spec.key; pinMenu = false },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val ready = prompt.isNotBlank() && !running
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(shape)
                    .background(if (prompt.isNotBlank()) t.accSoft else Color.Transparent)
                    .border(1.dp, t.accLine, shape)
                    .clickable(enabled = ready) {
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
                    }
                    .alpha(if (ready) 1f else 0.45f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                PhIcon(Ph.PLAY, 14.0, t.accLt)
                Spacer(Modifier.width(8.dp))
                Text("Run", color = t.accLt, style = rk(500, 13.5, 1.0))
            }

            if (running) {
                Spinner()
                RkButton(
                    label = "Stop",
                    onClick = { job?.cancel() },
                    tone = ButtonTone.NEUTRAL,
                    height = 44.dp,
                    fontSize = 13.0,
                )
            }
        }

        AnimatedVisibility(
            visible = reply.isNotEmpty() || error != null || footnote != null,
            enter = slideInVertically(tween(260)) { it / 4 } + fadeIn(tween(260)),
        ) {
            val panel = RoundedCornerShape(14.dp)
            Column(
                Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .clip(panel)
                    .background(t.tile)
                    .border(1.dp, t.line2, panel)
                    .padding(14.dp)
            ) {
                Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                ) {
                    if (reply.isNotEmpty()) {
                        Text(
                            reply,
                            color = if (t.dark) t.ink else Color(0xFFE8ECF4),
                            style = rk(400, 12.5, 1.65, mono = true),
                        )
                    }
                    error?.let {
                        if (reply.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        Text(it, color = t.err, style = rk(400, 12.5, 1.5))
                    }
                }
                footnote?.let {
                    Spacer(Modifier.height(12.dp))
                    GlassDivider()
                    Spacer(Modifier.height(10.dp))
                    MonoText(it, size = 10.5, tint = t.faint)
                }
            }
        }
    }
}

/** rkSpin: the 18dp ring the design spins beside a running prompt. */
@Composable
private fun Spinner() {
    val t = LocalTokens.current
    CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        color = t.acc,
        trackColor = t.g3,
        strokeWidth = 2.dp,
    )
}
