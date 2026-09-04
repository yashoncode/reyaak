package io.reyaak.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reyaak.router.catalog.BuiltinCatalog
import io.reyaak.router.score.RoutingStrategy
import io.reyaak.vm.ChainRow
import io.reyaak.vm.ModelRow
import io.reyaak.vm.ProviderRow
import io.reyaak.vm.RouterViewModel

@Composable
fun RouterScreen(
    vm: RouterViewModel,
    // The three things this screen needs from an OS, as lambdas. A document
    // picker, a document writer, and a browser are all platform APIs; keeping
    // them as parameters is what lets this file stay in commonMain.
    onOpenUrl: (String) -> Unit,
    onPickDocument: (onText: (String?) -> Unit) -> Unit,
    onSaveDocument: (fileName: String, content: String, onSaved: () -> Unit) -> Unit,
    bottomPadding: Dp = NavBarSpace,
) {
    val t = LocalTokens.current
    val feedback = LocalFeedback.current
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()

    var keyDialogFor by remember { mutableStateOf<String?>(null) }
    var showOrderEditor by remember { mutableStateOf(false) }

    // The router used to own the app's only feedback banner. It now hands its
    // notices to the same channel every other screen uses, so there is one
    // place a message can appear rather than one per screen that bothered.
    LaunchedEffect(notice) {
        notice?.let {
            feedback.toast(it.text, it.isError, it.warnings)
            vm.dismissNotice()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 2.dp,
                bottom = bottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    ScreenTitle("Router", Modifier.padding(top = 14.dp, bottom = 4.dp))
                    Text(
                        "Scored across ${BuiltinCatalog.providers.size} providers. " +
                            "Whatever breaks gets benched.",
                        color = t.mut,
                        style = rk(400, 13.0, 1.55),
                    )
                    Spacer(Modifier.height(20.dp))
                    SectionLabel("Routing strategy")
                    Spacer(Modifier.height(10.dp))
                    StrategyPicker(state.strategy) { vm.setStrategy(it) }
                }
            }

            item { ChainCard(state.chain, state.chainExclusions) }

            if (state.strategy == RoutingStrategy.PRIORITY) {
                item {
                    ManualOrderCard(
                        order = state.manualOrder,
                        onEdit = { showOrderEditor = true },
                        onClear = { vm.clearOrder() },
                    )
                }
            }

            item {
                ConfigCard(
                    onRestart = { vm.restartRouter() },
                    onImport = {
                        onPickDocument { text ->
                            // Ask merge-or-replace rather than assuming: replacing
                            // would silently drop the key in use right now.
                            if (text.isNullOrBlank()) {
                                vm.import("", merge = true)
                            } else {
                                feedback.confirm(
                                    Confirmation(
                                        title = "Import router config",
                                        body = "Merge keeps your current keys and layers " +
                                            "this file on top. Replace discards everything " +
                                            "you have now.",
                                        cta = "Merge",
                                        run = { vm.import(text, merge = true) },
                                        alt = "Replace" to { vm.import(text, merge = false) },
                                    )
                                )
                            }
                        }
                    },
                    onExport = {
                        feedback.confirm(
                            Confirmation(
                                title = "Include API keys?",
                                body = "An export without keys describes your setup safely. " +
                                    "With keys it is a credential file, only for moving to " +
                                    "another device.",
                                cta = "Without keys",
                                run = {
                                    onSaveDocument("reyaak-router.json", vm.exportJson(false)) {
                                        vm.onExported(false)
                                    }
                                },
                                alt = "With keys" to {
                                    onSaveDocument("reyaak-router.json", vm.exportJson(true)) {
                                        vm.onExported(true)
                                    }
                                },
                            )
                        )
                    },
                )
            }

            item {
                SectionLabel("Providers", Modifier.padding(top = 14.dp, bottom = 0.dp))
            }

            items(state.providers, key = { it.provider.id }) { row ->
                ProviderCard(
                    row = row,
                    onAddKey = { keyDialogFor = row.provider.id },
                    onRemoveKey = { label ->
                        // Removing a key un-routes every model that depended on
                        // it, and the secret is not recoverable, so it asks.
                        feedback.confirm(
                            Confirmation(
                                title = "Remove this key?",
                                body = "The $label key for ${row.provider.label} is deleted " +
                                    "from the encrypted store. Models that depend on it stop " +
                                    "being routable.",
                                cta = "Remove",
                                danger = true,
                                run = { vm.removeKey(row.provider.id, label) },
                            )
                        )
                    },
                    onToggleKey = { label, on -> vm.toggleKey(row.provider.id, label, on) },
                    onRefreshModels = { vm.refreshModels(row.provider.id) },
                    onToggleModel = { key, on -> vm.toggleModel(key, on) },
                    onSetAllModels = { on -> vm.setAllModels(row.provider.id, on) },
                    onClearCooldown = { modelId ->
                        vm.clearCooldown(row.provider.id, modelId, row.keys.firstOrNull()?.label ?: "k1")
                    },
                )
            }

            if (usage.isNotEmpty()) {
                item {
                    Column {
                        SectionLabel("Usage", Modifier.padding(top = 14.dp))
                        Spacer(Modifier.height(10.dp))
                        UsageCard(usage)
                    }
                }
            }
        }

        if (busy) BusyBar(Modifier.align(Alignment.TopCenter))
    }

    keyDialogFor?.let { platform ->
        AddKeyDialog(
            platform = platform,
            consoleUrl = state.providers.firstOrNull { it.provider.id == platform }?.provider?.consoleUrl,
            onDismiss = { keyDialogFor = null },
            onSave = { label, secret ->
                vm.addKey(platform, label, secret)
                keyDialogFor = null
            },
            onOpenConsole = onOpenUrl,
        )
    }

    if (showOrderEditor) {
        OrderEditorDialog(
            order = state.manualOrder,
            onMove = { key, delta -> vm.moveInOrder(key, delta) },
            onDismiss = { showOrderEditor = false },
        )
    }
}

