package io.reyaak.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyless backend parses HTML by pattern, which is the part most likely to
 * rot. These pin the two behaviours a wrong result would silently corrupt: the
 * real URL behind a redirect, and prose surviving tag stripping.
 */
class BuiltinWebTest {

    @Test
    fun `a result redirect is unwrapped to the real url`() {
        val href = "//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%20b&rut=xyz"
        assertEquals("https://example.com/a b", BuiltinWeb.resolveRedirect(href))
    }

    @Test
    fun `a plain href is left alone`() {
        assertEquals("https://example.com/x", BuiltinWeb.resolveRedirect("https://example.com/x"))
    }

    @Test
    fun `percent decoding handles utf8 and plus`() {
        assertEquals("café x", BuiltinWeb.percentDecode("caf%C3%A9+x"))
    }

    @Test
    fun `results are paired with their snippets and capped`() {
        val html = buildString {
            repeat(3) { i ->
                append("""<a class="result__a" href="https://e$i.com">Title $i</a>""")
                append("""<a class="result__snippet" href="#">Snippet $i</a>""")
            }
        }
        val hits = BuiltinWeb.parseResults(html, limit = 2)
        assertEquals(2, hits.size)
        assertEquals("Title 0", hits[0].title)
        assertEquals("https://e0.com", hits[0].url)
        assertEquals("Snippet 1", hits[1].snippet)
    }

    @Test
    fun `script and style content never reaches the model`() {
        val html = """
            <html><head><title>Page</title><style>.a{color:red}</style></head>
            <body><script>var secret = 1;</script>
            <p>First para.</p><p>Second &amp; last.</p></body></html>
        """.trimIndent()

        val text = BuiltinWeb.htmlToText(html)
        assertTrue(text.startsWith("# Page"))
        assertTrue(text.contains("First para."))
        assertTrue(text.contains("Second & last."))
        assertFalse(text.contains("var secret"))
        assertFalse(text.contains("color:red"))
    }

    @Test
    fun `paragraph breaks survive but blank runs collapse`() {
        val text = BuiltinWeb.htmlToText("<p>a</p><p></p><p></p><p>b</p>")
        assertEquals("a\n\nb", text)
    }
}
