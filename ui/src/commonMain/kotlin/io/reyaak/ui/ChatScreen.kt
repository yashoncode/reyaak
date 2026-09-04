package io.reyaak.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reyaak.router.catalog.BuiltinCatalog
import io.reyaak.vm.ChatViewModel
import io.reyaak.vm.TranscriptEntry

/** The three openers offered on an empty, ready transcript. */
private val Starters = listOf(
    "Summarise what my enabled models are good at",
    "Which of my enabled models has the best uptime?",
    "Draft a standup digest from yesterday",
)

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenRouter: () -> Unit,
    onOpenAgent: () -> Unit,
    /** Start the agent from here, since a stopped agent is why chat is dead. */
    onStartAgent: () -> Unit,
    onOpenHistory: () -> Unit,
    /** Same conversation, terminal rendering: monospace lines instead of bubbles. */
    cli: Boolean = false,
    onToggleCli: () -> Unit = {},
) {
    val t = LocalTokens.current
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    // Follow the reply as it streams, but only from the bottom: yanking the view
    // down while someone is scrolling back through the transcript is worse than
    // not following at all.
    LaunchedEffect(state.transcript.size, state.streaming) {
        val lastIndex = state.transcript.lastIndex
        if (lastIndex >= 0 && listState.firstVisibleItemIndex >= lastIndex - 3) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    // Auto-follow deliberately stops once you scroll up, so there has to be a
    // way back down that is not "scroll all the way yourself".
    val awayFromBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last != null && last.index < listState.layoutInfo.totalItemsCount - 2
        }
    }

    // Exactly one thing reacts to the keyboard, and it is the composer. Padding
    // the whole Column as well stacked a second shift on top of the window's
    // own resize and threw the transcript off the top of the screen.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // Raised by the jump button, consumed by the effect below: the scroll needs
    // a coroutine scope, and a click handler is not one.
    var jumpRequested by remember { mutableStateOf(false) }
    LaunchedEffect(jumpRequested) {
        if (jumpRequested) {
            listState.animateScrollToItem(state.transcript.lastIndex.coerceAtLeast(0))
            jumpRequested = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(
                cli = cli,
                onOpenHistory = onOpenHistory,
                onToggleCli = onToggleCli,
                onNewConversation = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.newConversation()
                },
            )

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
                    onStartAgent = onStartAgent,
                    onPick = { draft = it },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (cli) 2.dp else 16.dp),
                ) {
                    items(state.transcript, key = { it.id }) { entry ->
                        // A keyed item animates itself into place, so a new message
                        // slides in and the ones above it move up rather than
                        // jumping. One modifier, no transition bookkeeping.
                        val row = Modifier.animateItem()
                        if (cli) {
                            CliLine(entry, row)
                        } else {
                            MessageBubble(
                                entry = entry,
                                onRetry = vm::retryLast,
                                modifier = row,
                            )
                        }
                    }
                }
            }

            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                enabled = state.canSend,
                streaming = state.streaming,
                cli = cli,
                keyboardOpen = keyboardOpen,
                modelShort = state.modelShort,
                routerNote = state.routerNote,
                onOpenRouter = onOpenRouter,
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
                onStop = vm::stop,
            )
        }

        AnimatedVisibility(
            visible = awayFromBottom && state.transcript.isNotEmpty() && !keyboardOpen,
            enter = scaleIn(tween(220), initialScale = 0.8f) + fadeIn(tween(220)),
            exit = scaleOut(tween(160), targetScale = 0.8f) + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 186.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(t.g2)
                    .background(t.bg.copy(alpha = 0.5f))
                    .border(1.dp, t.line, CircleShape)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        jumpRequested = true
                    },
                contentAlignment = Alignment.Center,
            ) { PhIcon(Ph.ARROW_DOWN, 16.0, t.ink) }
        }
    }
}

/**
 * The chat chrome: conversations, the mark, and the two chat-only actions.
 *
 * This lives in the screen rather than a shared app bar because it is the only
 * screen with actions. Every other tab opens with its own large title, so a
 * shared bar would be a mostly empty strip on three screens out of four.
 */
@Composable
private fun ChatHeader(
    cli: Boolean,
    onOpenHistory: () -> Unit,
    onToggleCli: () -> Unit,
    onNewConversation: () -> Unit,
) {
    val t = LocalTokens.current
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeaderButton(Ph.LIST, 18.0) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onOpenHistory()
        }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(22.dp)
            Spacer(Modifier.width(9.dp))
            Text("Reyaak", color = t.ink, style = rk(600, 16.0, 1.0, tracking = (-0.01).em))
        }
        HeaderButton(
            glyph = Ph.TERMINAL,
            size = 17.0,
            fill = cli,
            tint = if (cli) t.accLt else t.mut,
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggleCli()
        }
        HeaderButton(Ph.PLUS, 17.0, onClick = onNewConversation)
    }
}

@Composable
private fun HeaderButton(
    glyph: String,
    size: Double,
    fill: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(38.dp)
            .clip(shape)
            .background(if (fill) t.accSoft else t.g1)
            .border(1.dp, if (fill) t.accLine else t.line, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PhIcon(glyph, size, tint ?: t.ink) }
}