@Composable
private fun StrategyPicker(current: RoutingStrategy, onPick: (RoutingStrategy) -> Unit) {
    val t = LocalTokens.current
    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            SelectableStrategies.forEach { strategy ->
                val on = current == strategy
                val shape = RoundedCornerShape(99.dp)
                Box(
                    Modifier
                        .clip(shape)
                        .background(if (on) t.accSoft else t.g1)
                        .border(1.dp, if (on) t.accLine else t.line, shape)
                        .clickable(enabled = !on) { onPick(strategy) }
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                ) {
                    Text(
                        strategyLabel(strategy),
                        color = if (on) t.accLt else t.mut,
                        style = rk(if (on) 500 else 400, 12.5, 1.0),
                    )
                }
            }
        }
        Spacer(Modifier.height(11.dp))
        Text(strategyDescription(current), color = t.mut, style = rk(400, 12.5, 1.5))
    }
}

@Composable
private fun ChainCard(chain: List<ChainRow>, exclusions: List<String>) {
    val t = LocalTokens.current
    Glass(radius = 20.dp, highlight = true, modifier = Modifier.padding(top = 6.dp)) {
        CardTitle("Next request would try")
        if (chain.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Nothing is routable yet. Add a provider key below.",
                color = t.mut,
                style = rk(400, 12.5, 1.5),
            )
        } else {
            Spacer(Modifier.height(14.dp))
            // Scores are relative, so the bar is drawn against the leader
            // rather than against 1.0: what matters is the gap to the model
            // that would actually be tried first.
            val top = chain.maxOf { it.score }.coerceAtLeast(0.0001)
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                chain.forEachIndexed { index, row ->
                    ChainLine(index, row, row.score / top)
                }
            }
        }
        if (exclusions.isNotEmpty()) {
            Spacer(Modifier.height(15.dp))
            GlassDivider()
            Spacer(Modifier.height(13.dp))
            Text(
                "EXCLUDED",
                color = t.faint,
                style = rk(500, 10.5, 1.0, mono = true, tracking = 0.12.em),
            )
            exclusions.forEach {
                MonoText(it, Modifier.padding(top = 4.dp), size = 11.0)
            }
        }
    }
}

