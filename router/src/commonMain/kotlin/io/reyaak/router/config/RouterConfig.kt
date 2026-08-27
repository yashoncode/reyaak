package io.reyaak.router.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The router interchange format.
 *
 * This is deliberately the same shape as freellmapi's declarative config, so a
 * file written for one works in the other. Two consequences follow from that
 * schema being `.strict()` on their side:
 *
 *  - **Export may only emit keys defined here.** Reyaak tracks a few things
 *    they do not (per-model max output tokens, JSON-mode support). Those are
 *    local-only and are re-derived from the builtin catalog on import rather
 *    than smuggled into the file, because an unknown key would make their
 *    importer reject the whole thing.
 *  - **Import is lenient.** Unknown keys are ignored and a malformed entry is
 *    skipped with a warning rather than failing the import, which is the only
 *    sane behaviour for a hand-edited file.
 *
 * `platform` is their name for what Reyaak calls a provider id; they are the
 * same string and are mapped at the boundary.
 */
@Serializable
data class RouterConfigFile(
    val keys: List<KeyEntry>? = null,
    val customProviders: List<CustomProviderEntry>? = null,
    val models: List<ModelEntry>? = null,
    val fallback: List<FallbackEntry>? = null,
    val routing: RoutingEntry? = null,
)

@Serializable
data class KeyEntry(
    val platform: String,
    val key: String? = null,
    val label: String? = null,
    val baseUrl: String? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class CustomProviderEntry(
    val baseUrl: String,
    val apiKey: String? = null,
    val label: String? = null,
    val models: List<CustomModelEntry> = emptyList(),
)

/**
 * An entry in a custom provider's model list.
 *
 * The format accepts either a bare model id string or a full object, so both
 * forms round-trip: a value carrying nothing but [model] is written back as a
 * plain string, exactly as a hand-written file would have it.
 */
@Serializable(with = CustomModelEntrySerializer::class)
data class CustomModelEntry(
    val model: String,
    val displayName: String? = null,
    val intelligenceRank: Int? = null,
    val speedRank: Int? = null,
    val sizeLabel: String? = null,
    val monthlyTokenBudget: String? = null,
    val contextWindow: Int? = null,
    val supportsVision: Boolean? = null,
    val supportsTools: Boolean? = null,
    val fallbackEnabled: Boolean? = null,
) {
    /** True when the compact string form carries the same information. */
    val isBareId: Boolean
        get() = displayName == null && intelligenceRank == null && speedRank == null &&
            sizeLabel == null && monthlyTokenBudget == null && contextWindow == null &&
            supportsVision == null && supportsTools == null && fallbackEnabled == null
}

/** The object form of [CustomModelEntry], used only for (de)serialization. */
@Serializable
private data class CustomModelObject(
    val model: String,
    val displayName: String? = null,
    val intelligenceRank: Int? = null,
    val speedRank: Int? = null,
    val sizeLabel: String? = null,
    val monthlyTokenBudget: String? = null,
    val contextWindow: Int? = null,
    val supportsVision: Boolean? = null,
    val supportsTools: Boolean? = null,
    val fallbackEnabled: Boolean? = null,
)

object CustomModelEntrySerializer : KSerializer<CustomModelEntry> {
    override val descriptor: SerialDescriptor = CustomModelObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): CustomModelEntry {
        val input = requireNotNull(decoder as? JsonDecoder) { "CustomModelEntry requires JSON" }
        val element = input.decodeJsonElement()
        if (element is JsonPrimitive && element.isString) {
            return CustomModelEntry(model = element.content)
        }
        val obj = input.json.decodeFromJsonElement(CustomModelObject.serializer(), element)
        return CustomModelEntry(
            model = obj.model,
            displayName = obj.displayName,
            intelligenceRank = obj.intelligenceRank,
            speedRank = obj.speedRank,
            sizeLabel = obj.sizeLabel,
            monthlyTokenBudget = obj.monthlyTokenBudget,
            contextWindow = obj.contextWindow,
            supportsVision = obj.supportsVision,
            supportsTools = obj.supportsTools,
            fallbackEnabled = obj.fallbackEnabled,
        )
    }

    override fun serialize(encoder: Encoder, value: CustomModelEntry) {
        if (value.isBareId) {
            encoder.encodeString(value.model)
            return
        }
        val output = requireNotNull(encoder as? JsonEncoder) { "CustomModelEntry requires JSON" }
        output.encodeJsonElement(
            output.json.encodeToJsonElement(
                CustomModelObject.serializer(),
                CustomModelObject(
                    model = value.model,
                    displayName = value.displayName,
                    intelligenceRank = value.intelligenceRank,
                    speedRank = value.speedRank,
                    sizeLabel = value.sizeLabel,
                    monthlyTokenBudget = value.monthlyTokenBudget,
                    contextWindow = value.contextWindow,
                    supportsVision = value.supportsVision,
                    supportsTools = value.supportsTools,
                    fallbackEnabled = value.fallbackEnabled,
                ),
            )
        )
    }
}

@Serializable
data class ModelEntry(
    val platform: String,
    val modelId: String,
    /** Which custom endpoint this means, when several serve the same model id. */
    val endpoint: String? = null,
    val displayName: String? = null,
    val intelligenceRank: Int? = null,
    val speedRank: Int? = null,
    val sizeLabel: String? = null,
    val rpmLimit: Int? = null,
    val rpdLimit: Int? = null,
    val tpmLimit: Int? = null,
    val tpdLimit: Int? = null,
    val monthlyTokenBudget: String? = null,
    val contextWindow: Int? = null,
    val enabled: Boolean? = null,
    val supportsVision: Boolean? = null,
    val supportsTools: Boolean? = null,
    val fallbackEnabled: Boolean? = null,
)

@Serializable
data class FallbackEntry(
    val platform: String,
    val modelId: String,
    val endpoint: String? = null,
    val priority: Int? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class RoutingEntry(
    val strategy: String,
    val weights: WeightsEntry? = null,
    val keySelectionStrategy: String? = null,
)

@Serializable
data class WeightsEntry(
    val reliability: Double,
    val speed: Double,
    val intelligence: Double,
)

/** Field bounds, matching the upstream schema so a rejected file is rejected here too. */
internal object Bounds {
    const val RANK_MIN = 1
    const val RANK_MAX = 1000
    const val SIZE_LABEL_MAX = 40
    const val BUDGET_MAX = 80

    val STRATEGIES = setOf("priority", "balanced", "smartest", "fastest", "reliable", "custom")
    val KEY_STRATEGIES = setOf("auto", "least-remaining")

    fun rankOk(v: Int?) = v == null || v in RANK_MIN..RANK_MAX
    fun positiveOrNull(v: Int?) = v == null || v > 0
    fun urlOk(v: String?) = v == null ||
        (v.startsWith("http://") || v.startsWith("https://")) && v.length > 8

    /** JsonObject helper used when reporting which keys an entry actually had. */
    fun keysOf(obj: JsonObject) = obj.keys.joinToString(", ")
}
