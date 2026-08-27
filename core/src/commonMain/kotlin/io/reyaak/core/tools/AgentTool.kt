package io.reyaak.core.tools

import io.reyaak.router.config.ConfigPersistence
import io.reyaak.router.model.ToolCall
import io.reyaak.router.model.ToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One thing the agent can do that is not talking.
 *
 * The contract is deliberately string-in, string-out: arguments arrive as the
 * raw JSON the model produced, and the result goes back as text the model reads.
 * Anything richer would be a schema the model cannot see and the provider would
 * flatten to text anyway.
 */
interface AgentTool {
    /** Wire name the model calls. Snake case, because that is what models emit. */
    val name: String

    /** Human label for the Agent screen. */
    val label: String

    val description: String

    /** JSON Schema for the arguments object. */
    val parameters: String

    /** True when this tool cannot run until the user supplies a credential. */
    val needsKey: Boolean get() = false

    /** @return text for the model. Throwing is fine: the caller reports it. */
    suspend fun run(argumentsJson: String, config: ToolConfig): String

    fun definition() = ToolDefinition(name, description, parameters)
}

/**
 * What the user configured for tools.
 *
 * One flat record rather than a per-tool object: there are two settings, and a
 * map of settings objects would be a schema to maintain for no gain.
 */
@Serializable
data class ToolConfig(
    /** Tool names the user turned on. Off by default: a tool costs a round trip. */
    val enabled: Set<String> = emptySet(),
    /** fastCRW key. A credential, so this file is Keystore-encrypted like the router config. */
    val crwApiKey: String = "",
    /** Override for a self-hosted crw, which needs no key at all. */
    val crwBaseUrl: String = "",
) {
    fun isEnabled(name: String) = name in enabled

    /**
     * True when the web tools should go through crw rather than the built-in
     * backend. Derived rather than a stored mode: a key or a URL IS the choice,
     * and a separate switch could disagree with what is configured.
     */
    val usesCrw: Boolean get() = crwApiKey.isNotBlank() || crwBaseUrl.isNotBlank()
}

/**
 * The tools the agent may use, and whether the user wants them.
 *
 * A tool the user has not enabled is not merely hidden: it never reaches the
 * model, because a declared tool the agent cannot actually run is worse than no
 * tool at all. It would spend a turn calling something that returns an error.
 */
class ToolRegistry(
    private val tools: List<AgentTool>,
    private val persistence: ConfigPersistence,
) {
    private val _config = MutableStateFlow(ToolConfig())
    val config: StateFlow<ToolConfig> = _config.asStateFlow()

    /** Every tool, for the Agent screen, regardless of state. */
    val all: List<AgentTool> get() = tools

    suspend fun load() {
        val stored = persistence.read()?.takeIf { it.isNotBlank() } ?: return
        _config.value = runCatching { json.decodeFromString<ToolConfig>(stored) }
            .getOrDefault(ToolConfig())
    }

    suspend fun update(transform: (ToolConfig) -> ToolConfig) {
        val next = transform(_config.value)
        _config.value = next
        persistence.write(json.encodeToString(next))
    }

    suspend fun setEnabled(name: String, enabled: Boolean) = update { cfg ->
        cfg.copy(enabled = if (enabled) cfg.enabled + name else cfg.enabled - name)
    }

    /** What the model is told it can call: enabled tools that can actually run. */
    fun definitions(): List<ToolDefinition> {
        val cfg = _config.value
        return tools.filter { usable(it, cfg) }.map { it.definition() }
    }

    fun usable(tool: AgentTool, cfg: ToolConfig = _config.value): Boolean =
        cfg.isEnabled(tool.name) && (!tool.needsKey || hasCredential(cfg))

    /**
     * Run one call from the model.
     *
     * Failures come back as text rather than exceptions on purpose: the model
     * can recover from "that page 404ed" by trying another source, whereas a
     * thrown error would end the whole turn.
     */
    suspend fun run(call: ToolCall): String {
        val tool = tools.firstOrNull { it.name == call.name }
            ?: return "No tool named ${call.name} is available."
        if (!usable(tool)) return "The tool ${call.name} is not enabled."
        return try {
            tool.run(call.arguments, _config.value)
        } catch (e: Exception) {
            "The tool ${call.name} failed: ${e.message?.take(300) ?: "unknown error"}"
        }
    }

    private fun hasCredential(cfg: ToolConfig) = cfg.usesCrw

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
