package io.reyaak.core.tools

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * Search and page reading with no service and no key.
 *
 * This exists because the alternative was a tool that is switched on and still
 * cannot run. fastCRW itself cannot be bundled: it is a Rust binary published
 * only for desktop Linux, macOS and Windows, so putting it on a phone would
 * mean cross-compiling it for `aarch64-linux-android` and shipping a native
 * executable, which is a build pipeline rather than a feature. What IS portable
 * is the job it does for one page, so that part is here in Kotlin.
 *
 * It is deliberately the weaker option: no JS rendering, no anti-bot handling,
 * HTML stripped by pattern rather than parsed. Point the tools at a crw and
 * every one of those gets better. The point of this backend is that the agent
 * can search on a fresh install.
 *
 * Public for one member only: [htmlToText] is also what turns an HTML-only
 * email into something a model can read, and two strippers would drift.
 */
object BuiltinWeb {

    /**
     * DuckDuckGo's no-JS endpoint. Chosen because it needs no key and no
     * account; it is also the first thing to break if they change their markup,
     * which is why a failure here reads as "search is unavailable" rather than
     * taking the turn down.
     */
    private const val SEARCH = "https://html.duckduckgo.com/html/"

    /**
     * A real browser UA. Not evasion: both endpoints serve a different, often
     * empty, document to an unrecognised client, and an empty page is not a
     * useful answer.
     */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    data class Hit(val title: String, val url: String, val snippet: String)

    suspend fun search(query: String, limit: Int): List<Hit> {
        val response = Crw.client.get(SEARCH) {
            parameter("q", query)
            header("User-Agent", UA)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("search returned ${response.status.value}")
        }
        return parseResults(response.bodyAsText(), limit)
    }

    suspend fun read(url: String): String {
        val response = Crw.client.get(url) { header("User-Agent", UA) }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("that page returned ${response.status.value}")
        }
        return htmlToText(response.bodyAsText())
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    private val RESULT_LINK = Regex(
        """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val SNIPPET = Regex(
        """class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    internal fun parseResults(html: String, limit: Int): List<Hit> {
        val snippets = SNIPPET.findAll(html).map { strip(it.groupValues[1]) }.toList()
        return RESULT_LINK.findAll(html)
            .mapIndexed { index, match ->
                Hit(
                    title = strip(match.groupValues[2]),
                    url = resolveRedirect(match.groupValues[1]),
                    snippet = snippets.getOrElse(index) { "" },
                )
            }
            .filter { it.url.startsWith("http") && it.title.isNotBlank() }
            .take(limit)
            .toList()
    }

    /**
     * Results are wrapped in a redirect that carries the real URL in `uddg`.
     * Unwrapped here, because handing the model a tracking redirect means the
     * page-reading tool fetches the redirector instead of the page.
     */
    internal fun resolveRedirect(href: String): String {
        val raw = if (href.startsWith("//")) "https:$href" else href
        val target = Regex("""[?&]uddg=([^&]+)""").find(raw)?.groupValues?.get(1)
            ?: return raw
        return percentDecode(target)
    }

    internal fun percentDecode(value: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' && i + 2 < value.length -> {
                    val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex == null) {
                        bytes += c.code.toByte()
                        i++
                    } else {
                        bytes += hex.toByte()
                        i += 3
                    }
                }
                c == '+' -> {
                    bytes += ' '.code.toByte()
                    i++
                }
                else -> {
                    // Non-ASCII is already UTF-8 in the source string.
                    c.toString().encodeToByteArray().forEach { bytes += it }
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private val DROP_BLOCKS = Regex(
        """<(script|style|noscript|svg|head|nav|footer|form)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val BLOCK_END = Regex(
        """</(p|div|section|article|h[1-6]|li|tr|blockquote|pre)>|<br\s*/?>""",
        RegexOption.IGNORE_CASE,
    )
    private val TAG = Regex("""<[^>]+>""")
    private val BLANK_RUN = Regex("""\n{3,}""")
    private val SPACE_RUN = Regex("""[ \t]{2,}""")

    /**
     * HTML to readable text.
     *
     * Pattern-based on purpose: an HTML parser is a dependency and a
     * multiplatform problem, and the consumer here is a language model reading
     * prose, which does not need a DOM. Block ends become newlines so
     * paragraphs survive; everything else is dropped.
     */
    fun htmlToText(html: String): String {
        val title = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1)?.let { strip(it) }

        val body = html
            .replace(DROP_BLOCKS, " ")
            .replace(BLOCK_END, "\n")
            .replace(TAG, "")
            .let { unescape(it) }
            .lineSequence()
            .map { it.trim() }
            .joinToString("\n")
            .replace(SPACE_RUN, " ")
            .replace(BLANK_RUN, "\n\n")
            .trim()

        return if (title.isNullOrBlank()) body else "# $title\n\n$body"
    }

    private fun strip(fragment: String) = unescape(fragment.replace(TAG, "")).trim()

    private fun unescape(text: String) = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
}
