package io.reyaak.core.skills

import io.reyaak.router.config.ConfigPersistence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A named block of instructions the agent loads when the user turns it on.
 *
 * Instructions and nothing else. A skill that also carried a tool allowlist
 * would give two places to turn a tool off and no way to tell which one is
 * winning, so tools stay where the user configured them.
 */
@Serializable
data class Skill(
    /** Stable id. Slug for the builtins, a timestamp-free slug for custom ones. */
    val id: String,
    val name: String,
    /** One line for the list, so a switch is not the only thing to read. */
    val summary: String = "",
    val instructions: String,
    val enabled: Boolean = false,
    /** Shipped with the app. Editable, but never deletable: it can be reset. */
    val builtin: Boolean = false,
)

@Serializable
data class SkillSet(val skills: List<Skill> = emptyList())

/**
 * The skills the agent knows, and which of them are active.
 *
 * Persisted whole rather than as a diff against the builtins: a skill is a piece
 * of text the user is free to rewrite, and storing "the builtin plus an override"
 * would mean deciding what happens when the builtin text changes in an app
 * update. Storing the file the user last saw has no such question.
 */
class SkillStore(private val persistence: ConfigPersistence) {

    private val _skills = MutableStateFlow(BUILTINS)
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    suspend fun load() {
        val stored = persistence.read()?.takeIf { it.isNotBlank() } ?: return
        val parsed = runCatching { json.decodeFromString<SkillSet>(stored) }.getOrNull() ?: return
        // A builtin added in a later version is merged in rather than lost, and
        // one the user edited keeps their text: theirs wins on id.
        val known = parsed.skills.map { it.id }.toSet()
        _skills.value = parsed.skills + BUILTINS.filterNot { it.id in known }
    }

    private suspend fun write(next: List<Skill>) {
        _skills.value = next
        persistence.write(json.encodeToString(SkillSet(next)))
    }

    suspend fun setEnabled(id: String, enabled: Boolean) =
        write(_skills.value.map { if (it.id == id) it.copy(enabled = enabled) else it })

    /** Add or replace. A blank name or blank instructions is not a skill. */
    suspend fun save(skill: Skill): Boolean {
        if (skill.name.isBlank() || skill.instructions.isBlank()) return false
        val existing = _skills.value.indexOfFirst { it.id == skill.id }
        val next = _skills.value.toMutableList()
        if (existing >= 0) next[existing] = skill else next += skill
        write(next)
        return true
    }

    /** Custom skills only. A builtin is reset instead, so the list cannot empty out. */
    suspend fun delete(id: String) {
        val target = _skills.value.firstOrNull { it.id == id } ?: return
        if (target.builtin) {
            BUILTINS.firstOrNull { it.id == id }?.let { original ->
                write(_skills.value.map { if (it.id == id) original.copy(enabled = target.enabled) else it })
            }
            return
        }
        write(_skills.value.filterNot { it.id == id })
    }

    /**
     * The active skills as one prompt section, or null when none are on.
     *
     * Capped in total, because this is prepended to a history budget sized for
     * the smallest model the router might pick. Skills are taken in list order
     * until the cap binds, so what gets dropped is predictable rather than
     * whichever one happened to be long.
     */
    fun promptSection(): String? {
        val active = _skills.value.filter { it.enabled && it.instructions.isNotBlank() }
        if (active.isEmpty()) return null

        val kept = mutableListOf<Skill>()
        var budget = MAX_CHARS
        for (skill in active) {
            val cost = skill.name.length + skill.instructions.length + 4
            if (cost > budget && kept.isNotEmpty()) break
            kept += skill
            budget -= cost
        }

        return buildString {
            appendLine("Active skills. Follow these for this conversation:")
            kept.forEach { skill ->
                appendLine()
                appendLine("## ${skill.name.trim()}")
                appendLine(skill.instructions.trim().take(MAX_CHARS))
            }
        }.trim()
    }

    companion object {
        /** Total characters of skill text allowed into one prompt. */
        const val MAX_CHARS = 2_000

        /**
         * Shipped skills.
         *
         * All off by default: a skill silently rewriting how the agent answers
         * is exactly the kind of surprise nobody can debug from the transcript.
         */
        val BUILTINS: List<Skill> = listOf(
            Skill(
                id = "researcher",
                name = "Researcher",
                summary = "Search, read, then cite.",
                instructions = """
                    Answer from sources, not memory, whenever a claim is checkable.
                    Search first, then read the page that looks most authoritative
                    rather than trusting a snippet. Give the answer, then the URLs
                    it came from. If the sources disagree, say so instead of
                    picking one silently.
                """.trimIndent(),
                builtin = true,
            ),
            Skill(
                id = "coder",
                name = "Coder",
                summary = "Code first, prose second.",
                instructions = """
                    Lead with the code, then at most three lines explaining it.
                    Match the conventions of whatever the user pasted rather than
                    imposing your own. Name the language on every block. Do not
                    add error handling, configuration, or abstraction that was
                    not asked for, and say plainly when something will not work.
                """.trimIndent(),
                builtin = true,
            ),
            Skill(
                id = "brief",
                name = "Brief",
                summary = "Short answers, no preamble.",
                instructions = """
                    Answer in at most four sentences. No preamble, no restating
                    the question, no summary of what you just said. If the honest
                    answer needs more room, give the one-line version first and
                    offer to expand.
                """.trimIndent(),
                builtin = true,
            ),
            Skill(
                id = "teacher",
                name = "Teacher",
                summary = "Explain from first principles.",
                instructions = """
                    Explain the mechanism, not just the conclusion: what is
                    happening underneath, and why it works that way. Use one
                    concrete example before any general rule. Flag the common
                    misconception if there is one. Assume intelligence, not
                    background knowledge.
                """.trimIndent(),
                builtin = true,
            ),
        )

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