@Composable
private fun EmptyChat(
    hasKeys: Boolean,
    agentRunning: Boolean,
    onOpenRouter: () -> Unit,
    onStartAgent: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier,
) {
    val t = LocalTokens.current
    val halo = rememberBreath(6000)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(108.dp)
                    .scale(1f + 0.14f * halo)
                    .alpha(0.30f + 0.30f * halo)
                    .background(
                        Brush.radialGradient(listOf(t.accSoft, Color.Transparent)),
                        CircleShape,
                    )
            )
            IconTile(58.dp)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = when {
                !hasKeys -> "Add a provider key to start"
                !agentRunning -> "Start the agent to chat"
                else -> "Ask Reyaak anything"
            },
            color = t.ink,
            style = rk(500, 20.0, 1.25, tracking = (-0.02).em),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
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
            color = t.mut,
            style = rk(400, 13.5, 1.55),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        if (!hasKeys || !agentRunning) {
            Spacer(Modifier.height(16.dp))
            RkButton(
                label = if (!hasKeys) "Open Router" else "Start agent",
                onClick = if (!hasKeys) onOpenRouter else onStartAgent,
                glyph = Ph.ARROW_RIGHT,
                tone = ButtonTone.OUTLINE,
            )
        } else {
            Spacer(Modifier.height(22.dp))
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Starters.forEach { prompt ->
                    val shape = RoundedCornerShape(15.dp)
                    Text(
                        prompt,
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(t.g1)
                            .border(1.dp, t.line, shape)
                            .clickable { onPick(prompt) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        color = t.mut,
                        style = rk(400, 13.0, 1.4),
                    )
                }
            }
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
    val t = LocalTokens.current
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 4.dp)
            .clip(shape)
            .background(t.g1)
            .border(1.dp, t.line, shape)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onOpenAgent()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(running)
        Spacer(Modifier.width(10.dp))
        if (running) {
            MonoText(
                "Agent: $activity",
                Modifier.weight(1f),
                size = 12.5,
                tint = t.ink,
                maxLines = 1,
            )
        } else {
            Text(
                "Agent stopped",
                Modifier.weight(1f),
                color = t.mut,
                style = rk(400, 13.0, 1.3),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (running) "Manage" else "Start",
            color = t.accLt,
            style = rk(500, 12.5, 1.0),
        )
    }
}

@Composable
private fun MessageBubble(
    entry: TranscriptEntry,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTokens.current
    when {
        entry.error != null -> ErrorBubble(entry, onRetry, modifier)
        entry.fromUser -> Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomEnd = 6.dp,
                bottomStart = 20.dp,
            )
            Box(
                Modifier
                    .fillMaxWidth(0.86f)
                    .clip(shape)
                    .background(t.accSoft)
                    .border(1.dp, t.accLine, shape)
                    .padding(horizontal = 15.dp, vertical = 11.dp)
            ) {
                Text(entry.content, color = t.ink, style = rk(400, 14.5, 1.5))
            }
        }
        // The assistant does not get a bubble. It is the page talking, and a
        // container around every reply is what makes a transcript read as a
        // stack of cards instead of a conversation.
        else -> AssistantMessage(entry, onRetry, modifier)
    }
}

@Composable
private fun AssistantMessage(
    entry: TranscriptEntry,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTokens.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    Column(modifier.fillMaxWidth()) {
        if (entry.streaming && entry.content.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypingDots()
                Spacer(Modifier.width(10.dp))
                MonoText(
                    entry.toolNote?.let { "running $it…" } ?: "routing…",
                    size = 12.5,
                )
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(entry.content, color = t.ink, style = rk(400, 15.0, 1.62))
            if (entry.streaming) {
                Spacer(Modifier.width(3.dp))
                Caret()
            }
        }

        entry.footnote?.let { note ->
            Spacer(Modifier.height(9.dp))
            // Provenance: which provider actually answered. This is the whole
            // point of a router, so it is shown rather than hidden in a log.
            MonoText(note, size = 10.5, tint = t.faint)
        }

        if (!entry.streaming && entry.content.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RowAction(Ph.COPY) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    clipboard.setText(AnnotatedString(entry.content))
                }
                RowAction(Ph.REFRESH) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onRetry()
                }
            }
        }
    }
}

@Composable
private fun RowAction(glyph: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PhIcon(glyph, 14.0, t.faint) }
}

/** The blinking block that says text is still arriving. */
@Composable
private fun Caret() {
    val t = LocalTokens.current
    val transition = rememberInfiniteTransition(label = "caret")
    val on by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "caretAlpha",
    )
    Box(
        Modifier
            .padding(bottom = 2.dp)
            .size(7.dp, 15.dp)
            .alpha(on)
            .clip(RoundedCornerShape(2.dp))
            .background(t.accLt)
    )
}

/**
 * A failed turn, with the way out of it.
 *
 * The old rendering was terminal: an error bubble and nothing else, so the only
 * recovery was retyping the question. The retry re-sends the last thing the user
 * actually said, which is what they would have done by hand.
 */
