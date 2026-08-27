package io.reyaak.router.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Streaming deltas, exactly as the providers send them.
 *
 * The regression this guards: `role` appears only on the first delta of a
 * stream, so a non-nullable `role` made every subsequent chunk fail to decode.
 * The reply arrived, was dropped chunk by chunk, and the transcript showed an
 * empty bubble with a full provenance line under it.
 */
class OpenAiChunkTest {

    @Test
    fun `first delta carries the role`() {
        val chunk = decodeOaiChunk(
            """{"id":"x","object":"chat.completion.chunk","created":1,"model":"gpt-oss-120b",""" +
                """"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}"""
        )
        assertEquals("", chunk?.choices?.firstOrNull()?.delta?.content)
    }

    @Test
    fun `later deltas omit the role and still decode`() {
        val chunk = decodeOaiChunk(
            """{"id":"x","object":"chat.completion.chunk","created":1,"model":"gpt-oss-120b",""" +
                """"system_fingerprint":"fp_1","choices":[{"index":0,"delta":{"content":"Hello"},""" +
                """"logprobs":null,"finish_reason":null}]}"""
        )
        assertEquals("Hello", chunk?.choices?.firstOrNull()?.delta?.content)
    }

    @Test
    fun `usage-only chunk decodes`() {
        val chunk = decodeOaiChunk(
            """{"choices":[],"usage":{"prompt_tokens":116,"completion_tokens":42,"total_tokens":158}}"""
        )
        assertEquals(116, chunk?.usage?.promptTokens)
        assertEquals(42, chunk?.usage?.completionTokens)
    }

    @Test
    fun `tool-call fragments decode without a role`() {
        val chunk = decodeOaiChunk(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1",""" +
                """"type":"function","function":{"name":"search","arguments":"{\"q\""}}]}}]}"""
        )
        val call = chunk?.choices?.firstOrNull()?.delta?.toolCalls?.firstOrNull()
        assertEquals("search", call?.function?.name)
    }

    @Test
    fun `garbage is dropped rather than thrown`() {
        assertNull(decodeOaiChunk("not json"))
    }
}
