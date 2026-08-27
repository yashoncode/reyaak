package io.reyaak.core

import io.reyaak.core.chat.ChatEngine
import androidx.room.RoomDatabase
import io.reyaak.core.agent.AgentStatus
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.reyaak.core.data.ReyaakDatabase
import io.reyaak.router.catalog.ModelSpec
import io.reyaak.core.llm.LLMClient
import io.reyaak.core.persona.PersonaStore
import io.reyaak.core.skills.SkillStore
import io.reyaak.core.tools.ToolRegistry
import io.reyaak.core.tools.WebReadTool
import io.reyaak.core.tools.WebSearchTool
import io.reyaak.router.Router
import io.reyaak.router.config.ConfigPersistence
import io.reyaak.router.config.RouterConfigStore
import io.reyaak.router.health.HealthStore

/**
 * The agent, assembled.
 *
 * A hand-wired container rather than a DI framework: there are five objects and
 * one construction order, so a framework would add a compile step and an
 * annotation vocabulary to solve a problem this app does not have.
 *
 * The dependency direction is the architectural boundary made real:
 * [ChatEngine] holds an [LLMClient], and only [LLMClient] holds the [Router].
 * Nothing in this module can reach a provider.
 */
class ReyaakCore private constructor(
    val configStore: RouterConfigStore,
    val router: Router,
    val llm: LLMClient,
    val chat: ChatEngine,
    val health: HealthStore,
    val agent: AgentStatus,
    val persona: PersonaStore,
    val tools: ToolRegistry,
    val skills: SkillStore,
) {

    /** Read persisted config. Returns any warnings, for the UI to surface. */
    suspend fun start(): List<String> {
        health.warmUp()
        persona.load()
        tools.load()
        skills.load()
        return configStore.load()
    }

    /**
     * Reconcile the stored model catalog with what each provider actually serves.
     *
     * Model ids rot: a provider renames or retires one and the stored config
     * keeps offering it, so the first turn after a few idle days spends its
     * attempts on 404s. This asks `/v1/models` and, for every platform that
     * answers, disables the enabled ids it no longer lists and adds the new ones
     * (disabled, since a listed id may be an embedding or audio endpoint).
     *
     * Stale entries are disabled rather than deleted: their ranks and limits may
     * have been tuned, and a renamed id often comes back.
     *
     * An empty or failed listing means "unknown", never "serves nothing" — both
     * adapters return an empty list on a body they cannot parse, and acting on
     * that would disable a working provider outright.
     *
     * @param platform one platform, or null for every platform with a usable key.
     */
    suspend fun syncModels(platform: String? = null): ModelSync {
        val targets = platform?.let { listOf(it) }
            ?: configStore.settings.value.configuredPlatforms().toList()
        var added = 0
        var retired = 0
        val failures = mutableListOf<String>()

        for (id in targets) {
            val served = try {
                llm.listModels(id)
            } catch (e: Exception) {
                failures += "$id: ${e.message?.take(120) ?: "listing failed"}"
                continue
            }
            if (served.isEmpty()) {
                failures += "$id: no models listed"
                continue
            }
            val servedIds = served.toSet()
            configStore.update { settings ->
                val known = settings.models.filter { it.platform == id }.map { it.modelId }.toSet()
                val fresh = served.filterNot { it in known }.map {
                    ModelSpec(
                        platform = id,
                        modelId = it,
                        displayName = it.substringAfterLast('/'),
                        sizeLabel = "Medium",
                        enabled = false,
                    )
                }
                added += fresh.size
                settings.copy(
                    models = settings.models.map { spec ->
                        if (spec.platform == id && spec.enabled && spec.modelId !in servedIds) {
                            retired++
                            spec.copy(enabled = false)
                        } else {
                            spec
                        }
                    } + fresh
                )
            }
        }
        return ModelSync(added, retired, targets.size, failures)
    }

    /**
     * Reload config from disk, clear every bench, and resync the catalog.
     *
     * The same thing a process restart does, without one: after a long idle the
     * stored ids may be stale and a benched target may be benched for a reason
     * that no longer holds.
     */
    suspend fun restart(): ModelSync {
        configStore.load()
        health.clearAllCooldowns()
        return syncModels()
    }

    companion object {
        private var instance: ReyaakCore? = null

        /**
         * @param databaseBuilder supplied by the host, because locating a
         *   database file is the one thing only the OS knows how to do.
         * @param persistence where router config (including API keys) is
         *   stored. Also the host's job, so this module stays free of Keystore.
         *
         * Not synchronized: both hosts call this from their single-threaded
         * application entry point before any UI exists.
         */
        fun get(
            databaseBuilder: RoomDatabase.Builder<ReyaakDatabase>,
            persistence: ConfigPersistence,
            personaPersistence: ConfigPersistence,
            toolPersistence: ConfigPersistence,
            skillPersistence: ConfigPersistence,
        ): ReyaakCore = instance ?: build(
            databaseBuilder,
            persistence,
            personaPersistence,
            toolPersistence,
            skillPersistence,
        ).also { instance = it }

        private fun build(
            databaseBuilder: RoomDatabase.Builder<ReyaakDatabase>,
            persistence: ConfigPersistence,
            personaPersistence: ConfigPersistence,
            toolPersistence: ConfigPersistence,
            skillPersistence: ConfigPersistence,
        ): ReyaakCore {
            val configStore = RouterConfigStore(persistence)
            val health = HealthStore()
            val router = Router(
                settingsProvider = { configStore.settings.value },
                health = health,
            )
            val llm = LLMClient(router)
            val agent = AgentStatus()
            val persona = PersonaStore(personaPersistence)
            // The tool list is fixed at build time: a tool is code, so there is
            // nothing to discover at runtime. What the user controls is which of
            // them the agent may use, which lives in the registry config.
            val skills = SkillStore(skillPersistence)
            val tools = ToolRegistry(
                tools = listOf(WebSearchTool(), WebReadTool()),
                persistence = toolPersistence,
            )
            // The bundled driver is set here rather than by each host, so
            // Android and iOS provably run the same SQLite build.
            val db = databaseBuilder.setDriver(BundledSQLiteDriver()).build()
            return ReyaakCore(
                configStore = configStore,
                router = router,
                llm = llm,
                chat = ChatEngine(
                    dao = db.chatDao(),
                    llm = llm,
                    agent = agent,
                    persona = { persona.persona.value },
                    tools = tools,
                    skills = skills,
                ),
                health = health,
                agent = agent,
                persona = persona,
                tools = tools,
                skills = skills,
            )
        }
    }
}

/** What one [ReyaakCore.syncModels] pass changed, for the Router screen to report. */
data class ModelSync(
    val added: Int = 0,
    val retired: Int = 0,
    val platforms: Int = 0,
    val failures: List<String> = emptyList(),
)
