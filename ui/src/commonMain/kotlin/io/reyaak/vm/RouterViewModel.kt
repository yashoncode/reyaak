package io.reyaak.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.reyaak.core.ModelSync
import io.reyaak.core.ReyaakCore
import io.reyaak.core.data.UsageRow
import io.reyaak.router.catalog.ModelSpec
import io.reyaak.router.catalog.Provider
import io.reyaak.router.config.KeyRecord
import io.reyaak.router.config.RouterSettings
import io.reyaak.router.health.HealthSnapshot
import io.reyaak.router.score.RoutingStrategy
import io.reyaak.router.time.epochMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One provider as the Router screen shows it. */
data class ProviderRow(
    val provider: Provider,
    val keys: List<KeyRecord>,
    val models: List<ModelRow>,
    val configured: Boolean,
)

data class ModelRow(
    val spec: ModelSpec,
    val health: HealthSnapshot?,
    val cooldownRemainingMs: Long,
    val utilization: Double?,
    /** The router's own score for this model, null when it is not a candidate. */
    val score: Double? = null,
)

/** One row of the candidate chain, in the order the router would try them. */
data class ChainRow(
    val modelKey: String,
    val platform: String,
    val displayName: String,
    val score: Double,
)

/** A transient result banner: import outcome, export path, refresh result. */
data class RouterNotice(
    val text: String,
    val warnings: List<String> = emptyList(),
    val isError: Boolean = false,
)

data class RouterUiState(
    val providers: List<ProviderRow> = emptyList(),
    val strategy: RoutingStrategy = RoutingStrategy.BALANCED,
    val chain: List<ChainRow> = emptyList(),
    val chainExclusions: List<String> = emptyList(),
    /** Enabled, keyed models in the order the manual strategy would use them. */
    val manualOrder: List<ChainRow> = emptyList(),
    val busy: Boolean = false,
)

class RouterViewModel(private val core: ReyaakCore) : ViewModel() {

