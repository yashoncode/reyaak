package io.reyaak.core.tools

import io.reyaak.core.skills.Skill
import io.reyaak.core.skills.SkillStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Write down how to do something, so the next attempt starts where this one
 * ended.
 *
 * Memory is facts and this is procedures: the thing worth keeping after a hard
 * task is usually not what was true but what worked. Authoring happens by
 * telling the live agent the standards and letting it write, rather than by a
 * separate distillation pass over transcripts, because the model that just did
 * the work is the one that knows which step was the awkward one.
 *
 * The safety rule is in [run] rather than in the prose: this tool can only ever
 * create or revise a skill the agent itself wrote. A shipped skill or one the
 * user has touched is out of reach, so a bad revision can cost the agent its own
 * notes and nothing else.
 */
class SkillWriteTool(private val skills: SkillStore) : AgentTool {

    override val name = "skill_write"
    override val label = "Write a skill"
    override val effectful = true

    override val description =
        "Record a reusable procedure after finishing something involved, or revise one " +
            "you already wrote when it turned out to be wrong or incomplete. Write it for " +
            "a competent stranger: what the task is, the steps in order, and what to watch " +
            "out for. Keep it under 200 words, imperative, and specific to how THIS user's " +
            "setup actually works, not general advice a model already knows. Do not record " +
            "one-off answers, facts (use memory_write), or anything you only did once. To " +
            "revise, pass the existing skill's name in \"revises\"."

    override val parameters = """
        {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "Short title for the procedure, e.g. \"Release the Android app\"."
            },
            "summary": {
              "type": "string",
              "description": "One line describing when this applies."
            },
            "instructions": {
              "type": "string",
              "description": "The procedure itself: ordered steps, imperative, under 200 words."
            },
            "revises": {
              "type": "string",
              "description": "Name or id of a skill you wrote earlier that this replaces."
            }
          },
          "required": ["name", "instructions"]
        }
    """.trimIndent()

    override suspend fun run(argumentsJson: String, config: ToolConfig): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            ?: return "could not read the arguments"
        val title = args.string("name").orEmpty().trim()
        val instructions = args.string("instructions").orEmpty().trim()
        if (title.isBlank() || instructions.isBlank()) {
            return "a skill needs both a name and instructions"
        }
        val summary = args.string("summary").orEmpty().trim()
        val revises = args.string("revises")?.trim()?.takeIf { it.isNotBlank() }

        val existing = revises?.let { key ->
            skills.skills.value.firstOrNull { it.id.equals(key, true) || it.name.equals(key, true) }
                ?: return "no skill named \"$key\""
        }
        // Invariant 1, enforced here rather than trusted to the prompt.
        if (existing != null && !existing.agentCreated) {
            return "\"${existing.name}\" belongs to the user, so it cannot be rewritten here"
        }

        val skill = existing?.copy(
            name = title,
            summary = summary.ifBlank { existing.summary },
            instructions = instructions,
            archived = false,
        ) ?: Skill(
            id = newId(title),
            name = title,
            summary = summary,
            instructions = instructions,
            // On, or the loop never closes: a procedure that has to be switched
            // on by hand is one the agent will never get to use.
            enabled = true,
            agentCreated = true,
        )

        if (!skills.save(skill, byUser = false)) return "that skill was rejected as empty"
        val saved = skills.skills.value.first { it.id == skill.id }
        return if (existing == null) {
            "wrote the skill \"${saved.name}\", now active"
        } else {
            "revised \"${saved.name}\" to v${saved.version}"
        }
    }

    /** Slug, kept clear of every id already in use so a builtin cannot be hit. */
    private fun newId(title: String): String {
        val base = "learned-" + title.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(DASHES, "-")
            .take(40)
            .ifBlank { "skill" }
        val taken = skills.skills.value.map { it.id }.toSet()
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.first { "$base-$it" !in taken }.let { "$base-$it" }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val DASHES = Regex("-+")
    }
}

private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }
