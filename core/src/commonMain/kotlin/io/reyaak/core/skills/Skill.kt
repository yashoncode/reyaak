package io.reyaak.core.skills

import io.reyaak.router.config.ConfigPersistence
import io.reyaak.router.time.epochMillis
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
    /**
     * Bumped on every rewrite, so a procedure that keeps being revised is
     * visible as such. Not a history: keeping every past body of every skill on
     * a phone would cost more than being able to read one.
     */
    val version: Int = 1,
    /** Pinned bypasses every automatic transition, exactly as in memory. */
    val pinned: Boolean = false,
    /** Curated away rather than deleted, and reversible. */
    val archived: Boolean = false,
    /** How many prompts this skill has been part of. What earns it its tokens. */
    val usageCount: Int = 0,
    /** Written by the agent. Only these are ever curated automatically. */
    val agentCreated: Boolean = false,
    /** Last write, for staleness. Zero on the shipped set, which never goes stale. */
    val updatedAt: Long = 0,
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
class SkillStore(
    private val persistence: ConfigPersistence,
    private val now: () -> Long = ::epochMillis,
) {

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

    /**
     * Add or replace. A blank name or blank instructions is not a skill.
     *
     * [byUser] is what decides who owns the result. Rewriting a procedure is a
     * stronger claim on it than pinning it, so a user edit takes the skill out
     * of the curator's reach for good, while the agent revising its own does
     * not hand it back. Same rule as `MemoryStore.edit`.
     */
    suspend fun save(skill: Skill, byUser: Boolean = true): Boolean {
        if (skill.name.isBlank() || skill.instructions.isBlank()) return false
        val existing = _skills.value.indexOfFirst { it.id == skill.id }
        val previous = _skills.value.getOrNull(existing)
        val rewritten = previous != null && previous.instructions != skill.instructions
        val saved = skill.copy(
            version = if (rewritten) previous.version + 1 else skill.version,
            agentCreated = if (byUser) false else skill.agentCreated,
            updatedAt = now(),
        )
        val next = _skills.value.toMutableList()
        if (existing >= 0) next[existing] = saved else next += saved
        write(next)
        return true
    }

    suspend fun setPinned(id: String, pinned: Boolean) =
        write(_skills.value.map { if (it.id == id) it.copy(pinned = pinned) else it })

    suspend fun setArchived(id: String, archived: Boolean) =
        write(_skills.value.map { if (it.id == id) it.copy(archived = archived) else it })

    /**
     * The cheap deterministic curation pass, and the only automatic one.
     *
     * Same four invariants as memory: agent-written only, archive rather than
     * delete, pinned is untouchable, and nothing here asks a model anything.
     * A skill that was never part of a prompt in a month is one the agent wrote
     * for a job that did not come back.
     */
    suspend fun archiveStale(
        olderThanMs: Long = STALE_AFTER_MS,
        maxUses: Int = STALE_MAX_USES,
        limit: Int = ARCHIVE_PER_RUN,
    ): List<Skill> {
        val before = now() - olderThanMs
        val stale = _skills.value
            .filter {
                it.agentCreated && !it.pinned && !it.archived &&
                    it.usageCount <= maxUses && it.updatedAt in 1 until before
            }
            .sortedBy { it.updatedAt }
            .take(limit)
        if (stale.isEmpty()) return emptyList()

        val ids = stale.map { it.id }.toSet()
        write(_skills.value.map { if (it.id in ids) it.copy(archived = true) else it })
        return stale
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
    suspend fun promptSection(): String? {
        val active = _skills.value
            .filter { it.enabled && !it.archived && it.instructions.isNotBlank() }
        if (active.isEmpty()) return null

        val kept = mutableListOf<Skill>()
        var budget = MAX_CHARS
        for (skill in active) {
            val cost = skill.name.length + skill.instructions.length + 4
            if (cost > budget && kept.isNotEmpty()) break
            kept += skill
            budget -= cost
        }

        // Being part of a prompt is what "used" means for a procedure: unlike a
        // memory there is no separate lookup that could count instead, and a
        // skill nobody ever loads is exactly what the curator is looking for.
        val keptIds = kept.map { it.id }.toSet()
        write(_skills.value.map { if (it.id in keptIds) it.copy(usageCount = it.usageCount + 1) else it })

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

        /** A procedure unused for a month was written for a job that never came back. */
        const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000
        const val STALE_MAX_USES = 0
        const val ARCHIVE_PER_RUN = 5

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
