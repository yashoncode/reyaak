package io.reyaak.core.skills

import io.reyaak.router.config.ConfigPersistence
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillStoreTest {

    private fun store(stored: String? = null) = SkillStore(ConfigPersistence.InMemory(stored))

    @Test
    fun `every shipped skill starts off, so nothing changes behaviour silently`() {
        assertTrue(SkillStore.BUILTINS.none { it.enabled })
        assertNull(store().promptSection())
    }

    @Test
    fun `an enabled skill contributes its name and instructions`() = runTest {
        val store = store()
        store.setEnabled("coder", true)

        val section = store.promptSection()!!
        assertTrue(section.contains("## Coder"))
        assertTrue(section.contains("Lead with the code"))
        assertFalse("an off skill must not leak in", section.contains("## Teacher"))
    }

    @Test
    fun `the prompt section is capped, dropping later skills rather than truncating text`() = runTest {
        val store = store()
        val long = "x".repeat(SkillStore.MAX_CHARS)
        store.save(Skill(id = "a", name = "A", instructions = long, enabled = true))
        store.save(Skill(id = "b", name = "B", instructions = long, enabled = true))

        val section = store.promptSection()!!
        // Builtins come first in list order and are all off, so A is the first
        // active one and B is what the cap drops.
        assertTrue(section.contains("## A"))
        assertFalse(section.contains("## B"))
    }

    @Test
    fun `a blank skill is rejected`() = runTest {
        val store = store()
        assertFalse(store.save(Skill(id = "x", name = " ", instructions = "do things")))
        assertFalse(store.save(Skill(id = "x", name = "X", instructions = " ")))
        assertTrue(store.skills.value.none { it.id == "x" })
    }

    @Test
    fun `deleting a builtin resets its text but keeps it in the list`() = runTest {
        val store = store()
        val edited = SkillStore.BUILTINS.first { it.id == "brief" }
            .copy(instructions = "my own wording", enabled = true)
        store.save(edited)
        assertEquals("my own wording", store.skills.value.first { it.id == "brief" }.instructions)

        store.delete("brief")
        val reset = store.skills.value.first { it.id == "brief" }
        assertTrue(reset.instructions.contains("at most four sentences"))
        assertTrue("the switch state survives a reset", reset.enabled)
    }

    @Test
    fun `a custom skill is deletable`() = runTest {
        val store = store()
        store.save(Skill(id = "custom-1", name = "Mine", instructions = "be terse"))
        store.delete("custom-1")
        assertTrue(store.skills.value.none { it.id == "custom-1" })
    }

    @Test
    fun `edits survive a reload and new builtins are merged in`() = runTest {
        val persistence = ConfigPersistence.InMemory()
        SkillStore(persistence).apply {
            setEnabled("coder", true)
            save(Skill(id = "custom-1", name = "Mine", instructions = "be terse", enabled = true))
        }

        val reloaded = SkillStore(persistence).also { it.load() }
        assertTrue(reloaded.skills.value.first { it.id == "coder" }.enabled)
        assertTrue(reloaded.skills.value.any { it.id == "custom-1" })
        // All four shipped skills are still present exactly once.
        SkillStore.BUILTINS.forEach { builtin ->
            assertEquals(1, reloaded.skills.value.count { it.id == builtin.id })
        }
    }

    @Test
    fun `an unreadable file leaves the shipped set in place`() = runTest {
        val store = store("{ not json")
        store.load()
        assertEquals(SkillStore.BUILTINS.size, store.skills.value.size)
    }
}
