package io.reyaak.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.reyaak.core.ReyaakCore
import io.reyaak.core.persona.Persona
import kotlinx.coroutines.launch

/**
 * Personalisation: who the agent is, and who it is talking to.
 *
 * Free-text fields rather than tone presets, because the user's own words are
 * the whole feature. Applies from the next message: the persona is read per
 * turn, so there is nothing to restart.
 */
@Composable
fun PersonalisationCard(core: ReyaakCore) {
    val scope = rememberCoroutineScope()
    val saved by core.persona.persona.collectAsStateWithLifecycle()

    // Seeded from the store and re-seeded whenever it changes, which is what
    // makes the fields show the persisted persona once startup has read it.
    var draft by remember(saved) { mutableStateOf(saved) }
    var savedNote by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Personalisation",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Shapes how the agent talks to you. Every field is optional, and " +
                    "changes apply from your next message.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            PersonaField(
                value = draft.agentName,
                onValueChange = { draft = draft.copy(agentName = it) },
                label = "What should the agent be called?",
                placeholder = "Reyaak",
            )
            PersonaField(
                value = draft.userName,
                onValueChange = { draft = draft.copy(userName = it) },
                label = "What should it call you?",
                placeholder = "Yash",
            )
            PersonaField(
                value = draft.traits,
                onValueChange = { draft = draft.copy(traits = it) },
                label = "What character should it have?",
                placeholder = "Dry, skeptical, no flattery",
                lines = 3,
            )
            PersonaField(
                value = draft.about,
                onValueChange = { draft = draft.copy(about = it) },
                label = "What should it know about you?",
                placeholder = "Android dev, Kotlin and KMP, prefers short answers",
                lines = 4,
            )
            PersonaField(
                value = draft.instructions,
                onValueChange = { draft = draft.copy(instructions = it) },
                label = "Anything else it should follow?",
                placeholder = "Show code before explaining it",
                lines = 4,
            )

            Row {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            core.persona.save(draft)
                            savedNote = true
                        }
                    },
                    enabled = draft != saved,
                ) { Text("Save") }
                if (draft != saved) {
                    TextButton(onClick = { draft = saved }) { Text("Revert") }
                } else if (!draft.isEmpty) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                core.persona.save(Persona())
                                savedNote = false
                            }
                        }
                    ) { Text("Clear") }
                }
            }

            if (savedNote && draft == saved) {
                Text(
                    "Saved. It applies from your next message.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PersonaField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    lines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = lines == 1,
        maxLines = lines,
        shape = RoundedCornerShape(12.dp),
    )
}
