package io.reyaak.core.tools

import io.reyaak.router.config.ConfigPersistence
import io.reyaak.router.model.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry decides what the model is even told about, so the properties
 * here are the difference between an agent that can search and one that wastes
 * a round trip discovering it cannot.
 */
class ToolRegistryTest {

    private class FakeTool(
        override val name: String,
        private val needsKey: Boolean = false,
    ) : AgentTool {
        override fun ready(config: ToolConfig) = !needsKey || config.usesCrw
        var ran = false
        override val label = name
        override val description = "fake"
        override val parameters = """{"type":"object","properties":{}}"""
        override suspend fun run(argumentsJson: String, config: ToolConfig): String {
            ran = true
            return "ran $name"
        }
    }

    private fun registry(vararg tools: AgentTool, stored: String? = null) =
        ToolRegistry(tools.toList(), ConfigPersistence.InMemory(stored))

    @Test
    fun `nothing is offered until the user enables it`() = runTest {
        val registry = registry(FakeTool("free"))
        assertTrue(registry.definitions().isEmpty())

        registry.setEnabled("free", true)
        assertEquals(listOf("free"), registry.definitions().map { it.name })
    }

    @Test
    fun `an enabled tool that needs a credential is withheld until there is one`() = runTest {
        val registry = registry(FakeTool("web", needsKey = true))
        registry.setEnabled("web", true)
        assertTrue("no key yet", registry.definitions().isEmpty())

        registry.update { it.copy(crwApiKey = "sk_test") }
        assertEquals(listOf("web"), registry.definitions().map { it.name })
    }

    @Test
    fun `a self-hosted url counts as a credential, since it needs no key`() = runTest {
        val registry = registry(FakeTool("web", needsKey = true))
        registry.setEnabled("web", true)
        registry.update { it.copy(crwBaseUrl = "http://localhost:3000") }
        assertEquals(1, registry.definitions().size)
    }

    @Test
    fun `a call to a disabled or unknown tool answers in text, not an exception`() = runTest {
        val tool = FakeTool("free")
        val registry = registry(tool)

        val disabled = registry.run(ToolCall("1", "free", "{}"))
        assertTrue(disabled.contains("not enabled"))
        assertFalse(tool.ran)

        val unknown = registry.run(ToolCall("2", "nope", "{}"))
        assertTrue(unknown.contains("No tool named"))
    }

    @Test
    fun `a throwing tool becomes text the model can recover from`() = runTest {
        val exploding = object : AgentTool {
            override val name = "boom"
            override val label = "boom"
            override val description = "throws"
            override val parameters = "{}"
            override suspend fun run(argumentsJson: String, config: ToolConfig): String =
                throw IllegalStateException("network down")
        }
        val registry = registry(exploding)
        registry.setEnabled("boom", true)

        val result = registry.run(ToolCall("1", "boom", "{}"))
        assertTrue(result.contains("network down"))
    }

    @Test
    fun `config survives a reload`() = runTest {
        val persistence = ConfigPersistence.InMemory()
        ToolRegistry(listOf(FakeTool("free")), persistence).apply {
            setEnabled("free", true)
            update { it.copy(crwApiKey = "sk_test") }
        }

        val reloaded = ToolRegistry(listOf(FakeTool("free")), persistence).also { it.load() }
        assertTrue(reloaded.config.value.isEnabled("free"))
        assertEquals("sk_test", reloaded.config.value.crwApiKey)
    }
}
