package io.reyaak.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parsing half of the Gmail tool: everything between "Google answered" and
 * "the model reads it". The HTTP half needs a mailbox, but this is where a real
 * message actually goes wrong, because senders decide the MIME shape, not us.
 */
class GmailToolTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun payload(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    /** base64url of [text], unpadded, the way Gmail sends it. */
    private fun encode(text: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.toByteArray())

    @Test
    fun `a plain single-part message decodes`() {
        val body = extractText(
            payload(
                """{"mimeType":"text/plain","body":{"data":"${encode("the rent is due")}"}}"""
            )
        )
        assertEquals("the rent is due", body)
    }

    @Test
    fun `multipart prefers the plain half over the html one`() {
        val body = extractText(
            payload(
                """
                {"mimeType":"multipart/alternative","parts":[
                  {"mimeType":"text/html","body":{"data":"${encode("<p>marked up</p>")}"}},
                  {"mimeType":"text/plain","body":{"data":"${encode("plain words")}"}}
                ]}
                """
            )
        )
        assertEquals("plain words", body)
    }

    @Test
    fun `an html-only message is stripped rather than handed over as markup`() {
        val body = extractText(
            payload(
                """
                {"mimeType":"multipart/mixed","parts":[
                  {"mimeType":"text/html","body":{"data":"${encode("<p>hello <b>there</b></p>")}"}}
                ]}
                """
            )
        )
        assertEquals("hello there", body.trim())
    }

    @Test
    fun `a message with no readable part yields nothing rather than junk`() {
        val body = extractText(
            payload(
                """
                {"mimeType":"multipart/mixed","parts":[
                  {"mimeType":"application/pdf","body":{"attachmentId":"abc"}}
                ]}
                """
            )
        )
        assertTrue(body.isEmpty())
    }

    @Test
    fun `a body wrapped at 76 columns still decodes`() {
        val long = "x".repeat(200)
        val wrapped = encode(long).chunked(76).joinToString("\r\n")
        assertEquals(long, decodeBody(wrapped))
    }

    @Test
    fun `headers match whatever casing the sender used`() {
        val headers = payload(
            """{"headers":[{"name":"subject","value":"Invoice 42"},
                           {"name":"From","value":"a@b.com"}]}"""
        )
        assertEquals("Invoice 42", headerOf(headers, "Subject"))
        assertEquals("a@b.com", headerOf(headers, "from"))
        assertEquals("", headerOf(headers, "Cc"))
    }

    @Test
    fun `gmail stays off until an account is connected`() {
        val tool = GmailReadTool { "token" }
        assertTrue(!tool.ready(ToolConfig(enabled = setOf("gmail_read"))))
        assertTrue(tool.ready(ToolConfig(gmailAccount = "me@example.com")))
    }
}
