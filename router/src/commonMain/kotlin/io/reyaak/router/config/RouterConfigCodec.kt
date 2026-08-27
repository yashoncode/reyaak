package io.reyaak.router.config

import io.reyaak.router.catalog.BuiltinCatalog
import io.reyaak.router.catalog.ModelSpec
import io.reyaak.router.catalog.Provider
import io.reyaak.router.score.KeySelectionStrategy
import io.reyaak.router.score.RoutingStrategy
import io.reyaak.router.score.RoutingWeights
import kotlinx.serialization.json.Json

/** Outcome of an import. Never throws for a bad entry: it reports one. */
data class ImportResult(
    val settings: RouterSettings,
    val warnings: List<String> = emptyList(),
    val keysImported: Int = 0,
    val modelsImported: Int = 0,
    val customProviders: Int = 0,
    val fallbackEntries: Int = 0,
    val routingApplied: Boolean = false,
)

/** A file that could not be parsed at all, as opposed to one with bad entries. */
class ConfigParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads and writes the router interchange format.
 *
 * Import mirrors freellmapi's per-entry degradation: a single malformed entry is
 * skipped with a warning and the rest of the file still applies. Failing the
 * whole import on one bad rank would make a hand-edited file miserable to fix,
 * and the warnings surface in the UI so nothing is silently dropped.
 */
object RouterConfigCodec {

