package io.reyaak.data

import io.reyaak.core.tools.AgentTool
import io.reyaak.core.tools.BuiltinWeb
import io.reyaak.core.tools.ToolConfig
import io.reyaak.core.tools.imapNeedsBridge
import io.reyaak.core.tools.imapServer
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.MimeUtility
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Read any mailbox that speaks IMAP.
 *
 * The provider-independent half of reading mail, and the reason it exists next
 * to the Gmail tool rather than instead of it: an address and an app password
 * reach Outlook, Yahoo, Zoho, Fastmail, a company server and Gmail itself, with
 * no OAuth client to register and no scope review to pass.
 *
 * It lives in the host module, not :core, because IMAP here is JavaMail, which
 * is a JVM library. That is the same boundary the Keystore sits on, so nothing
 * new is being bent.
 *
 * IMAPS on 993 only. There is no STARTTLS fallback on purpose: the fallback is
 * a plaintext connection that a hostile network can hold open by simply
 * dropping the upgrade, and every host worth connecting to has served 993 for
 * twenty years.
 */
class ImapReadTool : AgentTool {

    override val name = "mail_read"
    override val label = "Read mail (IMAP)"
    override val description =
        "Read the user's mailbox over IMAP: search the inbox and return the " +
            "matching messages with sender, date, subject and body. Read-only."
    override val parameters = SCHEMA.trim()

    /** A server and a password, or there is nothing to connect to. */
    override fun ready(config: ToolConfig) =
        config.imapUser.isNotBlank() &&
            config.imapPassword.isNotBlank() &&
            config.imapServer.isNotBlank() &&
            !config.imapNeedsBridge

