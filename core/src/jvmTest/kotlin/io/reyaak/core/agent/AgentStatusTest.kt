package io.reyaak.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent gate, which chat now depends on. These are the properties the
 * ChatEngine guard and the two screens read, so a regression here is a chat
 * screen that either refuses every turn or accepts them with no agent.
 */
class AgentStatusTest {

    @Test
    fun `a fresh agent is stopped and cannot serve a turn`() {
        val agent = AgentStatus()
        assertFalse(agent.isRunning)
        assertEquals("Stopped", agent.state.value.activity)
    }

    @Test
    fun `starting opens the gate and zeroes both counters`() {
        val agent = AgentStatus()
        agent.turnDone("Answered")
        agent.started(1_000L)

        assertTrue(agent.isRunning)
        assertEquals(0, agent.state.value.turnsAnswered)
        assertEquals(0, agent.state.value.heartbeats)
        assertEquals(1_000L, agent.state.value.startedAtMs)
    }

    @Test
    fun `heartbeats and turns count separately`() {
        val agent = AgentStatus()
        agent.started(0L)
        agent.heartbeat("Idle for 1m")
        agent.heartbeat("Idle for 2m")
        agent.turnDone("Answered via groq")

        assertEquals(2, agent.state.value.heartbeats)
        assertEquals(1, agent.state.value.turnsAnswered)
        assertEquals("Answered via groq", agent.state.value.activity)
    }

    @Test
    fun `busy reports activity without advancing a counter`() {
        val agent = AgentStatus()
        agent.started(0L)
        agent.busy("Answering")

        assertEquals("Answering", agent.state.value.activity)
        assertEquals(0, agent.state.value.turnsAnswered)
        assertEquals(0, agent.state.value.heartbeats)
    }

    @Test
    fun `busy is ignored while stopped, so a stopped agent never looks alive`() {
        val agent = AgentStatus()
        agent.busy("Answering")

        assertFalse(agent.isRunning)
        assertEquals("Stopped", agent.state.value.activity)
    }

    @Test
    fun `stopping closes the gate but keeps what was done`() {
        val agent = AgentStatus()
        agent.started(0L)
        agent.heartbeat("Idle for 1m")
        agent.turnDone("Answered")
        agent.stopped()

        assertFalse(agent.isRunning)
        assertEquals(null, agent.state.value.startedAtMs)
        assertEquals(1, agent.state.value.turnsAnswered)
        assertEquals(1, agent.state.value.heartbeats)
    }
}
