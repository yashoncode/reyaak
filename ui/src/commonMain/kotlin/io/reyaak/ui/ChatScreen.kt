package io.reyaak.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reyaak.router.catalog.BuiltinCatalog
import io.reyaak.vm.ChatViewModel
import io.reyaak.vm.TranscriptEntry

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenRouter: () -> Unit,
    onOpenAgent: () -> Unit,
    /** Same conversation, terminal rendering: monospace lines instead of bubbles. */
    cli: Boolean = false,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the reply as it streams, but only from the bottom: yanking the view
    // down while someone is scrolling back through the transcript is worse than
    // not following at all.
    LaunchedEffect(state.transcript.size, state.streaming) {
        val lastIndex = state.transcript.lastIndex
        if (lastIndex >= 0 && listState.firstVisibleItemIndex >= lastIndex - 3) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    // Exactly one thing reacts to the keyboard, and it is the composer. Padding
    // the whole Column as well stacked a second shift on top of the window's
    // own resize and threw the transcript off the top of the screen.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Column(Modifier.fillMaxSize()) {
        AgentStrip(
            running = state.agentRunning,
            activity = state.agentActivity,
            onOpenAgent = onOpenAgent,
        )

        if (state.transcript.isEmpty()) {
            EmptyChat(
                hasKeys = state.hasKeys,
                agentRunning = state.agentRunning,
                onOpenRouter = onOpenRouter,
                onOpenAgent = onOpenAgent,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(if (cli) 2.dp else 12.dp),
            ) {
                items(state.transcript, key = { it.id }) { entry ->
                    // A keyed item animates itself into place, so a new message
                    // slides in and the ones above it move up rather than
                    // jumping. One modifier, no transition bookkeeping.
                    val row = Modifier.animateItem()
                    if (cli) CliLine(entry, row) else MessageBubble(entry, row)
                }
            }
        }

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            enabled = state.canSend,
            cli = cli,
            keyboardOpen = keyboardOpen,
            hint = when {
                !state.agentRunning -> "Start the agent to chat"
                !state.hasKeys -> "Add a provider key to chat"
                cli -> "reyaak $"
                else -> "Message"
            },
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    draft = ""
                    vm.send(text)
                }
            },
        )
    }
}

@Composable
private fun EmptyChat(
    hasKeys: Boolean,
    agentRunning: Boolean,
    onOpenRouter: () -> Unit,
    onOpenAgent: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                !hasKeys -> "Add a provider key to start"
                !agentRunning -> "Start the agent to chat"
                else -> "Ask Reyaak anything"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                !hasKeys ->
                    // Counted from the catalog rather than typed, so adding a
                    // provider cannot leave this sentence lying.
                    "Reyaak routes across ${BuiltinCatalog.providers.size} providers, most " +
                        "of them with a free tier. Add a single key and the router does the rest."
                !agentRunning ->
                    "The agent is what answers, so it has to be running. It keeps " +
                        "working while the app is in the background."
                else ->
                    "The router picks a model for each turn and falls back if one is unavailable."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!hasKeys) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onOpenRouter) { Text("Open Router") }
        } else if (!agentRunning) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onOpenAgent) { Text("Open Agent") }
        }
    }
}

/**
 * A one-line read on the agent, above the transcript.
 *
 * It is here and not only on the Agent tab because the agent is now a
 * precondition for chatting: when the composer is dead this is the sentence
 * that says why, and the tap that fixes it.
 */
@Composable
private fun AgentStrip(running: Boolean, activity: String, onOpenAgent: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(
        color = if (running) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenAgent()
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (running) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(8.dp),
            ) {}
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (running) "Agent: " + activity else "Agent stopped",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (running) "Manage" else "Start",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun MessageBubble(entry: TranscriptEntry, modifier: Modifier = Modifier) {
    val isUser = entry.fromUser
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 1f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Surface(
                color = when {
                    entry.error != null -> MaterialTheme.colorScheme.errorContainer
                    isUser -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp,
                ),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (entry.content.isNotBlank()) {
                        Text(
                            text = entry.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                entry.error != null -> MaterialTheme.colorScheme.onErrorContainer
                                isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    if (entry.streaming && entry.content.isBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypingDots()
                            Spacer(Modifier.width(10.dp))
                            Text(
                                entry.toolNote?.let { "running $it…" } ?: "routing…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    entry.error?.let { message ->
                        if (entry.content.isNotBlank()) Spacer(Modifier.height(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // Provenance: which provider actually answered. This is the whole
            // point of a router, so it is shown rather than hidden in a log.
            entry.footnote?.let { note ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One transcript row as a terminal would print it.
 *
 * The same [TranscriptEntry] the bubbles render: CLI mode is a skin over the
 * one conversation, not a second chat with its own history. Prefixes carry what
 * colour and alignment carry in the bubble view: `$` for what you typed, `!`
 * for an error, `#` for provenance.
 */
@Composable
private fun CliLine(entry: TranscriptEntry, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        val body = when {
            entry.content.isNotBlank() -> entry.content
            entry.streaming -> "routing…"
            else -> ""
        }
        if (body.isNotEmpty()) {
            Text(
                text = if (entry.fromUser) "$ " + body else body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = when {
                    entry.error != null -> MaterialTheme.colorScheme.error
                    entry.fromUser -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onBackground
                },
            )
        }
        entry.error?.let { message ->
            Text(
                text = "! " + message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
            )
        }
        entry.footnote?.let { note ->
            Text(
                text = "# " + note,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    hint: String,
    cli: Boolean,
    keyboardOpen: Boolean,
    onSend: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val fire = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onSend()
    }
    // The nav bar sits inside the system navigation inset, so the strip the
    // composer has to leave clear is the bar plus that inset. Reserving only
    // NavBarSpace is what let the bar ride up over the send button.
    val systemNav = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.imePadding(),
    ) {
        Row(
            // The floating nav bar is hidden while the keyboard is up, so the
            // strip reserved for it goes away at the same time.
            modifier = Modifier.fillMaxWidth().padding(12.dp)
                .padding(bottom = if (keyboardOpen) 0.dp else NavBarSpace + systemNav - 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = { Text(hint, fontFamily = if (cli) FontFamily.Monospace else null) },
                textStyle = if (cli) {
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                maxLines = 6,
                shape = RoundedCornerShape(if (cli) 8.dp else 20.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (enabled) fire() }),
            )
            Spacer(Modifier.width(8.dp))
            // The button swells the moment there is something to send, which is
            // the whole feedback for "you started typing": a spring rather than
            // a state change nobody notices.
            val ready = enabled && draft.isNotBlank()
            val sendScale by animateFloatAsState(
                targetValue = if (ready) 1f else 0.86f,
                animationSpec = spring(dampingRatio = 0.45f, stiffness = 520f),
                label = "sendScale",
            )
            FilledIconButton(
                onClick = fire,
                enabled = ready,
                modifier = Modifier.size(48.dp).scale(sendScale),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = if (enabled) "↑" else "…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * Three dots breathing in sequence, while the router is still choosing.
 *
 * A determinate spinner would be a lie here: nothing about picking a model and
 * waiting for a first byte has a known duration. The stagger comes from one
 * infinite transition read at three offsets, so there is no per-dot animation
 * to keep in step.
 */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 520,
                        delayMillis = index * 140,
                        easing = FastOutSlowInEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .padding(end = if (index == 2) 0.dp else 4.dp)
                    .offset(y = (-3 * phase).dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .alpha(0.45f + 0.55f * phase)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
