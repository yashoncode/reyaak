package io.reyaak.core.tools

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where a Gmail access token comes from.
 *
 * A one-method interface the host implements, for the same reason the Keystore
 * is the host's job: OAuth on Android is Play Services, on iOS it is something
 * else, and neither belongs in shared code. The contract is deliberately
 * "give me a token now" rather than "sign in": once the scope is granted the
 * platform mints a fresh token silently, so there is no session to model.
 */
fun interface GmailAuth {
    /** A usable access token, or null when the user must grant consent again. */
    suspend fun accessToken(): String?
}

/**
 * The slice of the Gmail REST API this tool needs.
 *
 * Raw HTTP through the client the web tools already use, rather than the Google
 * API client library: that library is a JVM-only tree of transports and model
 * classes, and what is actually needed here is two GETs.
 */
object Gmail {

    /** Read-only. The agent is a reader; it has no business sending mail. */
    const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    internal suspend fun get(
        path: String,
        token: String,
        params: Map<String, String> = emptyMap(),
    ): JsonObject {
        val response = Crw.client.get("$BASE$path") {
            header("Authorization", "Bearer $token")
            params.forEach { (key, value) -> parameter(key, value) }
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("gmail ${response.status.value}: ${text.take(200)}")
        }
        return Crw.json.parseToJsonElement(text).jsonObject
    }

    /**
     * Which mailbox a token belongs to.
     *
     * Doubles as the sign-in confirmation: it proves the grant works and yields
     * the address to show, without asking for a profile scope on top of the
     * mail one.
     */
    suspend fun profileEmail(token: String): String =
        get("/profile", token).s("emailAddress").orEmpty()
}

/** Read a string field, tolerating its absence or a JSON null. */
internal fun JsonObject.s(name: String): String? =
    this[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        ?.takeIf { it.isNotBlank() && it != "null" }

/**
 * Gmail encodes part bodies as base64url, usually unpadded.
 *
 * Whitespace is filtered because some senders wrap the encoded body at 76
 * columns, and the decoder rejects anything outside the alphabet.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeBody(data: String): String = runCatching {
    Base64.UrlSafe
        .withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
        .decode(data.filterNot { it.isWhitespace() })
        .decodeToString()
}.getOrDefault("")

/** Find a header by name. Gmail's casing varies by sender, so the match does not. */
internal fun headerOf(payload: JsonObject, name: String): String =
    payload["headers"]?.jsonArray
        ?.firstOrNull { it.jsonObject.s("name").equals(name, ignoreCase = true) }
        ?.jsonObject?.s("value")
        .orEmpty()

/**
 * Readable text out of a MIME tree.
 *
 * text/plain first, because a multipart/alternative mail carries the same words
 * twice and the plain half is already what a model wants. Only if there is none
 * does the HTML half get stripped, by the same routine the page reader uses.
 */
internal fun extractText(payload: JsonObject): String {
    fun walk(node: JsonObject, wanted: String): String? {
        if (node.s("mimeType")?.startsWith(wanted) == true) {
            node["body"]?.jsonObject?.s("data")?.let { return decodeBody(it) }
        }
        node["parts"]?.jsonArray?.forEach { part ->
            walk(part.jsonObject, wanted)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
    walk(payload, "text/plain")?.takeIf { it.isNotBlank() }?.let { return it }
    walk(payload, "text/html")?.takeIf { it.isNotBlank() }?.let { return BuiltinWeb.htmlToText(it) }
    return ""
}

private const val GMAIL_SCHEMA = """
{"type":"object","properties":{
"query":{"type":"string","description":"Gmail search syntax, e.g. 'from:priya is:unread newer_than:7d' or 'subject:invoice'. Leave out for the most recent mail."},
"limit":{"type":"integer","description":"How many messages, 1 to 10. Default 5."}}}
"""

/**
 * Read the user's mail.
 *
 * One tool rather than a list/fetch pair: "what did my landlord say" is one
 * question, and making the model spend a turn on ids before it can spend
 * another on bodies buys nothing on a phone.
 */
class GmailReadTool(private val auth: GmailAuth) : AgentTool {
    override val name = "gmail_read"
    override val label = "Read Gmail"
    override val description =
        "Search the user's Gmail and return the matching messages with sender, " +
            "subject, date and body. Read-only, and only the user's own mailbox."
    override val parameters = GMAIL_SCHEMA.trim()

    /** The stored address is the grant: no address, no consent has been given. */
    override fun ready(config: ToolConfig) = config.gmailAccount.isNotBlank()

    override suspend fun run(argumentsJson: String, config: ToolConfig): String {
        val token = auth.accessToken()
            ?: return "Gmail is no longer connected. Ask the user to sign in again " +
                "under Agent, Tools."

        val args = runCatching { Crw.json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrNull() ?: JsonObject(emptyMap())
        val query = args.s("query").orEmpty()
        val limit = args["limit"]
            ?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }
            ?.coerceIn(1, 10)
            ?: 5

        val listing = Gmail.get(
            "/messages",
            token,
            buildMap {
                put("maxResults", limit.toString())
                if (query.isNotBlank()) put("q", query)
            },
        )
        val ids = listing["messages"]?.jsonArray?.mapNotNull { it.jsonObject.s("id") }.orEmpty()
        if (ids.isEmpty()) {
            return if (query.isBlank()) "That mailbox has no messages."
            else "No mail matches \"$query\"."
        }

        // ponytail: one round trip per message, sequentially. At a cap of ten
        // that is a second or two; parallelise only if the cap ever rises.
        val rendered = ids.map { id ->
            val message = Gmail.get("/messages/$id", token, mapOf("format" to "full"))
            val payload = message["payload"]?.jsonObject ?: JsonObject(emptyMap())
            val body = extractText(payload).ifBlank { message.s("snippet").orEmpty() }
            buildString {
                append("From: ").append(headerOf(payload, "From").ifBlank { "unknown" })
                append("\nDate: ").append(headerOf(payload, "Date"))
                append("\nSubject: ").append(headerOf(payload, "Subject").ifBlank { "(none)" })
                append("\n\n").append(body.trim().take(PER_MESSAGE))
            }
        }.joinToString("\n\n———\n\n")

        // Trimmed hard for the same reason a scraped page is: this lands in a
        // prompt that must fit the smallest model the router might pick.
        return if (rendered.length <= MAX_CHARS) rendered
        else rendered.take(MAX_CHARS) + "\n\n[truncated]"
    }

    private companion object {
        const val PER_MESSAGE = 2_000
        const val MAX_CHARS = 8_000
    }
}
