package io.reyaak.core.tools

import io.reyaak.router.config.ConfigPersistence
import io.reyaak.router.model.ToolCall
import io.reyaak.router.model.ToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
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

    /**
     * True when this tool can actually run right now.
     *
     * Per tool rather than one registry-wide credential check: the web tools
     * need nothing, and Gmail needs a signed-in account that means nothing to
     * them.
     */
    fun ready(config: ToolConfig): Boolean = true

    /**
     * Whether running this can change anything outside the agent.
     *
     * True by default, and deliberately so: a tool added later, or supplied by a
     * host this module has never seen, is assumed to have effects until someone
     * says otherwise. Defaulting to false would make every new tool silently
     * eligible for unattended use, which is the wrong way round for a mistake to
     * happen. Reading a page or searching memory is safe to run unattended;
     * writing memory or touching a mailbox is not.
     */
    val effectful: Boolean get() = true

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
    /**
     * Tool names the user turned on.
     *
     * Off by default, because a tool costs a round trip. The exception is memory:
     * an agent whose memory is switched off does not learn anything, which is not
     * a default anyone would choose deliberately, so [DEFAULT_ENABLED] is unioned
     * in once per install by [ToolRegistry.load].
     */
    val enabled: Set<String> = DEFAULT_ENABLED,
    /**
     * Which defaults this record has already been given.
     *
     * Without it, a config written before memory existed would keep memory off
     * forever, and unioning the defaults on every load would make turning
     * memory off impossible.
     */
    @SerialName("config_version") val configVersion: Int = CONFIG_VERSION,
    /** fastCRW key. A credential, so this file is Keystore-encrypted like the router config. */
    val crwApiKey: String = "",
    /** Override for a self-hosted crw, which needs no key at all. */
    val crwBaseUrl: String = "",
    /**
     * The Gmail address the user connected, or blank.
     *
     * The address, not a token: Play Services hands out a fresh access token on
     * demand once the scope is granted, so storing one would be caching
     * something that expires in an hour. This is what "signed in" means here,
     * and what the card shows.
     */
    val gmailAccount: String = "",
    /** Mailbox address for the IMAP tool. Doubles as the username: they match everywhere. */
    val imapUser: String = "",
    /**
     * App password for [imapUser]. A credential, hence the encrypted file.
     *
     * An app password rather than the account password because every provider
     * that still allows IMAP at all issues one, and a revoked app password
     * costs the user nothing.
     */
    val imapPassword: String = "",
    /** Server override. Blank means it is derived from the address; see [imapServer]. */
    val imapHost: String = "",
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
        val decoded = runCatching { json.decodeFromString<ToolConfig>(stored) }
            .getOrDefault(ToolConfig())
        // A config written before a default existed is brought up to date once,
        // then left alone, so switching one of those tools back off sticks.
        _config.value = if (decoded.configVersion < CONFIG_VERSION) {
            val upgraded = decoded.copy(
                enabled = decoded.enabled + DEFAULT_ENABLED,
                configVersion = CONFIG_VERSION,
            )
            persistence.write(json.encodeToString(upgraded))
            upgraded
        } else {
            decoded
        }
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

    /**
     * The tools that are safe to offer when nobody is watching.
     *
     * Used by the maintenance pass, which runs on a timer rather than on a
     * request. An unattended turn that could send mail or rewrite memory is a
     * different risk from one the user is sitting in front of, so the timer only
     * ever sees the tools that cannot change anything.
     */
    fun readOnlyDefinitions(): List<ToolDefinition> {
        val cfg = _config.value
        return tools.filter { !it.effectful && usable(it, cfg) }.map { it.definition() }
    }

    fun usable(tool: AgentTool, cfg: ToolConfig = _config.value): Boolean =
        cfg.isEnabled(tool.name) && tool.ready(cfg)

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

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** Tools that start on. Memory only: everything else costs a round trip. */
val DEFAULT_ENABLED: Set<String> = setOf("memory_write", "memory_search")

/**
 * Bumped when [DEFAULT_ENABLED] gains a name that existing installs should get.
 *
 * Not a schema version: the record is additive and Room-free. It exists only to
 * make "apply the new defaults once" distinguishable from "apply them forever".
 */
const val CONFIG_VERSION = 1