    private val _notice = MutableStateFlow<RouterNotice?>(null)
    val notice: StateFlow<RouterNotice?> = _notice.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val usage: StateFlow<List<UsageRow>> = core.chat.observeUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(RouterUiState())
    val state: StateFlow<RouterUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            core.configStore.settings.collect { rebuild(it) }
        }
    }

    /**
     * Recompute the view from settings plus live health.
     *
     * The candidate chain is computed inside its own runCatching: it walks every
     * model and touches the health store and the rate limiter, and if it threw,
     * the exception would kill the settings collector and the screen would stop
     * responding to every toggle from then on. The rows matter more than the
     * preview, so a failed preview costs the chain, not the screen.
     */
    private suspend fun rebuild(settings: RouterSettings) {
        val now = epochMillis()
        val health = core.health.all()
        val selection = runCatching { core.llm.preview() }.getOrNull()

        // Best score per model, since a model reached with two keys is two
        // candidates but one row.
        val scores: Map<String, Double> = selection?.candidates
            ?.groupBy { it.model.key }
            ?.mapValues { (_, group) -> group.maxOf { it.score } }
            ?: emptyMap()

        val rows = settings.providers
            .sortedWith(
                compareByDescending<Provider> { settings.keysFor(it.id).isNotEmpty() }
                    .thenBy { it.label }
            )
            .map { provider ->
                val keys = settings.keys.filter { it.platform == provider.id }
                val label = keys.firstOrNull()?.label ?: "k1"
                ProviderRow(
                    provider = provider,
                    keys = keys,
                    configured = keys.any { it.enabled && it.hasSecret },
                    models = settings.models
                        .filter { it.platform == provider.id }
                        .map { spec ->
                            val key = io.reyaak.router.health.HealthStore
                                .keyOf(spec.platform, spec.modelId, label)
                            val snapshot = health[key]
                            ModelRow(
                                spec = spec,
                                health = snapshot,
                                cooldownRemainingMs = snapshot?.cooldownRemainingMs(now) ?: 0L,
                                utilization = runCatching {
                                    core.router.utilizationOf(spec, label)
                                }.getOrNull(),
                                score = scores[spec.key],
                            )
                        }
                        // Enabled first, then by what the router thinks of them,
                        // then alphabetically: the same order the chain uses, so
                        // the list and the chain cannot disagree.
                        .sortedWith(
                            compareByDescending<ModelRow> { it.spec.enabled }
                                .thenByDescending { it.score ?: -1.0 }
                                .thenBy { it.spec.displayName.lowercase() }
                        ),
                )
            }

        _state.value = RouterUiState(
            providers = rows,
            strategy = settings.strategy,
            chain = selection?.candidates?.take(8)?.map {
                ChainRow(it.model.key, it.model.platform, it.model.displayName, it.score)
            } ?: emptyList(),
            chainExclusions = selection?.exclusions?.take(6)
                ?.map { "${it.modelKey}: ${it.reason}" } ?: emptyList(),
            manualOrder = manualOrder(settings, scores),
        )
    }

    /**
     * The manual chain as the user would edit it.
     *
     * Declared models first in their declared order, then any routable model not
     * yet in the list, best-scoring first. The editor therefore opens showing the
     * order the router is actually using rather than an empty list.
     */
    private fun manualOrder(
        settings: RouterSettings,
        scores: Map<String, Double>,
    ): List<ChainRow> {
        val routable = settings.routableModels().associateBy { it.key }
        val declared = settings.fallbackOrder.filter { it in routable }
        val rest = routable.keys
            .filterNot { it in declared }
            .sortedWith(compareByDescending<String> { scores[it] ?: -1.0 }.thenBy { it })
        return (declared + rest).mapNotNull { key ->
            routable[key]?.let {
                ChainRow(it.key, it.platform, it.displayName, scores[it.key] ?: 0.0)
            }
        }
    }

    fun refresh() = viewModelScope.launch { rebuild(core.configStore.settings.value) }

    fun dismissNotice() { _notice.value = null }

    // ── Keys ────────────────────────────────────────────────────────────────

    fun addKey(platform: String, label: String, secret: String) {
        val cleanLabel = label.trim().ifBlank { "default" }
        val cleanSecret = secret.trim()
        if (cleanSecret.isEmpty()) {
            _notice.value = RouterNotice("The key was empty, so nothing was saved.", isError = true)
            return
        }
        viewModelScope.launch {
            core.configStore.update {
                it.withKey(KeyRecord(platform = platform, label = cleanLabel, secret = cleanSecret))
            }
            _notice.value = RouterNotice("Key saved for $platform.")
        }
    }

    fun removeKey(platform: String, label: String) = viewModelScope.launch {
        core.configStore.update { it.withoutKey(platform, label) }
        _notice.value = RouterNotice("Key removed.")
    }

    fun toggleKey(platform: String, label: String, enabled: Boolean) = viewModelScope.launch {
        core.configStore.update { settings ->
            val existing = settings.keys.firstOrNull {
                it.platform == platform && it.label == label
            } ?: return@update settings
            settings.withKey(existing.copy(enabled = enabled))
        }
    }

    fun setStrategy(strategy: RoutingStrategy) = viewModelScope.launch {
        core.configStore.update { it.copy(strategy = strategy) }
    }

    fun toggleModel(modelKey: String, enabled: Boolean) = viewModelScope.launch {
        core.configStore.update { settings ->
            settings.copy(
                models = settings.models.map {
                    if (it.key == modelKey) it.copy(enabled = enabled) else it
                }
            )
        }
    }

    /** Enable or disable every model of one provider in one write. */
    fun setAllModels(platform: String, enabled: Boolean) = viewModelScope.launch {
        core.configStore.update { settings ->
            settings.copy(
                models = settings.models.map {
                    if (it.platform == platform) it.copy(enabled = enabled) else it
                }
            )
        }
        _notice.value = RouterNotice(
            if (enabled) "$platform: all models enabled." else "$platform: all models disabled."
        )
    }

    /**
     * Move one model in the manual chain.
     *
     * The whole visible order is written back, not just the moved pair: the order
     * may have been implicit until now (scored, with nothing declared), and
     * persisting only the pair would leave the rest to be re-derived from scores
     * that drift on their own.
     */
    fun moveInOrder(modelKey: String, delta: Int) = viewModelScope.launch {
        val current = _state.value.manualOrder.map { it.modelKey }
        val from = current.indexOf(modelKey)
        val to = from + delta
        if (from < 0 || to < 0 || to > current.lastIndex) return@launch
        val next = current.toMutableList()
        next.add(to, next.removeAt(from))
        core.configStore.update { it.copy(fallbackOrder = next) }
    }

    /** Drop the manual chain, so scoring decides the order again. */
    fun clearOrder() = viewModelScope.launch {
        core.configStore.update { it.copy(fallbackOrder = emptyList()) }
        _notice.value = RouterNotice("Manual order cleared.")
    }

    fun clearCooldown(platform: String, modelId: String, keyLabel: String) = viewModelScope.launch {
        core.router.clearCooldown(
            io.reyaak.router.health.HealthStore.keyOf(platform, modelId, keyLabel)
        )
        rebuild(core.configStore.settings.value)
    }

    /**
     * Ask each provider what it actually serves and reconcile the catalog.
     *
     * Adds what is new (disabled, since a listed id may be an embedding or audio
     * endpoint) and disables what the provider no longer lists, which is the
     * thing that otherwise makes the first turn after an idle week fail on a
     * model id that was retired upstream.
     */
    fun refreshModels(platform: String? = null) = viewModelScope.launch {
        _busy.value = true
        try {
            _notice.value = core.syncModels(platform).notice(platform)
        } catch (e: Exception) {
            _notice.value = RouterNotice(
                "Could not sync models: ${e.message?.take(160)}",
                isError = true,
            )
        } finally {
            _busy.value = false
            rebuild(core.configStore.settings.value)
        }
    }

    /** Reload config, clear every bench, resync the catalog. */
    fun restartRouter() = viewModelScope.launch {
        _busy.value = true
        try {
            val sync = core.restart()
            _notice.value = sync.notice(null).let {
                it.copy(text = "Router restarted. " + it.text)
            }
        } catch (e: Exception) {
            _notice.value = RouterNotice(
                "Router restart failed: ${e.message?.take(160)}",
                isError = true,
            )
        } finally {
            _busy.value = false
            rebuild(core.configStore.settings.value)
        }
    }

    private fun ModelSync.notice(platform: String?): RouterNotice {
        val scope = platform ?: "$platforms provider" + if (platforms == 1) "" else "s"
        return RouterNotice(
            text = when {
                platforms == 0 -> "No provider has a usable key yet, so there was nothing to sync."
                added == 0 && retired == 0 -> "$scope: catalog already current."
                else -> "$scope: added $added, disabled $retired retired."
            },
            warnings = failures,
            isError = platforms > 0 && failures.size == platforms,
        )
    }

    // ── Import / export ─────────────────────────────────────────────────────

    /** @param merge true layers the file over current config; false replaces it. */
    fun import(json: String, merge: Boolean) = viewModelScope.launch {
        _busy.value = true
        try {
            val result = core.configStore.import(json, merge)
            _notice.value = RouterNotice(
                text = buildString {
                    append(if (merge) "Merged config: " else "Replaced config: ")
                    append("${result.keysImported} keys")
                    if (result.customProviders > 0) append(", ${result.customProviders} custom providers")
                    if (result.modelsImported > 0) append(", ${result.modelsImported} models")
                    if (result.fallbackEntries > 0) append(", ${result.fallbackEntries} fallback entries")
                    if (result.routingApplied) append(", routing")
                    append('.')
                },
                warnings = result.warnings,
            )
        } catch (e: Exception) {
            _notice.value = RouterNotice(
                "That file could not be read as router config: ${e.message?.take(200)}",
                isError = true,
            )
        } finally {
            _busy.value = false
        }
    }

    fun exportJson(includeSecrets: Boolean): String = core.configStore.export(includeSecrets)

    fun onExported(includeSecrets: Boolean) {
        _notice.value = RouterNotice(
            if (includeSecrets) {
                "Exported WITH API keys. Treat that file as a credential."
            } else {
                "Exported without API keys."
            }
        )
    }
}
