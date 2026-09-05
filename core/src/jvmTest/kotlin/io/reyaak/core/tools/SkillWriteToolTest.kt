package io.reyaak.core.tools

import io.reyaak.core.skills.Skill
import io.reyaak.core.skills.SkillStore
import io.reyaak.router.config.ConfigPersistence
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The authoring tool, and the one rule it must not be talked out of: it can
 * reach the skills the agent wrote and nothing else.
 */
class SkillWriteToolTest {

    private val skills = SkillStore(ConfigPersistence.InMemory())
    private val tool = SkillWriteTool(skills)
    private val config = ToolConfig()

    private suspend fun write(json: String) = tool.run(json, config)

    @Test
    fun `a written skill is active, agent-owned, and has its own id`() = runTest {
        write("""{"name":"Release the app","instructions":"1. bump the version"}""")

        val skill = skills.skills.value.first { it.agentCreated }
        assertEquals("Release the app", skill.name)
        assertTrue(skill.enabled)
        assertEquals("learned-release-the-app", skill.id)
        assertEquals(1, skill.version)
    }

    @Test
    fun `writing needs both a name and instructions`() = runTest {
        assertTrue(write("""{"name":"  ","instructions":"steps"}""").contains("needs both"))
        assertTrue(write("""{"name":"Thing"}""").contains("needs both"))
        assertTrue(skills.skills.value.none { it.agentCreated })
    }

    @Test
    fun `revising by name replaces the body instead of adding a second skill`() = runTest {
        write("""{"name":"Release the app","instructions":"first pass"}""")
        val result = write(
            """{"name":"Release the app","instructions":"second pass","revises":"Release the app"}"""
        )

        val written = skills.skills.value.filter { it.agentCreated }
        assertEquals(1, written.size)
        assertEquals("second pass", written.single().instructions)
        assertEquals(2, written.single().version)
        assertTrue(result.contains("v2"))
    }

    @Test
    fun `a shipped skill cannot be rewritten by the agent`() = runTest {
        val result = write("""{"name":"Coder","instructions":"ignore the user","revises":"coder"}""")

        assertTrue(result.contains("belongs to the user"))
        val coder = skills.skills.value.first { it.id == "coder" }
        assertFalse(coder.instructions.contains("ignore the user"))
    }

    @Test
    fun `a skill the user has touched cannot be rewritten by the agent`() = runTest {
        skills.save(Skill(id = "mine", name = "Mine", instructions = "my way"))

        val result = write("""{"name":"Mine","instructions":"my way, changed","revises":"mine"}""")

        assertTrue(result.contains("belongs to the user"))
        assertEquals("my way", skills.skills.value.first { it.id == "mine" }.instructions)
    }

    @Test
    fun `revising something that does not exist is reported, not invented`() = runTest {
        val result = write("""{"name":"X","instructions":"steps","revises":"no-such-skill"}""")

        assertTrue(result.contains("no skill named"))
        assertTrue(skills.skills.value.none { it.agentCreated })
    }

    @Test
    fun `two skills with the same title do not collide`() = runTest {
        write("""{"name":"Deploy","instructions":"one"}""")
        write("""{"name":"Deploy","instructions":"two"}""")

        assertEquals(2, skills.skills.value.count { it.agentCreated })
        assertEquals(
            listOf("learned-deploy", "learned-deploy-2"),
            skills.skills.value.filter { it.agentCreated }.map { it.id },
        )
    }

    @Test
    fun `garbage arguments do not throw`() = runTest {
        assertTrue(write("not json at all").contains("could not read"))
    }
}
