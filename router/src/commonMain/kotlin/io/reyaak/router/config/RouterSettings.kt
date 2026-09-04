package io.reyaak.router.config

import io.reyaak.router.catalog.Auth
import io.reyaak.router.catalog.BuiltinCatalog
import io.reyaak.router.catalog.ModelSpec
import io.reyaak.router.catalog.Provider
import io.reyaak.router.catalog.Wire
import io.reyaak.router.score.BANDIT_PRESETS
import io.reyaak.router.score.DEFAULT_STRATEGY
import io.reyaak.router.score.KeySelectionStrategy
import io.reyaak.router.score.RoutingStrategy
import io.reyaak.router.score.RoutingWeights

/**
 * One credential for one provider.
 *
 * A provider may hold several, which is the point: free tiers are per-key, so
 * two keys double the allowance and the router load-balances across them.
 */
data class KeyRecord(
    val platform: String,
    val label: String,
    /**
     * The secret. Null means "known to exist but not present here", what a
     * redacted export produces, and what the UI shows for a stored key it has
     * not been asked to reveal.
     */
    val secret: String? = null,
    /** Overrides the provider base URL, and identifies a custom endpoint. */
    val baseUrl: String? = null,
    val enabled: Boolean = true,
) {
    val hasSecret: Boolean get() = !secret.isNullOrBlank()
}

/**
 * Everything the router needs that is not learned at runtime.
 *
 * Health, latency, and quota observations deliberately live elsewhere: they are
 * measurements, not configuration, and exporting them would make a config file
 * device-specific.
 */
data class RouterSettings(
    val strategy: RoutingStrategy = DEFAULT_STRATEGY,
    /** Only meaningful when [strategy] is CUSTOM. */
    val customWeights: RoutingWeights? = null,
    val keySelection: KeySelectionStrategy = KeySelectionStrategy.AUTO,
    val keys: List<KeyRecord> = emptyList(),
    val providers: List<Provider> = BuiltinCatalog.providers,
    val models: List<ModelSpec> = BuiltinCatalog.models,
    /**
     * Model keys in explicit priority order. Drives the PRIORITY strategy and,
     * for the bandit strategies, breaks ties between equal scores.
     */
    val fallbackOrder: List<String> = emptyList(),
) {

    /** The weight vector in force. CUSTOM without weights falls back to balanced. */
    fun weights(): RoutingWeights =
        if (strategy == RoutingStrategy.CUSTOM) {
            customWeights ?: BANDIT_PRESETS.getValue(RoutingStrategy.BALANCED)
        } else {
            BANDIT_PRESETS[strategy] ?: BANDIT_PRESETS.getValue(RoutingStrategy.BALANCED)
        }

    fun provider(platform: String): Provider? = providers.firstOrNull { it.id == platform }

    fun keysFor(platform: String): List<KeyRecord> =
        keys.filter { it.platform == platform && it.enabled && it.hasSecret }

    /** Providers the user has actually supplied a usable key for. */
    fun configuredPlatforms(): Set<String> =
        keys.filter { it.enabled && it.hasSecret }.map { it.platform }.toSet()

    /** Models that are enabled, routable, and backed by a key. */
    fun routableModels(): List<ModelSpec> {
        val configured = configuredPlatforms()
        return models.filter { it.enabled && it.platform in configured }
    }

    fun withKey(record: KeyRecord): RouterSettings {
        val existing = keys.indexOfFirst {
            it.platform == record.platform && it.label == record.label
        }
        val next = keys.toMutableList()
        if (existing >= 0) next[existing] = record else next += record
        return copy(keys = next)
    }

    fun withoutKey(platform: String, label: String): RouterSettings =
        copy(keys = keys.filterNot { it.platform == platform && it.label == label })

    companion object {
        /** A custom endpoint is identified by its base URL, so derive a stable id from it. */
        fun customPlatformId(baseUrl: String): String {
            val host = baseUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .lowercase()
            return "custom:$host"
        }

        fun customProvider(baseUrl: String, label: String?): Provider {
            val id = customPlatformId(baseUrl)
            return Provider(
                id = id,
                label = label?.takeIf { it.isNotBlank() } ?: id.removePrefix("custom:"),
                baseUrl = baseUrl.trimEnd('/'),
                auth = Auth.Bearer,
                wire = Wire.OPENAI,
                custom = true,
            )
        }
    }
}

/**
 * The same list with one entry moved to a 1-based rank.
 *
 * Lives beside [RouterSettings.fallbackOrder] rather than in the screen that
 * calls it because it is an edit to that field, and because the clamping is the
 * whole point: a rank typed out of range lands at the nearest end instead of
 * being rejected, so 0 and 1 both mean first and nothing is silently ignored.
 */
fun List<String>.movedTo(key: String, position: Int): List<String> {
    val from = indexOf(key)
    if (from < 0) return this
    val to = (position - 1).coerceIn(0, lastIndex)
    if (to == from) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
