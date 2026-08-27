package io.reyaak.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()

    var keyDialogFor by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var showOrderEditor by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionTitle("Routing strategy")
                StrategyPicker(state.strategy) { vm.setStrategy(it) }
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
                SectionTitle("Configuration")
                ConfigCard(
                    onRestart = { vm.restartRouter() },
                    onImport = {
                        onPickDocument { text ->
                            // Ask merge-or-replace rather than assuming: replacing
                            // would silently drop the key in use right now.
                            if (text.isNullOrBlank()) vm.import("", merge = true)
                            else pendingImport = text
                        }
                    },
                    onExport = { withSecrets ->
                        onSaveDocument("reyaak-router.json", vm.exportJson(withSecrets)) {
                            vm.onExported(withSecrets)
                        }
                    },
                )
            }

            item { SectionTitle("Providers") }

            items(state.providers, key = { it.provider.id }) { row ->
                ProviderCard(
                    row = row,
                    onAddKey = { keyDialogFor = row.provider.id },
                    onRemoveKey = { label -> vm.removeKey(row.provider.id, label) },
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
                    SectionTitle("Usage")
                    UsageCard(usage)
                }
            }

            item { Spacer(Modifier.height(NavBarSpace)) }
        }

        notice?.let { current ->
            NoticeBanner(
                text = current.text,
                warnings = current.warnings,
                isError = current.isError,
                onDismiss = { vm.dismissNotice() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }

        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
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

    pendingImport?.let { json ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import router config") },
            text = {
                Text(
                    "Merge keeps your current keys and layers this file on top. " +
                        "Replace discards everything you have now."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.import(json, merge = true)
                    pendingImport = null
                }) { Text("Merge") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.import(json, merge = false)
                    pendingImport = null
                }) { Text("Replace") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun StrategyPicker(current: RoutingStrategy, onPick: (RoutingStrategy) -> Unit) {
    val options = listOf(
        RoutingStrategy.BALANCED to "Balanced",
        RoutingStrategy.FASTEST to "Fastest",
        RoutingStrategy.SMARTEST to "Smartest",
        RoutingStrategy.RELIABLE to "Reliable",
        RoutingStrategy.PRIORITY to "Manual order",
    )
    Column {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (strategy, label) ->
                FilterChip(
                    selected = current == strategy,
                    onClick = { onPick(strategy) },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when (current) {
                RoutingStrategy.BALANCED -> "Reliability leads; speed and intelligence split the rest."
                RoutingStrategy.FASTEST -> "Prefers throughput, but a fast broken model still loses."
                RoutingStrategy.SMARTEST -> "Prefers capability, with reliability keeping it honest."
                RoutingStrategy.RELIABLE -> "Whatever is most likely to just work."
                RoutingStrategy.PRIORITY -> "Follows your explicit order and skips scoring."
                RoutingStrategy.CUSTOM -> "Custom weights from an imported config."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChainCard(chain: List<ChainRow>, exclusions: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Next request would try",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            if (chain.isEmpty()) {
                Text(
                    "Nothing is routable yet. Add a provider key below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Scores are relative, so the bar is drawn against the leader
                // rather than against 1.0: what matters is the gap to the model
                // that would actually be tried first.
                val top = chain.maxOf { it.score }.coerceAtLeast(0.0001)
                chain.forEachIndexed { index, row ->
                    ChainLine(index, row, row.score / top)
                }
            }
            if (exclusions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Excluded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                exclusions.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Manual order",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (order.isEmpty()) "Nothing routable to order yet."
                        else "${order.size} models, tried top to bottom.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClear, enabled = order.isNotEmpty()) { Text("Reset") }
                OutlinedButton(onClick = onEdit, enabled = order.isNotEmpty()) { Text("Edit") }
            }
            if (order.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                order.take(3).forEachIndexed { index, row ->
                    Text(
                        "${index + 1}. ${row.displayName}  ·  ${row.platform}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (order.size > 3) {
                    Text(
                        "+ ${order.size - 3} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
    val haptics = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chain order") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(order, key = { _, row -> row.modelKey }) { index, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}".padStart(2),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                            Text(
                                row.platform,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onMove(row.modelKey, -1)
                            },
                            enabled = index > 0,
                        ) { Text("↑") }
                        TextButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onMove(row.modelKey, 1)
                            },
                            enabled = index < order.lastIndex,
                        ) { Text("↓") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** One chain row: rank, model, score, and a bar for the score at a glance. */
@Composable
private fun ChainLine(index: Int, row: ChainRow, fraction: Double) {
    val leading = index == 0
    val tint = if (leading) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${index + 1}".padStart(2),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (leading) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (leading) MaterialTheme.colorScheme.onSurface else tint,
                    maxLines = 1,
                )
                Text(
                    row.platform,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                row.score.fmt(3),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = tint,
            )
        }
        // The bar animates so a score that moved after a turn reads as movement
        // rather than as a different number in the same place.
        val animated by animateFloatAsState(
            targetValue = fraction.toFloat().coerceIn(0f, 1f),
            label = "chainScore",
        )
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 3.dp),
            color = tint,
            trackColor = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ConfigCard(
    onRestart: () -> Unit,
    onImport: () -> Unit,
    onExport: (Boolean) -> Unit,
) {
    var showExportChoice by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Import or export the router configuration. The format is the same " +
                    "declarative config FreeLLMAPI uses, so files move between them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // Reload, unbench, and re-ask every provider what it serves. The
            // same thing a fresh app start does, for when the answer changed
            // while the app was open.
            OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
                Text("Restart router")
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Text("Import")
                }
                OutlinedButton(
                    onClick = { showExportChoice = true },
                    modifier = Modifier.weight(1f),
                ) { Text("Export") }
            }
        }
    }

    if (showExportChoice) {
        AlertDialog(
            onDismissRequest = { showExportChoice = false },
            title = { Text("Include API keys?") },
            text = {
                Text(
                    "An export without keys describes your setup safely. " +
                        "With keys it is a credential file, only for moving to another device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportChoice = false
                    onExport(false)
                }) { Text("Without keys") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportChoice = false
                    onExport(true)
                }) { Text("With keys") }
            },
        )
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
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (row.configured) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(8.dp),
                ) {}
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.provider.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${row.models.count { it.spec.enabled }} of ${row.models.size} models enabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Manage")
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))

                Text(
                    "Keys",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.keys.isEmpty()) {
                    Text(
                        "None yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    row.keys.forEach { key ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    key.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    // Never render the secret, even masked in full:
                                    // a shoulder-surfable prefix is enough to
                                    // identify which key this is.
                                    if (key.hasSecret) "•••• ${key.secret!!.takeLast(4)}"
                                    else "no secret stored",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = key.enabled,
                                onCheckedChange = { onToggleKey(key.label, it) },
                            )
                            TextButton(onClick = { onRemoveKey(key.label) }) { Text("Remove") }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddKey, modifier = Modifier.weight(1f)) {
                        Text("Add key")
                    }
                    OutlinedButton(
                        onClick = onRefreshModels,
                        enabled = row.configured,
                        modifier = Modifier.weight(1f),
                    ) { Text("Refresh models") }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Models",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // Bulk, because a provider that lists 300 ids makes
                    // per-row tapping a chore rather than a choice.
                    TextButton(onClick = { onSetAllModels(true) }) { Text("Enable all") }
                    TextButton(onClick = { onSetAllModels(false) }) { Text("Disable all") }
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
}