/** One chain row: rank, model, score, and a bar for the score at a glance. */
@Composable
private fun ChainLine(index: Int, row: ChainRow, fraction: Double) {
    val t = LocalTokens.current
    val leading = index == 0
    val tint = if (leading) t.ink else t.mut
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            MonoText("${index + 1}".padStart(2), size = 11.0, tint = t.faint)
            Spacer(Modifier.width(9.dp))
            Text(
                row.displayName,
                color = tint,
                style = rk(if (leading) 600 else 400, 13.5, 1.2),
                maxLines = 1,
            )
            Spacer(Modifier.width(9.dp))
            MonoText(row.platform, Modifier.weight(1f), size = 10.5, tint = t.faint, maxLines = 1)
            Spacer(Modifier.width(9.dp))
            MonoText(row.score.fmt(3), size = 11.0, tint = tint)
        }
        Spacer(Modifier.height(7.dp))
        // The bar animates so a score that moved after a turn reads as movement
        // rather than as a different number in the same place.
        val animated by animateFloatAsState(
            targetValue = fraction.toFloat().coerceIn(0f, 1f),
            animationSpec = tween(600),
            label = "chainScore",
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(t.g2)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .height(3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (leading) t.acc else t.mut)
            )
        }
    }
}

/**
 * The manual chain, and the way into editing it.
 *
 * Shown only under the manual strategy, because under every other one the order
 * is scored and an editor would imply an authority it does not have.
 */
@Composable
private fun ManualOrderCard(
    order: List<ChainRow>,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    val t = LocalTokens.current
    Glass(radius = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardTitle("Manual order")
                Spacer(Modifier.height(4.dp))
                CardBody(
                    if (order.isEmpty()) "Nothing routable to order yet."
                    else "${order.size} models, tried top to bottom."
                )
            }
            Spacer(Modifier.width(10.dp))
            RkTextAction(
                "Reset",
                onClick = onClear,
                tint = t.mut,
                fontSize = 12.0,
                enabled = order.isNotEmpty(),
            )
            Spacer(Modifier.width(4.dp))
            RkChipButton("Edit", onClick = onEdit, accent = true, enabled = order.isNotEmpty())
        }
        if (order.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            order.take(3).forEachIndexed { index, row ->
                MonoText(
                    "${index + 1}. ${row.displayName}  ·  ${row.platform}",
                    Modifier.padding(top = 3.dp),
                    size = 11.0,
                    maxLines = 1,
                )
            }
            if (order.size > 3) {
                MonoText(
                    "+ ${order.size - 3} more",
                    Modifier.padding(top = 5.dp),
                    size = 10.5,
                    tint = t.faint,
                )
            }
        }
    }
}