    private val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val strict = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
        explicitNulls = false
    }

    // ── Import ──────────────────────────────────────────────────────────────

    fun decode(json: String, base: RouterSettings = RouterSettings()): ImportResult {
        val file = try {
            lenient.decodeFromString(RouterConfigFile.serializer(), json)
        } catch (e: Exception) {
            throw ConfigParseException(
                e.message?.take(300) ?: "the file is not valid router config JSON",
                e,
            )
        }
        return apply(file, base)
    }

    fun apply(file: RouterConfigFile, base: RouterSettings = RouterSettings()): ImportResult {
        val warnings = mutableListOf<String>()
        var settings = base

        // 1. Custom providers first: later sections may reference them.
        val customProviders = mutableListOf<Provider>()
        val customModels = mutableListOf<ModelSpec>()
        file.customProviders?.forEachIndexed { index, entry ->
            if (!Bounds.urlOk(entry.baseUrl)) {
                warnings += "customProviders[$index]: baseUrl must be an http(s) URL; entry skipped"
                return@forEachIndexed
            }
            val provider = RouterSettings.customProvider(entry.baseUrl, entry.label)
            customProviders += provider
            entry.models.forEach { m ->
                if (!Bounds.rankOk(m.intelligenceRank) || !Bounds.rankOk(m.speedRank)) {
                    warnings += "customProviders[$index] model ${m.model}: rank must be 1..1000; ranks ignored"
                }
                customModels += ModelSpec(
                    platform = provider.id,
                    modelId = m.model,
                    displayName = m.displayName?.takeIf { it.isNotBlank() } ?: m.model,
                    sizeLabel = m.sizeLabel?.take(Bounds.SIZE_LABEL_MAX) ?: "Medium",
                    intelligenceRank = m.intelligenceRank?.takeIf { Bounds.rankOk(it) } ?: 500,
                    speedRank = m.speedRank?.takeIf { Bounds.rankOk(it) } ?: 500,
                    contextWindow = m.contextWindow,
                    monthlyTokenBudget = m.monthlyTokenBudget?.take(Bounds.BUDGET_MAX),
                    supportsTools = m.supportsTools ?: true,
                    supportsVision = m.supportsVision ?: false,
                    fallbackEnabled = m.fallbackEnabled ?: true,
                )
            }
            if (!entry.apiKey.isNullOrBlank()) {
                settings = settings.withKey(
                    KeyRecord(
                        platform = provider.id,
                        label = entry.label?.takeIf { it.isNotBlank() } ?: "Custom",
                        secret = entry.apiKey,
                        baseUrl = provider.baseUrl,
                    )
                )
            }
        }

        settings = settings.copy(
            providers = mergeProviders(settings.providers, customProviders),
            models = settings.models + customModels,
        )

        // 2. Keys.
        var keysImported = 0
        file.keys?.forEachIndexed { index, entry ->
            val platformRaw = entry.platform.trim()
            if (platformRaw.isEmpty()) {
                warnings += "keys[$index]: platform is required; entry skipped"
                return@forEachIndexed
            }
            val isCustom = platformRaw == "custom"
            if (isCustom && entry.baseUrl.isNullOrBlank()) {
                warnings += "keys[$index]: baseUrl is required for a custom key; entry skipped"
                return@forEachIndexed
            }
            if (!Bounds.urlOk(entry.baseUrl)) {
                warnings += "keys[$index]: baseUrl must be an http(s) URL; entry skipped"
                return@forEachIndexed
            }
            val platform = if (isCustom) {
                RouterSettings.customPlatformId(entry.baseUrl!!)
            } else {
                PlatformAlias.toLocal(platformRaw)
            }
            if (!isCustom && settings.provider(platform) == null) {
                warnings += "keys[$index]: unknown provider \"$platformRaw\"; entry skipped"
                return@forEachIndexed
            }
            if (entry.key.isNullOrBlank()) {
                // Mirrors upstream: a keyless entry degrades to a skip rather than
                // failing the import, because a config can outlive a provider's
                // keyless tier.
                warnings += "keys[$index]: $platformRaw requires an API key; entry skipped"
                return@forEachIndexed
            }
            if (isCustom && settings.provider(platform) == null) {
                settings = settings.copy(
                    providers = settings.providers +
                        RouterSettings.customProvider(entry.baseUrl!!, entry.label)
                )
            }
            settings = settings.withKey(
                KeyRecord(
                    platform = platform,
                    label = entry.label?.takeIf { it.isNotBlank() } ?: "imported",
                    secret = entry.key,
                    baseUrl = entry.baseUrl?.trimEnd('/'),
                    enabled = entry.enabled ?: true,
                )
            )
            keysImported++
        }

        // 3. Model overrides. An unknown (platform, modelId) is ADDED rather than
        //    rejected: a config may name a model newer than this catalog.
        var modelsImported = 0
        file.models?.forEachIndexed { index, entry ->
            val platform = PlatformAlias.toLocal(entry.platform)
            if (platform.isEmpty() || entry.modelId.isBlank()) {
                warnings += "models[$index]: platform and modelId are required; entry skipped"
                return@forEachIndexed
            }
            if (!Bounds.rankOk(entry.intelligenceRank) || !Bounds.rankOk(entry.speedRank)) {
                warnings += "models[$index] ${entry.modelId}: rank must be 1..1000; ranks ignored"
            }
            listOf(
                "rpmLimit" to entry.rpmLimit,
                "rpdLimit" to entry.rpdLimit,
                "tpmLimit" to entry.tpmLimit,
                "tpdLimit" to entry.tpdLimit,
                "contextWindow" to entry.contextWindow,
            ).forEach { (name, value) ->
                if (!Bounds.positiveOrNull(value)) {
                    warnings += "models[$index] ${entry.modelId}: $name must be positive; ignored"
                }
            }

            val key = "$platform/${entry.modelId}"
            val existing = settings.models.firstOrNull { it.key == key }
                ?: BuiltinCatalog.model(key)
            val merged = mergeModel(existing, platform, entry)
            settings = settings.copy(
                models = settings.models.filterNot { it.key == key } + merged
            )
            modelsImported++
        }

        // 4. Explicit fallback order.
        var fallbackCount = 0
        val ordered = file.fallback
            ?.filter { it.enabled != false && it.platform.isNotBlank() && it.modelId.isNotBlank() }
            ?.sortedBy { it.priority ?: Int.MAX_VALUE }
            ?.map { "${PlatformAlias.toLocal(it.platform)}/${it.modelId}" }
            ?: emptyList()
        if (ordered.isNotEmpty()) {
            settings = settings.copy(fallbackOrder = ordered)
            fallbackCount = ordered.size
        }

        // 5. Routing.
        var routingApplied = false
        file.routing?.let { routing ->
            val wire = routing.strategy.trim().lowercase()
            val parsed = RoutingStrategy.fromWire(wire)
            if (parsed == null || wire !in Bounds.STRATEGIES) {
                warnings += "routing: unknown strategy \"${routing.strategy}\"; routing left unchanged"
            } else {
                val weights = routing.weights?.let {
                    if (it.reliability < 0 || it.speed < 0 || it.intelligence < 0) {
                        warnings += "routing.weights: weights must be non-negative; weights ignored"
                        null
                    } else if (it.reliability + it.speed + it.intelligence <= 0.0) {
                        warnings += "routing.weights: weights sum to zero; weights ignored"
                        null
                    } else {
                        RoutingWeights(it.reliability, it.speed, it.intelligence)
                    }
                }
                if (parsed == RoutingStrategy.CUSTOM && weights == null) {
                    warnings += "routing: strategy \"custom\" needs weights; using balanced weights"
                }
                val keySel = routing.keySelectionStrategy?.let { raw ->
                    KeySelectionStrategy.fromWire(raw).also {
                        if (it == null) {
                            warnings += "routing: unknown keySelectionStrategy \"$raw\"; left unchanged"
                        }
                    }
                }
                settings = settings.copy(
                    strategy = parsed,
                    customWeights = weights ?: settings.customWeights,
                    keySelection = keySel ?: settings.keySelection,
                )
                routingApplied = true
            }
        }

        return ImportResult(
            settings = settings,
            warnings = warnings,
            keysImported = keysImported,
            modelsImported = modelsImported,
            customProviders = customProviders.size,
            fallbackEntries = fallbackCount,
            routingApplied = routingApplied,
        )
    }

    private fun mergeProviders(current: List<Provider>, added: List<Provider>): List<Provider> {
        val byId = current.associateBy { it.id }.toMutableMap()
        added.forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    private fun mergeModel(existing: ModelSpec?, platform: String, e: ModelEntry): ModelSpec {
        val base = existing ?: ModelSpec(
            platform = platform,
            modelId = e.modelId,
            displayName = e.modelId,
            sizeLabel = "Medium",
        )
        return base.copy(
            platform = platform,
            modelId = e.modelId,
            displayName = e.displayName?.takeIf { it.isNotBlank() } ?: base.displayName,
            sizeLabel = e.sizeLabel?.take(Bounds.SIZE_LABEL_MAX) ?: base.sizeLabel,
            intelligenceRank = e.intelligenceRank?.takeIf { Bounds.rankOk(it) } ?: base.intelligenceRank,
            speedRank = e.speedRank?.takeIf { Bounds.rankOk(it) } ?: base.speedRank,
            contextWindow = e.contextWindow?.takeIf { it > 0 } ?: base.contextWindow,
            rpmLimit = e.rpmLimit?.takeIf { it > 0 } ?: base.rpmLimit,
            rpdLimit = e.rpdLimit?.takeIf { it > 0 } ?: base.rpdLimit,
            tpmLimit = e.tpmLimit?.takeIf { it > 0 } ?: base.tpmLimit,
            tpdLimit = e.tpdLimit?.takeIf { it > 0 } ?: base.tpdLimit,
            monthlyTokenBudget = e.monthlyTokenBudget?.take(Bounds.BUDGET_MAX) ?: base.monthlyTokenBudget,
            enabled = e.enabled ?: base.enabled,
            supportsTools = e.supportsTools ?: base.supportsTools,
            supportsVision = e.supportsVision ?: base.supportsVision,
            fallbackEnabled = e.fallbackEnabled ?: base.fallbackEnabled,
        )
    }

    // ── Export ──────────────────────────────────────────────────────────────

    /**
     * Serialize settings back to the interchange format.
     *
     * [includeSecrets] defaults to false. An export is a file that gets emailed,
     * synced, and pasted into issue trackers, so the safe default is a config
     * that describes the setup without carrying the credentials. With it off,
     * each key still appears, so the shape and labels survive a round trip,
     * but with no `key` field, which is exactly the entry upstream skips with a
     * warning rather than silently mis-importing.
     */
    fun encode(settings: RouterSettings, includeSecrets: Boolean = false): String =
        strict.encodeToString(RouterConfigFile.serializer(), toFile(settings, includeSecrets))

    fun toFile(settings: RouterSettings, includeSecrets: Boolean = false): RouterConfigFile {
        val customIds = settings.providers.filter { it.custom }.map { it.id }.toSet()

        val keys = settings.keys.map { record ->
            val isCustom = record.platform in customIds
            KeyEntry(
                platform = if (isCustom) "custom" else PlatformAlias.toWire(record.platform),
                key = record.secret?.takeIf { includeSecrets && it.isNotBlank() },
                label = record.label,
                baseUrl = record.baseUrl,
                enabled = if (record.enabled) null else false,
            )
        }

        val customProviders = settings.providers.filter { it.custom }.map { provider ->
            CustomProviderEntry(
                baseUrl = provider.baseUrl,
                apiKey = settings.keys
                    .firstOrNull { it.platform == provider.id }
                    ?.secret
                    ?.takeIf { includeSecrets && it.isNotBlank() },
                label = provider.label,
                models = settings.models
                    .filter { it.platform == provider.id }
                    .map { it.toCustomEntry() },
            )
        }

        // Only models that actually differ from the builtin catalog are emitted.
        // A full dump would bury the handful of deliberate overrides in noise,
        // and would pin this catalog's ranks into a file meant to outlive it.
        val models = settings.models
            .filter { it.platform !in customIds }
            .filter { it != BuiltinCatalog.model(it.key) }
            .map { it.toModelEntry() }

        val fallback = settings.fallbackOrder.mapIndexedNotNull { index, key ->
            val platform = key.substringBefore('/')
            val modelId = key.substringAfter('/')
            if (platform.isBlank() || modelId.isBlank()) null
            else FallbackEntry(
                platform = if (platform in customIds) "custom" else PlatformAlias.toWire(platform),
                modelId = modelId,
                priority = index + 1,
            )
        }

        val routing = RoutingEntry(
            strategy = settings.strategy.wireName,
            weights = settings.customWeights?.let {
                WeightsEntry(it.reliability, it.speed, it.intelligence)
            },
            keySelectionStrategy = settings.keySelection.wireName,
        )

        return RouterConfigFile(
            keys = keys.ifEmpty { null },
            customProviders = customProviders.ifEmpty { null },
            models = models.ifEmpty { null },
            fallback = fallback.ifEmpty { null },
            routing = routing,
        )
    }

    private fun ModelSpec.toModelEntry() = ModelEntry(
        platform = PlatformAlias.toWire(platform),
        modelId = modelId,
        displayName = displayName,
        intelligenceRank = intelligenceRank,
        speedRank = speedRank,
        sizeLabel = sizeLabel,
        rpmLimit = rpmLimit,
        rpdLimit = rpdLimit,
        tpmLimit = tpmLimit,
        tpdLimit = tpdLimit,
        monthlyTokenBudget = monthlyTokenBudget,
        contextWindow = contextWindow,
        enabled = if (enabled) null else false,
        supportsVision = supportsVision,
        supportsTools = supportsTools,
        fallbackEnabled = if (fallbackEnabled) null else false,
    )

    private fun ModelSpec.toCustomEntry() = CustomModelEntry(
        model = modelId,
        displayName = displayName.takeIf { it != modelId },
        intelligenceRank = intelligenceRank.takeIf { it != 500 },
        speedRank = speedRank.takeIf { it != 500 },
        sizeLabel = sizeLabel.takeIf { it != "Medium" },
        monthlyTokenBudget = monthlyTokenBudget,
        contextWindow = contextWindow,
        supportsVision = supportsVision.takeIf { it },
        supportsTools = supportsTools.takeIf { !it },
        fallbackEnabled = fallbackEnabled.takeIf { !it },
    )
}