@Composable
private fun ModelLine(model: ModelRow, onToggle: (Boolean) -> Unit, onClearCooldown: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.spec.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // The score only exists for a model the router would consider,
                // so its absence is information too.
                model.score?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        it.fmt(3),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model.cooldownRemainingMs > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "cooling down ${model.cooldownRemainingMs / 1000}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onClearCooldown) { Text("Clear") }
                }
            }
        }
        Switch(checked = model.spec.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun UsageCard(rows: List<io.reyaak.core.data.UsageRow>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            rows.take(10).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${row.platform} · ${row.modelId?.substringAfterLast('/') ?: "?"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        "${row.turns} turns · ${row.totalTokens} tok · ${row.avgLatencyMs.toInt()}ms",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddKeyDialog(
    platform: String,
    consoleUrl: String?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onOpenConsole: (String) -> Unit,
) {
    var label by remember { mutableStateOf("default") }
    var secret by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $platform key") },
        text = {
            Column {
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("API key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    supportingText = { Text("Add a second key with a different label for more free quota.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!consoleUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onOpenConsole(consoleUrl) }) {
                        Text("Get a key")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label, secret) },
                enabled = secret.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NoticeBanner(
    text: String,
    warnings: List<String>,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (warnings.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                // Warnings are shown rather than counted: an import that silently
                // skipped a key the user needs is worse than a noisy banner.
                warnings.take(6).forEach {
                    Text(
                        "• $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (warnings.size > 6) {
                    Text(
                        "…and ${warnings.size - 6} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Dismiss")
            }
        }
    }
}
