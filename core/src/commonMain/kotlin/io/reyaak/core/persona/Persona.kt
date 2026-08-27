package io.reyaak.core.persona

import io.reyaak.router.config.ConfigPersistence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How the user wants the agent to behave.
 *
 * Free text rather than a preset list: a persona is the one setting where the
 * user's own words are the feature, and a dropdown of "friendly / formal /
 * witty" only re-encodes them worse. Every field is optional, and an empty
 * persona adds nothing to the prompt at all.
 */
@Serializable
data class Persona(
    /** What the agent calls itself. Blank keeps "Reyaak". */
    val agentName: String = "",
    /** What the agent calls the user. */
    val userName: String = "",
    /** Character, e.g. "dry, skeptical, no flattery". */
    val traits: String = "",
    /** Standing context about the user: work, tools, preferences. */
    val about: String = "",
    /** Anything else, in the user's own words. */
    val instructions: String = "",
) {
    val isEmpty: Boolean
        get() = listOf(agentName, userName, traits, about, instructions).all { it.isBlank() }

    /**
     * The persona as a prompt section, or null when nothing has been set.
     *
     * Rendered as labelled lines rather than prose so the model reads it as
     * configuration it was given, not as something the user said in the
     * conversation.
     */
    fun promptSection(): String? {
        if (isEmpty) return null
        return buildString {
            appendLine("The user has personalised you. Honour this over your default style,")
            appendLine("but never over a direct instruction in the conversation itself.")
            if (agentName.isNotBlank()) appendLine("- Your name: ${agentName.trim()}")
            if (userName.isNotBlank()) appendLine("- Call the user: ${userName.trim()}")
            if (traits.isNotBlank()) appendLine("- Your character: ${traits.trim()}")
            if (about.isNotBlank()) appendLine("- About the user: ${about.trim()}")
            if (instructions.isNotBlank()) appendLine("- Their instructions: ${instructions.trim()}")
        }.trim()
    }
}

/**
 * Holds the persona and persists it.
 *
 * Reuses the [ConfigPersistence] port because the contract is identical, read
 * and write one string, and the host already knows how to give one: a second
 * port would be the same two methods under a different name. The bytes hold no
 * secrets, so the host is free to back this with a plain file.
 */
class PersonaStore(private val persistence: ConfigPersistence) {

    private val _persona = MutableStateFlow(Persona())
    val persona: StateFlow<Persona> = _persona.asStateFlow()

    suspend fun load() {
        val stored = persistence.read()?.takeIf { it.isNotBlank() } ?: return
        // A persona is a convenience, so a file written by an older or newer
        // build must not take the app down: unreadable means "not personalised".
        _persona.value = runCatching { json.decodeFromString<Persona>(stored) }
            .getOrDefault(Persona())
    }

    suspend fun save(persona: Persona) {
        _persona.value = persona
        persistence.write(json.encodeToString(persona))
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
