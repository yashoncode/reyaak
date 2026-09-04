package io.reyaak.core.tools

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Web tools, served by fastCRW.
 *
 * Two tools rather than five: search answers "what is out there" and scrape
 * answers "what does that page say", and between them they cover what a chat
 * turn can use. Crawl, map, and extract are batch operations whose results do
 * not fit in a phone-sized context, so they are not offered until something
 * asks for them.
 *
 * Responses are read through the generic JSON tree rather than typed models.
 * The shape is Firecrawl-compatible and may gain fields, and a strict
 * serializer would fail a turn over a field the agent never reads.
 */
internal object Crw {

    const val HOSTED = "https://api.fastcrw.com"

    /** Its own client: :router owns provider traffic, and this is not that. */
    val client: HttpClient = HttpClient {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            // A scrape renders a page, so it is slower than a chat round trip
            // but must still not outlive the turn that asked for it.
            requestTimeoutMillis = 45_000
        }
    }

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun baseUrl(config: ToolConfig): String =
        config.crwBaseUrl.trim().trimEnd('/').ifBlank { HOSTED }

    suspend fun call(path: String, body: String, config: ToolConfig): JsonObject {
        val response: HttpResponse = client.post("${baseUrl(config)}$path") {
            contentType(ContentType.Application.Json)
            // A self-hosted crw needs no auth, so the header is sent only when
            // there is a key to send.
            config.crwApiKey.trim().takeIf { it.isNotEmpty() }?.let {
                header("Authorization", "Bearer $it")
            }
            setBody(body)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("crw ${response.status.value}: ${text.take(200)}")
        }
        return json.parseToJsonElement(text).jsonObject
    }

    /** Read a string field, tolerating its absence. */
    fun JsonObject.str(name: String): String? =
        this[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != "null" }
}

/** What the model sends us for a search. */
private const val SEARCH_SCHEMA = """
{"type":"object","properties":{
"query":{"type":"string","description":"What to search the web for."},
"limit":{"type":"integer","description":"How many results, 1 to 10. Default 5."}},
"required":["query"]}
"""

private const val SCRAPE_SCHEMA = """
{"type":"object","properties":{
"url":{"type":"string","description":"The page to read, as a full http(s) URL."}},
"required":["url"]}
"""

class WebSearchTool : AgentTool {
    override val name = "web_search"
    override val label = "Web search"
    override val description =
        "Search the web and return titles, URLs and snippets. Use for anything " +
            "current, or any fact you are not certain of."
    override val parameters = SEARCH_SCHEMA.trim()
    // Works with no configuration at all: without a crw it falls back to the
    // built-in backend, so switching the tool on is enough: ready() stays true.

    override suspend fun run(argumentsJson: String, config: ToolConfig): String {
        val args = Crw.json.parseToJsonElement(argumentsJson).jsonObject
        val query = with(Crw) { args.str("query") }
            ?: return "No query was given, so there was nothing to search for."
        val limit = args["limit"]
            ?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }
            ?.coerceIn(1, 10)
            ?: 5

        if (!config.usesCrw) {
            val hits = BuiltinWeb.search(query, limit)
            if (hits.isEmpty()) return "No results for \"$query\"."
            return hits.mapIndexed { index, hit ->
                buildString {
                    append(index + 1).append(". ").append(hit.title)
                    append("\n   ").append(hit.url)
                    if (hit.snippet.isNotBlank()) append("\n   ").append(hit.snippet.take(400))
                }
            }.joinToString("\n")
        }

        val body = buildJsonObject {
            put("query", query)
            put("limit", limit)
        }.toString()
        val root = Crw.call("/v1/search", body, config)
        val results = root["data"]?.jsonArray ?: return "The search returned nothing."
        if (results.isEmpty()) return "No results for \"$query\"."

        return results.take(limit).mapIndexed { index, element ->
            val row = element.jsonObject
            with(Crw) {
                buildString {
                    append(index + 1).append(". ")
                    append(row.str("title") ?: "untitled")
                    row.str("url")?.let { append("\n   ").append(it) }
                    (row.str("snippet") ?: row.str("description"))?.let {
                        append("\n   ").append(it.take(400))
                    }
                }
            }
        }.joinToString("\n")
    }
}

class WebReadTool : AgentTool {
    override val name = "web_read"
    override val label = "Read a page"
    override val description =
        "Fetch one web page and return its readable content as markdown. Use " +
            "after a search, or when the user gives you a URL."
    override val parameters = SCRAPE_SCHEMA.trim()

    override suspend fun run(argumentsJson: String, config: ToolConfig): String {
        val args = Crw.json.parseToJsonElement(argumentsJson).jsonObject
        val url = with(Crw) { args.str("url") }
            ?: return "No url was given, so there was nothing to read."

        if (!config.usesCrw) {
            val text = BuiltinWeb.read(url)
            return if (text.isBlank()) "That page returned no readable text."
            else text.take(MAX_CHARS)
        }

        val body = buildJsonObject {
            put("url", url)
            putJsonArray("formats") { add("markdown") }
        }.toString()
        val root = Crw.call("/v1/scrape", body, config)
        // The payload has lived at both the top level and under `data` across
        // Firecrawl-compatible versions, so both are checked.
        val data = root["data"]?.jsonObject ?: root
        val markdown = with(Crw) { data.str("markdown") ?: data.str("content") }
            ?: return "That page returned no readable text."

        // Trimmed hard: this lands in a prompt whose budget must fit the
        // smallest model the router might pick.
        return if (markdown.length <= MAX_CHARS) markdown
        else markdown.take(MAX_CHARS) + "\n\n[truncated, page continues]"
    }

    private companion object {
        const val MAX_CHARS = 8_000
    }
}