@Composable
private fun ErrorBubble(
    entry: TranscriptEntry,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomEnd = 4.dp,
        bottomStart = 18.dp,
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.errSoft)
            .border(1.dp, t.errLine, shape)
            .padding(horizontal = 15.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhIcon(Ph.WARNING_CIRCLE, 15.0, t.err)
            Spacer(Modifier.width(8.dp))
            Text("That turn failed", color = t.err, style = rk(500, 13.5, 1.3))
        }
        if (entry.content.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(entry.content, color = t.mut, style = rk(400, 12.5, 1.5))
        }
        entry.error?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(message, color = t.mut, style = rk(400, 12.5, 1.5))
        }
        Spacer(Modifier.height(11.dp))
        val pill = RoundedCornerShape(11.dp)
        Row(
            Modifier
                .clip(pill)
                .border(1.dp, t.err.copy(alpha = 0.4f), pill)
                .clickable(onClick = onRetry)
                .padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhIcon(Ph.COUNTER_CLOCKWISE, 13.0, t.err)
            Spacer(Modifier.width(7.dp))
            Text("Retry", color = t.err, style = rk(500, 12.5, 1.0))
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
    val t = LocalTokens.current
    Column(modifier.fillMaxWidth()) {
        val body = when {
            entry.content.isNotBlank() -> entry.content
            entry.streaming -> entry.toolNote?.let { "running $it…" } ?: "routing…"
            else -> ""
        }
        if (body.isNotEmpty()) {
            Text(
                text = if (entry.fromUser) "$ $body" else body,
                color = when {
                    entry.error != null -> t.err
                    entry.fromUser -> t.accLt
                    else -> t.ink
                },
                style = rk(400, 12.5, 1.6, mono = true),
            )
        }
        entry.error?.let { message ->
            Text("! $message", color = t.err, style = rk(400, 12.5, 1.6, mono = true))
        }
        entry.footnote?.let { note ->
            Text("# $note", color = t.faint, style = rk(400, 11.5, 1.6, mono = true))
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    streaming: Boolean,
    hint: String,
    cli: Boolean,
    keyboardOpen: Boolean,
    modelShort: String,
    routerNote: String,
    onOpenRouter: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val t = LocalTokens.current
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

    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp)
            // The floating tab bar is hidden while the keyboard is up, so the
            // strip reserved for it goes away at the same time.
            .padding(bottom = if (keyboardOpen) 8.dp else NavBarSpace + systemNav)
    ) {
        // Which model is about to answer, and why that one. It is a chip rather
        // than a line of text because it is also the way to the Router.
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pill = RoundedCornerShape(99.dp)
            Row(
                Modifier
                    .clip(pill)
                    .background(t.g1)
                    .border(1.dp, t.line, pill)
                    .clickable(onClick = onOpenRouter)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(t.accLt))
                Spacer(Modifier.width(7.dp))
                MonoText(modelShort, size = 11.0, maxLines = 1)
                Spacer(Modifier.width(7.dp))
                PhIcon(Ph.CARET_UP_DOWN, 10.0, t.faint)
            }
            Spacer(Modifier.weight(1f))
            MonoText(routerNote, size = 11.0, tint = t.faint, maxLines = 1)
        }

        val shape = RoundedCornerShape(if (cli) 12.dp else 26.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(t.g1)
                .background(t.bg.copy(alpha = if (t.dark) 0.34f else 0.30f))
                .border(1.dp, t.line, shape)
                .padding(start = 6.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = rk(400, if (cli) 13.5 else 15.0, 1.4, mono = cli).copy(color = t.ink)
            Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
                if (draft.isEmpty()) Text(hint, color = t.faint, style = style)
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = enabled,
                    textStyle = style,
                    cursorBrush = Brush.verticalGradient(listOf(t.accLt, t.accLt)),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (enabled) fire() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val ready = enabled && draft.isNotBlank()
            when {
                streaming -> Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .border(1.dp, t.accLine, CircleShape)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStop()
                        },
                    contentAlignment = Alignment.Center,
                ) { PhIcon(Ph.STOP, 13.0, t.accLt, fill = true) }

                ready -> {
                    // The button swells the moment there is something to send,
                    // which is the whole feedback for "you started typing": a
                    // spring rather than a state change nobody notices.
                    val pop by animateFloatAsState(
                        targetValue = 1f,
                        animationSpec = spring(dampingRatio = 0.45f, stiffness = 520f),
                        label = "sendPop",
                    )
                    Box(
                        Modifier
                            .size(38.dp)
                            .scale(pop)
                            .clip(CircleShape)
                            .background(t.acc)
                            .clickable(onClick = fire),
                        contentAlignment = Alignment.Center,
                    ) { PhIcon(Ph.ARROW_UP, 17.0, t.tile, fill = true) }
                }

                else -> Box(
                    Modifier.size(38.dp).alpha(0.5f),
                    contentAlignment = Alignment.Center,
                ) { PhIcon(Ph.ARROW_UP, 17.0, t.mut, fill = true) }
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
    val t = LocalTokens.current
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.height(16.dp)) {
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
                    .alpha(0.4f + 0.6f * phase)
                    .background(t.acc)
            )
        }
    }
}
