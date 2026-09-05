package io.reyaak.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
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
    val t = LocalTokens.current
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val skills by core.skills.skills.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Skill?>(null) }
    var showArchived by remember { mutableStateOf(false) }

    val archived = skills.filter { it.archived }
    val shown = if (showArchived) skills else skills.filterNot { it.archived }

    Glass(radius = 20.dp, modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                CardTitle("Skills")
                Spacer(Modifier.height(4.dp))
                Text(
                    skills.count { it.enabled && !it.archived }.let {
                        if (it == 0) "None active. The agent answers as itself."
                        else "$it active, applied from your next message."
                    },
                    color = t.mut,
                    style = rk(400, 12.0, 1.45),
                )
            }
            Spacer(Modifier.width(10.dp))
            if (archived.isNotEmpty()) {
                RkChipButton(
                    label = if (showArchived) "Hide" else "Archived ${archived.size}",
                    onClick = { showArchived = !showArchived },
                )
                Spacer(Modifier.width(8.dp))
            }
            RkChipButton(
                label = "New",
                accent = true,
                onClick = { editing = Skill(id = newId(skills), name = "", instructions = "") },
            )
        }

        Spacer(Modifier.height(14.dp))
        GlassDivider()

        if (skills.isEmpty()) {
            Text(
                "None yet. A skill is a few lines telling the agent how to answer.",
                Modifier.padding(vertical = 14.dp),
                color = t.mut,
                style = rk(400, 12.5, 1.5),
            )
        }

        shown.forEach { skill ->
            Row(
                Modifier
                    .fillMaxWidth()
                    // Archived stays readable but stops competing with live ones.
                    .alpha(if (skill.archived) 0.5f else 1f)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(skill.name, color = t.ink, style = rk(400, 13.5, 1.3))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        skill.summary.ifBlank { skill.instructions.take(70) },
                        color = t.mut,
                        style = rk(400, 11.5, 1.45),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Only for what the agent wrote: for a shipped or hand-written
                    // skill the version and the use count explain nothing, because
                    // neither one is ever curated on them.
                    if (skill.agentCreated) {
                        Spacer(Modifier.height(5.dp))
                        MonoText(
                            "written by the agent · v${skill.version} · " +
                                "used ${skill.usageCount}x" +
                                if (skill.archived) " · archived" else "",
                            size = 10.5,
                            tint = t.faint,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                // Curation can only reach agent-written skills, so only those
                // get the controls that exist to stop it.
                if (skill.agentCreated) {
                    RowIcon(
                        Ph.PIN,
                        onClick = {
                            scope.launch { core.skills.setPinned(skill.id, !skill.pinned) }
                        },
                        active = skill.pinned,
                    )
                    RowIcon(
                        if (skill.archived) Ph.COUNTER_CLOCKWISE else Ph.ARCHIVE,
                        onClick = {
                            scope.launch { core.skills.setArchived(skill.id, !skill.archived) }
                        },
                    )
                }
                RowIcon(Ph.PENCIL, onClick = { editing = skill })
                Spacer(Modifier.width(5.dp))
                RkSwitch(
                    checked = skill.enabled && !skill.archived,
                    onChange = { on -> scope.launch { core.skills.setEnabled(skill.id, on) } },
                )
            }
            GlassDivider()
        }

        Spacer(Modifier.height(13.dp))
        FootNote(
            "Active skills are added to the prompt after the base rules and " +
                "before your personalisation, so your own voice still wins. The agent " +
                "writes skills of its own after a job worth repeating; editing one " +
                "makes it yours, and it stops being curated."
        )
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
                // A built-in resets to its shipped wording, so only a custom
                // skill can actually lose text. That one asks first.
                if (skill.builtin) {
                    scope.launch { core.skills.delete(skill.id) }
                    editing = null
                } else {
                    editing = null
                    feedback.confirm(
                        Confirmation(
                            title = "Delete this skill?",
                            body = "“${skill.name}” and its instructions are removed. " +
                                "This cannot be undone.",
                            cta = "Delete",
                            danger = true,
                            run = { scope.launch { core.skills.delete(skill.id) } },
                        )
                    )
                }
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
    val t = LocalTokens.current
    var name by remember(skill.id) { mutableStateOf(skill.name) }
    var summary by remember(skill.id) { mutableStateOf(skill.summary) }
    var instructions by remember(skill.id) { mutableStateOf(skill.instructions) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.sheet,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                if (skill.name.isBlank()) "New skill" else skill.name,
                color = t.ink,
                style = rk(600, 18.0, 1.25),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RkLabelledField(
                    label = "Name",
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Terse answers",
                )
                RkLabelledField(
                    label = "One-line summary",
                    value = summary,
                    onValueChange = { summary = it },
                    placeholder = "No preamble, no offers to help further",
                )
                RkLabelledField(
                    label = "Instructions",
                    value = instructions,
                    onValueChange = { instructions = it },
                    placeholder = "Answer in at most three sentences.",
                    maxLines = 10,
                )
                AnimatedVisibility(visible = skill.builtin) {
                    FootNote(
                        "Built in. Deleting resets it to the shipped wording " +
                            "rather than removing it."
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
            ) { Text("Save", color = t.accLt, style = rk(500, 13.5, 1.0)) }
        },
        dismissButton = {
            Row {
                if (skill.name.isNotBlank()) {
                    TextButton(onClick = onDelete) {
                        Text(
                            if (skill.builtin) "Reset" else "Delete",
                            color = t.err,
                            style = rk(500, 13.5, 1.0),
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = t.mut, style = rk(500, 13.5, 1.0))
                }
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
