package io.reyaak.core.tools

import io.reyaak.core.data.MemoryKind
import io.reyaak.core.memory.MemoryStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Write something down so it survives this conversation.
 *
 * The agent curates its own memory rather than a background job mining the
 * transcript. Two reasons: the model already knows which sentence in a
 * conversation was the durable one, and a mining pass would have to re-read
 * every transcript on a device where that is the expensive thing to do.
 *
 * Effectful, because it changes state the user can see and did not ask for
 * directly.
 */
class MemoryWriteTool(
    private val memory: MemoryStore,
    /** Which conversation is being served, so a memory can be traced back. */
    private val conversationId: () -> Long? = { null },
) : AgentTool {

    override val name = "memory_write"
    override val label = "Remember"
    override val effectful = true

    override val description =
        "Save a durable fact so it is available in later conversations. Use it for " +
            "things that stay true: a preference, a decision, how something is set up. " +
            "Do not use it for the answer to the current question, or for anything the " +
            "user has not implied is lasting. Say what is true in one sentence, without " +
            "referring to this conversation."

    override val parameters = """
        {
          "type": "object",
          "properties": {
            "content": {
              "type": "string",
              "description": "The fact, as one self-contained sentence."
            },
            "kind": {
              "type": "string",
              "enum": ["profile", "fact"],
              "description": "profile for something about the user, fact for anything else."
            }
          },
          "required": ["content"]
        }
    """.trimIndent()

    override suspend fun run(argumentsJson: String, config: ToolConfig): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            ?: return "could not read the arguments"
        val content = args["content"]?.jsonPrimitive?.contentOrNull().orEmpty()
        if (content.isBlank()) return "nothing to remember: content was empty"
        val kind = MemoryKind.fromWire(args["kind"]?.jsonPrimitive?.contentOrNull())

        val id = memory.remember(content, kind, conversationId())
            ?: return "nothing to remember: content was empty"
        return "remembered as ${kind.wireName} #$id"
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Look something up that was learned earlier.
 *
 * Separate from the profile section that goes into every prompt: that section is
 * the handful of things about the user that are always relevant, and this is
 * everything else, fetched only when the question touches it.
 */
class MemorySearchTool(private val memory: MemoryStore) : AgentTool {

    override val name = "memory_search"
    override val label = "Recall"
    override val effectful = false

    override val description =
        "Search what you have remembered from earlier conversations. Use it when the " +
            "question refers to something you were told before and do not have in front " +
            "of you. Search for a distinctive word rather than a whole sentence."

    override val parameters = """
        {
          "type": "object",
          "properties": {
            "term": {
              "type": "string",
              "description": "A distinctive word or short phrase to match."
            }
          },
          "required": ["term"]
        }
    """.trimIndent()

    override suspend fun run(argumentsJson: String, config: ToolConfig): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            ?: return "could not read the arguments"
        val term = args["term"]?.jsonPrimitive?.contentOrNull().orEmpty()
        if (term.isBlank()) return "no search term given"

        val hits = memory.recall(term)
        if (hits.isEmpty()) return "nothing remembered about \"$term\""
        return hits.joinToString("\n") { "- ${it.content}" }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** `jsonPrimitive.content` throws on JSON null; this is the total version. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    runCatching { content }.getOrNull()?.takeIf { it != "null" }
