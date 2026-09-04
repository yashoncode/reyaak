package io.reyaak.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val t = LocalTokens.current
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val saved by core.persona.persona.collectAsStateWithLifecycle()

    // Seeded from the store and re-seeded whenever it changes, which is what
    // makes the fields show the persisted persona once startup has read it.
    var draft by remember(saved) { mutableStateOf(saved) }
    var savedNote by remember { mutableStateOf(false) }
    val dirty = draft != saved

    Glass(radius = 20.dp) {
        CardTitle("Personalisation")
        Spacer(Modifier.height(5.dp))
        CardBody(
            "Shapes how the agent talks to you. Every field is optional, and " +
                "changes apply from your next message."
        )
        Spacer(Modifier.height(15.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RkLabelledField(
                label = "What should the agent be called?",
                value = draft.agentName,
                onValueChange = { draft = draft.copy(agentName = it); savedNote = false },
                placeholder = "Reyaak",
            )
            RkLabelledField(
                label = "What should it call you?",
                value = draft.userName,
                onValueChange = { draft = draft.copy(userName = it); savedNote = false },
                placeholder = "Yash",
            )
            RkLabelledField(
                label = "What character should it have?",
                value = draft.traits,
                onValueChange = { draft = draft.copy(traits = it); savedNote = false },
                placeholder = "Dry, skeptical, no flattery",
                maxLines = 3,
            )
            RkLabelledField(
                label = "What should it know about you?",
                value = draft.about,
                onValueChange = { draft = draft.copy(about = it); savedNote = false },
                placeholder = "Android dev, Kotlin and KMP, prefers short answers",
                maxLines = 4,
            )
            RkLabelledField(
                label = "Anything else it should follow?",
                value = draft.instructions,
                onValueChange = { draft = draft.copy(instructions = it); savedNote = false },
                placeholder = "Show code before explaining it",
                maxLines = 4,
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            DirtySave(
                dirty = dirty,
                onClick = {
                    scope.launch {
                        core.persona.save(draft)
                        savedNote = true
                    }
                },
                modifier = Modifier.weight(1f),
            )
            if (dirty) {
                RkButton(
                    label = "Revert",
                    onClick = { draft = saved },
                    tone = ButtonTone.NEUTRAL,
                    radius = 13.dp,
                    fontSize = 13.0,
                )
            } else if (!draft.isEmpty) {
                RkButton(
                    label = "Clear",
                    onClick = {
                        scope.launch {
                            core.persona.save(Persona())
                            savedNote = false
                            feedback.say("Personalisation cleared.")
                        }
                    },
                    tone = ButtonTone.NEUTRAL,
                    radius = 13.dp,
                    fontSize = 13.0,
                )
            }
        }

        AnimatedVisibility(
            visible = savedNote && !dirty,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(180)),
        ) {
            Text(
                "Saved. It applies from your next message.",
                Modifier.padding(top = 9.dp),
                color = t.accLt,
                style = rk(400, 11.5, 1.5),
            )
        }
    }
}