    /**
     * JavaMail is blocking from connect to close, so the whole session runs on
     * IO. Every handle is closed in reverse order even on failure: an IMAP
     * server holds a connection slot open for a client that just walks away,
     * and the free tiers count them.
     */
    override suspend fun run(argumentsJson: String, config: ToolConfig): String =
        withContext(Dispatchers.IO) {
            val args = Args.parse(argumentsJson)
            val session = Session.getInstance(properties(config.imapServer))
            val store = session.getStore("imaps")
            try {
                store.connect(config.imapServer, config.imapUser, config.imapPassword)
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_ONLY)
                try {
                    render(pick(inbox, args), args.limit)
                } finally {
                    inbox.close(false)
                }
            } finally {
                runCatching { store.close() }
            }
        }

    /**
     * The newest [Args.limit] messages matching the request.
     *
     * Filtering is pushed to the server as a SEARCH rather than pulled down and
     * sifted here: a mailbox has tens of thousands of messages and a phone
     * should never see the ones it is not going to read. Newest-first comes
     * from walking the result backwards, since IMAP hands back ascending
     * sequence numbers and sorting is an optional extension not every server has.
     */
    private fun pick(inbox: Folder, args: Args): List<Message> {
        val term = args.searchTerm()
        val found = if (term == null) {
            val count = inbox.messageCount
            if (count == 0) return emptyList()
            inbox.getMessages(maxOf(1, count - args.limit + 1), count)
        } else {
            inbox.search(term)
        }
        return found.takeLast(args.limit).reversed()
    }

    private fun render(messages: List<Message>, limit: Int): String {
        if (messages.isEmpty()) return "No mail matched."
        val rendered = messages.take(limit).joinToString("\n\n———\n\n") { message ->
            buildString {
                append("From: ").append(addresses(message))
                append("\nDate: ").append(message.sentDate?.toString() ?: "unknown")
                append("\nSubject: ").append(decode(message.subject).ifBlank { "(none)" })
                append("\n\n").append(bodyOf(message).trim().take(PER_MESSAGE))
            }
        }
        return if (rendered.length <= MAX_CHARS) rendered
        else rendered.take(MAX_CHARS) + "\n\n[truncated]"
    }

    private fun addresses(message: Message): String =
        runCatching { message.from?.joinToString(", ") { decode(it.toString()) } }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"

    /**
     * Headers arrive RFC 2047 encoded when they are not plain ASCII, which is
     * most non-English mail. Undecoded they read as `=?UTF-8?B?...?=`, which
     * the model will cheerfully quote back at the user.
     */
    private fun decode(raw: String?): String =
        raw?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }.orEmpty()

    /**
     * Readable text out of a MIME part, plain half first.
     *
     * The same preference the Gmail tool makes, for the same reason: a
     * multipart/alternative mail carries the words twice and the plain copy is
     * already what a model wants. HTML-only mail goes through the page reader's
     * stripper rather than a second one written here.
     */
    private fun bodyOf(part: Part): String {
        find(part, "text/plain")?.let { return it }
        find(part, "text/html")?.let { return BuiltinWeb.htmlToText(it) }
        return ""
    }

    private fun find(part: Part, mimeType: String): String? {
        runCatching {
            if (part.isMimeType(mimeType)) {
                // Attachments are skipped: a text/plain attachment is a file
                // the user sent, not the message they wrote.
                if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return null
                return (part.content as? String)?.takeIf { it.isNotBlank() }
            }
            val multipart = part.content as? Multipart ?: return null
            for (i in 0 until multipart.count) {
                find(multipart.getBodyPart(i), mimeType)?.let { return it }
            }
        }
        return null
    }

    /** One request from the model, already clamped. */
    private data class Args(
        val query: String,
        val unreadOnly: Boolean,
        val days: Int,
        val limit: Int,
    ) {
        /** Null means "no filter at all", which is the cheap newest-N path. */
        fun searchTerm(): SearchTerm? {
            val terms = buildList {
                if (query.isNotBlank()) {
                    // Subject or sender, because that is what a person means by
                    // "the mail from the bank". Full-text IMAP SEARCH BODY is
                    // unindexed on most servers and can take tens of seconds.
                    add(OrTerm(SubjectTerm(query), FromStringTerm(query)))
                }
                if (unreadOnly) add(FlagTerm(Flags(Flags.Flag.SEEN), false))
                if (days > 0) {
                    val since = java.util.Date(System.currentTimeMillis() - days * DAY_MILLIS)
                    add(ReceivedDateTerm(ComparisonTerm.GE, since))
                }
            }
            return when (terms.size) {
                0 -> null
                1 -> terms.first()
                else -> AndTerm(terms.toTypedArray())
            }
        }

        companion object {
            private const val DAY_MILLIS = 24L * 60 * 60 * 1000

            private val json = Json { isLenient = true; ignoreUnknownKeys = true }

            fun parse(argumentsJson: String): Args {
                val args = runCatching {
                    json.parseToJsonElement(argumentsJson) as? JsonObject
                }.getOrNull()

                fun field(name: String): String? = args?.get(name)
                    ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?.takeIf { it.isNotBlank() && it != "null" }

                return Args(
                    query = field("query").orEmpty(),
                    unreadOnly = field("unread_only")?.toBooleanStrictOrNull() ?: false,
                    days = field("days")?.toIntOrNull()?.coerceIn(0, 365) ?: 0,
                    limit = field("limit")?.toIntOrNull()?.coerceIn(1, 10) ?: 5,
                )
            }
        }
    }

    private fun properties(host: String) = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", host)
        put("mail.imaps.port", "993")
        put("mail.imaps.ssl.enable", "true")
        // A tool call sits inside a chat turn, so a server that is not
        // answering has to fail while the user is still looking at the screen.
        put("mail.imaps.connectiontimeout", "15000")
        put("mail.imaps.timeout", "30000")
        put("mail.imaps.writetimeout", "15000")
    }

    private companion object {
        const val PER_MESSAGE = 2_000
        const val MAX_CHARS = 8_000

        const val SCHEMA = """
{"type":"object","properties":{
"query":{"type":"string","description":"Words to match in the subject or the sender. Leave out for the most recent mail."},
"unread_only":{"type":"boolean","description":"Only unread messages. Default false."},
"days":{"type":"integer","description":"Only mail received in the last N days. Leave out for no limit."},
"limit":{"type":"integer","description":"How many messages, 1 to 10. Default 5."}}}
"""
    }
}
