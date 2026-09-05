package io.reyaak.core.skills

import io.reyaak.router.config.ConfigPersistence
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillStoreTest {

    private var clock = 1_000_000L

    private fun store(stored: String? = null) =
        SkillStore(ConfigPersistence.InMemory(stored)) { clock }

    @Test
    fun `every shipped skill starts off, so nothing changes behaviour silently`() = runTest {
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

    // ── Curation invariants, the same four that hold for memory ─────────────

    private suspend fun SkillStore.learn(id: String, text: String = "do the thing") =
        save(
            Skill(id = id, name = id, instructions = text, enabled = true, agentCreated = true),
            byUser = false,
        )

    @Test
    fun `curation never touches a skill the user wrote`() = runTest {
        val store = store()
        store.save(Skill(id = "mine", name = "Mine", instructions = "my way", enabled = true))
        clock += FORTY_DAYS

        store.archiveStale()

        assertFalse(store.skills.value.first { it.id == "mine" }.archived)
    }

    @Test
    fun `curation never touches a pinned skill`() = runTest {
        val store = store()
        store.learn("pinned-one")
        store.setPinned("pinned-one", true)
        clock += FORTY_DAYS

        store.archiveStale()

        assertFalse(store.skills.value.first { it.id == "pinned-one" }.archived)
    }

    @Test
    fun `curation archives rather than deletes`() = runTest {
        val store = store()
        store.learn("stale-one")
        val before = store.skills.value.size
        clock += FORTY_DAYS

        val archived = store.archiveStale()

        assertEquals(1, archived.size)
        assertEquals("nothing was removed", before, store.skills.value.size)
        assertTrue(store.skills.value.first { it.id == "stale-one" }.archived)
    }

    @Test
    fun `curation spares a skill that has been used`() = runTest {
        val store = store()
        store.learn("used-one")
        store.promptSection()
        clock += FORTY_DAYS

        store.archiveStale()

        val used = store.skills.value.first { it.id == "used-one" }
        assertEquals(1, used.usageCount)
        assertFalse(used.archived)
    }

    @Test
    fun `curation spares a recent skill`() = runTest {
        val store = store()
        store.learn("fresh-one")
        clock += 60_000
        assertTrue(store.archiveStale().isEmpty())
    }

    @Test
    fun `an archived skill does not reach the prompt`() = runTest {
        val store = store()
        store.learn("hidden-one", "never say this")
        store.setArchived("hidden-one", true)
        assertNull(store.promptSection())
    }

    @Test
    fun `archiving is reversible`() = runTest {
        val store = store()
        store.learn("back-again")
        store.setArchived("back-again", true)
        store.setArchived("back-again", false)
        assertFalse(store.skills.value.first { it.id == "back-again" }.archived)
    }

    @Test
    fun `rewriting a skill by hand takes it out of the curator's reach`() = runTest {
        val store = store()
        store.learn("learned-thing", "first attempt")
        val agents = store.skills.value.first { it.id == "learned-thing" }
        store.save(agents.copy(instructions = "how it actually works"))
        clock += FORTY_DAYS

        store.archiveStale()

        val corrected = store.skills.value.first { it.id == "learned-thing" }
        assertFalse(corrected.agentCreated)
        assertFalse(corrected.archived)
        assertEquals(2, corrected.version)
    }

    @Test
    fun `the agent revising its own skill keeps it and bumps the version`() = runTest {
        val store = store()
        store.learn("learned-thing", "first attempt")
        val agents = store.skills.value.first { it.id == "learned-thing" }
        store.save(agents.copy(instructions = "second attempt"), byUser = false)

        val revised = store.skills.value.first { it.id == "learned-thing" }
        assertTrue(revised.agentCreated)
        assertEquals(2, revised.version)
    }

    private companion object {
        const val FORTY_DAYS = 40L * 24 * 60 * 60 * 1000
    }
}