@Composable
private fun ConfigCard(
    onRestart: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    Glass(radius = 20.dp) {
        CardBody(
            "Import or export the router configuration. The format is the same " +
                "declarative config FreeLLMAPI uses, so files move between them."
        )
        Spacer(Modifier.height(13.dp))
        // Reload, unbench, and re-ask every provider what it serves. The
        // same thing a fresh app start does, for when the answer changed
        // while the app was open.
        RkButton(
            label = "Restart router",
            onClick = onRestart,
            glyph = Ph.REFRESH,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            RkButton(
                label = "Import",
                onClick = onImport,
                glyph = Ph.DOWNLOAD,
                tone = ButtonTone.NEUTRAL,
                height = 40.dp,
                radius = 13.dp,
                fontSize = 13.0,
                modifier = Modifier.weight(1f),
            )
            RkButton(
                label = "Export",
                onClick = onExport,
                glyph = Ph.UPLOAD,
                tone = ButtonTone.NEUTRAL,
                height = 40.dp,
                radius = 13.dp,
                fontSize = 13.0,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProviderCard(
    row: ProviderRow,
    onAddKey: () -> Unit,
    onRemoveKey: (String) -> Unit,
    onToggleKey: (String, Boolean) -> Unit,
    onRefreshModels: () -> Unit,
    onToggleModel: (String, Boolean) -> Unit,
    onSetAllModels: (Boolean) -> Unit,
    onClearCooldown: (String) -> Unit,
) {
    val t = LocalTokens.current
    var expanded by remember { mutableStateOf(false) }

    Glass(
        radius = 18.dp,
        padding = PaddingValues(15.dp),
        modifier = Modifier.animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .then(
                        if (row.configured) Modifier.shadow(6.dp, CircleShape) else Modifier
                    )
                    .clip(CircleShape)
                    .background(if (row.configured) t.accLt else t.faint)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(row.provider.label, color = t.ink, style = rk(500, 14.5, 1.25))
                Spacer(Modifier.height(3.dp))
                MonoText(
                    "${row.models.count { it.spec.enabled }} of ${row.models.size} models enabled",
                    size = 11.0,
                )
            }
            Spacer(Modifier.width(10.dp))
            RkChipButton(if (expanded) "Hide" else "Manage", onClick = { expanded = !expanded })
        }

        if (expanded) {
            Spacer(Modifier.height(14.dp))
            GlassDivider()
            Spacer(Modifier.height(13.dp))

            SectionLabel("Keys", tint = t.faint)
            if (row.keys.isEmpty()) {
                Text(
                    "None yet.",
                    Modifier.padding(top = 8.dp),
                    color = t.mut,
                    style = rk(400, 12.5, 1.5),
                )
            } else {
                row.keys.forEach { key ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(key.label, color = t.ink, style = rk(400, 13.0, 1.3))
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PhIcon(Ph.LOCK, 11.0, t.accLt)
                                Spacer(Modifier.width(6.dp))
                                // Never render the secret, even masked in full:
                                // a shoulder-surfable prefix is enough to
                                // identify which key this is.
                                MonoText(
                                    if (key.hasSecret) "•••• ${key.secret!!.takeLast(4)}"
                                    else "no secret stored",
                                    size = 11.0,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        RkSwitch(
                            checked = key.enabled,
                            onChange = { onToggleKey(key.label, it) },
                        )
                        Spacer(Modifier.width(4.dp))
                        RkTextAction(
                            "Remove",
                            onClick = { onRemoveKey(key.label) },
                            tint = t.err,
                            fontSize = 12.0,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                RkButton(
                    label = "Add key",
                    onClick = onAddKey,
                    glyph = Ph.PLUS,
                    height = 38.dp,
                    radius = 12.dp,
                    fontSize = 12.5,
                    modifier = Modifier.weight(1f),
                )
                RkButton(
                    label = "Refresh models",
                    onClick = onRefreshModels,
                    tone = ButtonTone.NEUTRAL,
                    height = 38.dp,
                    radius = 12.dp,
                    fontSize = 12.5,
                    enabled = row.configured,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Models", Modifier.weight(1f), tint = t.faint)
                // Bulk, because a provider that lists 300 ids makes
                // per-row tapping a chore rather than a choice.
                RkTextAction("Enable all", onClick = { onSetAllModels(true) }, fontSize = 12.0)
                Spacer(Modifier.width(6.dp))
                RkTextAction(
                    "Disable all",
                    onClick = { onSetAllModels(false) },
                    tint = t.mut,
                    fontSize = 12.0,
                )
            }
            // Ordered by the router's own score, best first, so the list
            // reads the same way the chain does.
            row.models.forEach { model ->
                ModelLine(
                    model = model,
                    onToggle = { onToggleModel(model.spec.key, it) },
                    onClearCooldown = { onClearCooldown(model.spec.modelId) },
                )
            }
        }
    }
}

@Composable
private fun ModelLine(model: ModelRow, onToggle: (Boolean) -> Unit, onClearCooldown: () -> Unit) {
    val t = LocalTokens.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        model.spec.displayName,
                        color = t.ink,
                        style = rk(400, 13.0, 1.3),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // The score only exists for a model the router would consider,
                    // so its absence is information too.
                    model.score?.let {
                        Spacer(Modifier.width(8.dp))
                        MonoText(it.fmt(3), size = 10.5, tint = t.accLt)
                    }
                }
                val detail = buildString {
                    append(model.spec.sizeLabel)
                    model.spec.contextWindow?.let { append(" · ${it / 1024}k ctx") }
                    if (!model.spec.supportsTools) append(" · no tools")
                    model.health?.let { h ->
                        if (h.successes + h.failures > 0) {
                            val rate = h.successes / (h.successes + h.failures)
                            append(" · ${(rate * 100).fmt(0)}% ok")
                        }
                        if (h.tokensPerSecond > 0) append(" · ${h.tokensPerSecond.fmt(0)} tok/s")
                    }
                    model.utilization?.let { append(" · ${(it * 100).fmt(0)}% quota used") }
                }
                MonoText(detail, Modifier.padding(top = 3.dp), size = 10.5)
                if (model.cooldownRemainingMs > 0) {
                    Row(
                        Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val badge = RoundedCornerShape(7.dp)
                        Row(
                            Modifier
                                .clip(badge)
                                .background(t.errSoft)
                                .border(1.dp, t.err.copy(alpha = 0.3f), badge)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PhIcon(Ph.SNOWFLAKE, 10.0, t.err)
                            Spacer(Modifier.width(5.dp))
                            MonoText(
                                "cooling down ${model.cooldownRemainingMs / 1000}s",
                                size = 10.5,
                                tint = t.err,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        RkTextAction(
                            "Clear",
                            onClick = onClearCooldown,
                            tint = t.mut,
                            fontSize = 11.5,
                        )
                    }
                }
            }
            Spacer(Modifier.width(11.dp))
            RkSwitch(checked = model.spec.enabled, onChange = onToggle)
        }
        GlassDivider()
    }
}

@Composable
private fun UsageCard(rows: List<io.reyaak.core.data.UsageRow>) {
    val t = LocalTokens.current
    Glass(radius = 18.dp, padding = PaddingValues(horizontal = 15.dp, vertical = 6.dp)) {
        val shown = rows.take(10)
        shown.forEachIndexed { index, row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoText(
                    "${row.platform} · ${row.modelId?.substringAfterLast('/') ?: "?"}",
                    Modifier.weight(1f),
                    size = 11.5,
                    tint = t.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                MonoText(
                    "${row.turns} turns · ${row.totalTokens} tok · ${row.avgLatencyMs.toInt()}ms",
                    size = 10.5,
                )
            }
            if (index < shown.lastIndex) GlassDivider()
        }
        // The list is capped, so say so rather than truncating in silence.
        if (rows.size > 10) {
            GlassDivider()
            MonoText(
                "…and ${rows.size - 10} more",
                Modifier.padding(vertical = 12.dp),
                size = 10.5,
                tint = t.faint,
            )
        }
    }
}

/**
 * Reorder the chain, one step at a time.
 *
 * Up/down buttons rather than drag-and-drop: the animation is what makes a move
 * legible, and `animateItem` on a keyed lazy list gives that for free, whereas
 * long-press drag on a phone-sized list of forty rows means a lot of scrolling
 * while holding a finger down. The list re-sorts from persisted state, so each
 * tap animates the row into its new place.
 */
@Composable
private fun OrderEditorDialog(
    order: List<ChainRow>,
    onMove: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalTokens.current
    val haptics = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.sheet,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Chain order", color = t.ink, style = rk(600, 18.0, 1.25)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(order, key = { _, row -> row.modelKey }) { index, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonoText("${index + 1}".padStart(2), size = 11.0, tint = t.faint)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.displayName,
                                color = t.ink,
                                style = rk(400, 13.5, 1.2),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            MonoText(row.platform, size = 10.5, tint = t.faint, maxLines = 1)
                        }
                        MoveButton(Ph.ARROW_UP, index > 0) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMove(row.modelKey, -1)
                        }
                        MoveButton(Ph.ARROW_DOWN, index < order.lastIndex) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMove(row.modelKey, 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = t.accLt, style = rk(500, 13.5, 1.0))
            }
        },
    )
}

@Composable
private fun MoveButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(32.dp)
            .clip(shape)
            .background(if (enabled) t.g1 else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PhIcon(glyph, 14.0, if (enabled) t.ink else t.faint) }
}

@Composable
private fun AddKeyDialog(
    platform: String,
    consoleUrl: String?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onOpenConsole: (String) -> Unit,
) {
    val t = LocalTokens.current
    var label by remember { mutableStateOf("default") }
    var secret by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.sheet,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Add $platform key", color = t.ink, style = rk(600, 18.0, 1.25)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RkLabelledField(
                    label = "API key",
                    value = secret,
                    onValueChange = { secret = it },
                    placeholder = "sk-…",
                )
                RkLabelledField(
                    label = "Label",
                    value = label,
                    onValueChange = { label = it },
                    placeholder = "default",
                )
                FootNote("Add a second key with a different label for more free quota.")
                if (!consoleUrl.isNullOrBlank()) {
                    RkTextAction("Get a key", onClick = { onOpenConsole(consoleUrl) })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label, secret) },
                enabled = secret.isNotBlank(),
            ) { Text("Save", color = t.accLt, style = rk(500, 13.5, 1.0)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = t.mut, style = rk(500, 13.5, 1.0))
            }
        },
    )
}
