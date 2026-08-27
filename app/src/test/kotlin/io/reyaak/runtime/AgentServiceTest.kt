package io.reyaak.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The elapsed-time formatter is the only branching logic in the service that
 * runs without a device, and its boundaries are exactly where an off-by-one
 * would hide.
 */
class AgentServiceTest {

    private fun fmt(ms: Long) = AgentService.formatElapsed(ms)

    @Test
    fun `seconds below a minute`() {
        assertEquals("0s", fmt(0))
        assertEquals("1s", fmt(1_000))
        assertEquals("59s", fmt(59_999))
    }

    @Test
    fun `rolls over to minutes at exactly one minute`() {
        assertEquals("1m 00s", fmt(60_000))
        assertEquals("1m 01s", fmt(61_000))
        assertEquals("59m 59s", fmt(3_599_000))
    }

    @Test
    fun `rolls over to hours at exactly one hour`() {
        assertEquals("1h 00m", fmt(3_600_000))
        assertEquals("1h 01m", fmt(3_660_000))
        assertEquals("25h 00m", fmt(90_000_000))
    }

    @Test
    fun `truncates rather than rounding, so it never reports time not yet elapsed`() {
        assertEquals("0s", fmt(999))
        assertEquals("1m 00s", fmt(60_999))
    }
}
