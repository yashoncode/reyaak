package io.reyaak.core.persona

import io.reyaak.router.config.ConfigPersistence
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {

    @Test
    fun `an empty persona adds no prompt section`() {
        assertNull(Persona().promptSection())
    }

    @Test
    fun `only the fields that were filled in are rendered`() {
        val section = Persona(userName = "Yash", traits = "dry").promptSection()!!
        assertTrue(section.contains("Call the user: Yash"))
        assertTrue(section.contains("Your character: dry"))
        assertTrue(!section.contains("About the user"))
    }

    @Test
    fun `a saved persona survives a reload`() = runTest {
        val persistence = ConfigPersistence.InMemory()
        val persona = Persona(agentName = "Ray", about = "Android dev")
        PersonaStore(persistence).save(persona)

        val reloaded = PersonaStore(persistence).also { it.load() }
        assertEquals(persona, reloaded.persona.value)
    }

    @Test
    fun `an unreadable file reads as not personalised`() = runTest {
        val store = PersonaStore(ConfigPersistence.InMemory("{ not json"))
        store.load()
        assertTrue(store.persona.value.isEmpty)
    }
}
