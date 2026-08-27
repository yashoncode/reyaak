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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reyaak.core.ReyaakCore
import io.reyaak.core.skills.Skill
import kotlinx.coroutines.launch

/**
 * Skills: instruction packs the user switches on.
 *
 * All off by default, and the text of every one of them is visible and editable
 * here. A skill that silently rewrote how the agent answers, with its wording
 * hidden, would be the least debuggable feature in the app.
 */
@Composable
fun SkillsCard(core: ReyaakCore) {
    val scope = rememberCoroutineScope()
    val skills by core.skills.skills.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Skill?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Skills",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        skills.count { it.enabled }.let {
                            if (it == 0) "None active. The agent answers as itself."
                            else "$it active, applied from your next message."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        editing = Skill(id = newId(skills), name = "", instructions = "")
                    }
                ) { Text("New") }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            skills.forEach { skill ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            skill.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            skill.summary.ifBlank { skill.instructions.take(70) },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    TextButton(onClick = { editing = skill }) { Text("Edit") }
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { on ->
                            scope.launch { core.skills.setEnabled(skill.id, on) }
                        },
                    )
                }
            }

            Text(
                "Active skills are added to the prompt after the base rules and " +
                    "before your personalisation, so your own voice still wins.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    editing?.let { skill ->
        SkillDialog(
            skill = skill,
            onDismiss = { editing = null },
            onSave = {
                scope.launch { core.skills.save(it) }
                editing = null
            },
            onDelete = {
                scope.launch { core.skills.delete(skill.id) }
                editing = null
            },
        )
    }
}

@Composable
private fun SkillDialog(
    skill: Skill,
    onDismiss: () -> Unit,
    onSave: (Skill) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(skill.id) { mutableStateOf(skill.name) }
    var summary by remember(skill.id) { mutableStateOf(skill.summary) }
    var instructions by remember(skill.id) { mutableStateOf(skill.instructions) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (skill.name.isBlank()) "New skill" else skill.name) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("One-line summary") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Instructions") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                AnimatedVisibility(visible = skill.builtin) {
                    Text(
                        "Built in. Deleting resets it to the shipped wording " +
                            "rather than removing it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        skill.copy(
                            name = name.trim(),
                            summary = summary.trim(),
                            instructions = instructions.trim(),
                        )
                    )
                },
                enabled = name.isNotBlank() && instructions.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (skill.name.isNotBlank()) {
                    TextButton(onClick = onDelete) {
                        Text(if (skill.builtin) "Reset" else "Delete")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** A slug that cannot collide with an existing one, without a clock or a UUID. */
private fun newId(existing: List<Skill>): String {
    var n = existing.size + 1
    val taken = existing.map { it.id }.toSet()
    while ("custom-$n" in taken) n++
    return "custom-$n"
}
