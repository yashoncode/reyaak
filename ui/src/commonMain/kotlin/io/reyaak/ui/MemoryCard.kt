package io.reyaak.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.reyaak.core.ReyaakCore
import io.reyaak.core.data.MemoryEntity
import io.reyaak.core.data.MemoryKind
import kotlinx.coroutines.launch

/**
 * What the agent has remembered, and the means to correct it.
 *
 * Every entry here is visible and editable on purpose. An agent that writes its
 * own memory and hides the result is the least debuggable thing you can build:
 * a single wrong fact would quietly colour every later answer with no way to
 * find it. Pinning protects an entry from curation, archiving is how one stops
 * being used, and deleting is the only irreversible action, so it is the only
 * one that asks.
 */
@Composable
fun MemoryCard(core: ReyaakCore) {
    val t = LocalTokens.current
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val memories by produceState(initialValue = emptyList<MemoryEntity>(), core) {
        core.memory.observeAll().collect { value = it }
    }
    var showArchived by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }

    val active = memories.filterNot { it.archived }
    val archived = memories.filter { it.archived }
    val shown = if (showArchived) active + archived else active

    Glass(radius = 20.dp, modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.Top) {
            PhIcon(Ph.BRAIN, 17.0, t.accLt, Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                CardTitle("Memory")
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        active.isEmpty() -> "Nothing yet. The agent writes here as it learns."
                        else -> "${active.size} remembered, carried into every conversation."
                    },
                    color = t.mut,
                    style = rk(400, 12.0, 1.45),
                )
            }
            if (archived.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                RkChipButton(
                    label = if (showArchived) "Hide" else "Archived ${archived.size}",
                    onClick = { showArchived = !showArchived },
                )
            }
        }

        if (shown.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            GlassDivider()
        }

        shown.forEach { entry ->
            MemoryRow(
                entry = entry,
                onEdit = { editing = entry },
                onTogglePin = { scope.launch { core.memory.setPinned(entry.id, !entry.pinned) } },
                onToggleArchive = {
                    scope.launch { core.memory.setArchived(entry.id, !entry.archived) }
                },
                onDelete = {
                    feedback.confirm(
                        Confirmation(
                            title = "Forget this?",
                            body = "“${entry.content.take(90)}” is deleted from the local " +
                                "database. Archiving keeps it recoverable; this does not.",
                            cta = "Forget",
                            danger = true,
                            run = { scope.launch { core.memory.forget(entry.id) } },
                        )
                    )
                },
            )
            GlassDivider()
        }

        Spacer(Modifier.height(13.dp))
        FootNote(
            "The agent decides what to write here, and tidies unused entries after a " +
                "quiet spell. Pinned entries are never touched automatically, and " +
                "anything you write yourself is never touched at all."
        )
    }

    editing?.let { entry ->
        EditMemoryDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { text ->
                scope.launch { core.memory.edit(entry.id, text) }
                editing = null
            },
        )
    }
}

@Composable
private fun MemoryRow(
    entry: MemoryEntity,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = LocalTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            // Archived entries stay legible but stop competing with live ones.
            .alpha(if (entry.archived) 0.5f else 1f)
            .padding(vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.content,
                    color = t.ink,
                    style = rk(400, 13.0, 1.45),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The kind and the use count are the two facts that explain
                    // why an entry is still here, so they are the two shown.
                    MonoText(
                        buildString {
                            append(MemoryKind.fromWire(entry.kind).wireName)
                            append(" · used ").append(entry.useCount).append('x')
                            if (!entry.agentCreated) append(" · yours")
                            if (entry.archived) append(" · archived")
                        },
                        size = 10.5,
                        tint = t.faint,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            RowIcon(Ph.PIN, onTogglePin, active = entry.pinned)
            RowIcon(Ph.PENCIL, onEdit)
            RowIcon(if (entry.archived) Ph.COUNTER_CLOCKWISE else Ph.ARCHIVE, onToggleArchive)
            RowIcon(Ph.TRASH, onDelete)
        }
    }
}

@Composable
private fun EditMemoryDialog(
    entry: MemoryEntity,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val t = LocalTokens.current
    var draft by remember(entry.id) { mutableStateOf(entry.content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.sheet,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Edit memory", color = t.ink, style = rk(600, 18.0, 1.25)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RkField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = "One self-contained sentence",
                    minLines = 2,
                    maxLines = 6,
                )
                FootNote(
                    "Editing marks this as yours, so the agent will not curate it away."
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = draft.isNotBlank() && draft != entry.content,
            ) { Text("Save", color = t.accLt, style = rk(500, 13.5, 1.0)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = t.mut, style = rk(500, 13.5, 1.0))
            }
        },
    )
}
