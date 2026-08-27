package io.reyaak.router.config

import io.reyaak.router.score.KeySelectionStrategy
import io.reyaak.router.score.RoutingStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interchange format is a compatibility promise, so these tests are about
 * the promise rather than about the code: a file freellmapi wrote must import,
 * and a file this exports must be one freellmapi will accept.
 */
class RouterConfigCodecTest {

    private val json = Json { prettyPrint = false }

    /** A config in the upstream shape, exercising every section. */
    private val upstreamConfig = """
    {
      "keys": [
        { "platform": "groq", "key": "gsk_test_aaa", "label": "primary" },
        { "platform": "groq", "key": "gsk_test_bbb", "label": "secondary" },
        { "platform": "gemini", "key": "AIza_test", "label": "studio" },
        { "platform": "custom", "key": "sk-local-1", "label": "LAN box",
          "baseUrl": "https://llm.example.com/v1" }
      ],
      "customProviders": [
        {
          "baseUrl": "https://llm.example.com/v1",
          "apiKey": "sk-local-1",
          "label": "LAN box",
          "models": [
            "plain-model-id",
            { "model": "tuned-70b", "displayName": "Tuned 70B", "sizeLabel": "Large",
              "intelligenceRank": 12, "speedRank": 4, "contextWindow": 32768,
              "supportsTools": true, "supportsVision": false }
          ]
        }
      ],
      "models": [
        { "platform": "groq", "modelId": "llama-3.3-70b-versatile",
          "intelligenceRank": 7, "speedRank": 1, "rpmLimit": 30, "rpdLimit": 1000,
          "tpmLimit": 6000, "tpdLimit": 500000, "sizeLabel": "Large",
          "contextWindow": 131072, "enabled": true, "supportsTools": true },
        { "platform": "gemini", "modelId": "gemini-2.5-flash", "speedRank": 2,
          "monthlyTokenBudget": "1M" }
      ],
      "fallback": [
        { "platform": "groq", "modelId": "llama-3.3-70b-versatile", "priority": 1 },
        { "platform": "gemini", "modelId": "gemini-2.5-flash", "priority": 2 },
        { "platform": "groq", "modelId": "llama-3.1-8b-instant", "priority": 3,
          "enabled": false }
      ],
      "routing": {
        "strategy": "custom",
        "weights": { "reliability": 0.6, "speed": 0.3, "intelligence": 0.1 },
        "keySelectionStrategy": "least-remaining"
      }
    }
    """.trimIndent()

    // ── Import ──────────────────────────────────────────────────────────────

    @Test
    fun `imports an upstream config with every section populated`() {
        val result = RouterConfigCodec.decode(upstreamConfig)

        assertTrue("unexpected warnings: ${result.warnings}", result.warnings.isEmpty())
        assertEquals(4, result.keysImported)
        assertEquals(2, result.modelsImported)
        assertEquals(1, result.customProviders)
        // The disabled third entry must not enter the order.
        assertEquals(2, result.fallbackEntries)
        assertTrue(result.routingApplied)

        val s = result.settings
        assertEquals(RoutingStrategy.CUSTOM, s.strategy)
        assertEquals(0.6, s.customWeights!!.reliability, 1e-9)
        assertEquals(KeySelectionStrategy.LEAST_REMAINING, s.keySelection)
    }

    @Test
    fun `two keys for one platform both survive, because free tiers are per-key`() {
        val s = RouterConfigCodec.decode(upstreamConfig).settings
        assertEquals(2, s.keysFor("groq").size)
    }

    @Test
    fun `a custom provider becomes a routable platform with its models`() {
        val s = RouterConfigCodec.decode(upstreamConfig).settings
        val id = RouterSettings.customPlatformId("https://llm.example.com/v1")
        assertEquals("custom:llm.example.com", id)

        val provider = s.provider(id)
        assertNotNull("custom provider missing", provider)
        assertEquals("LAN box", provider!!.label)
        assertTrue(provider.custom)

        val models = s.models.filter { it.platform == id }.map { it.modelId }.sorted()
        assertEquals(listOf("plain-model-id", "tuned-70b"), models)

        // The bare-string entry gets sensible defaults, not nulls.
        val plain = s.models.first { it.modelId == "plain-model-id" }
        assertEquals("plain-model-id", plain.displayName)
        assertEquals("Medium", plain.sizeLabel)

        // The object entry keeps what it declared.
        val tuned = s.models.first { it.modelId == "tuned-70b" }
        assertEquals("Tuned 70B", tuned.displayName)
        assertEquals("Large", tuned.sizeLabel)
        assertEquals(12, tuned.intelligenceRank)
        assertEquals(32768, tuned.contextWindow)
    }

    @Test
    fun `model overrides land on the catalog entry without replacing its local fields`() {
        val s = RouterConfigCodec.decode(upstreamConfig).settings
        val m = s.models.first { it.key == "groq/llama-3.3-70b-versatile" }
        assertEquals(7, m.intelligenceRank)
        assertEquals(30, m.rpmLimit)
        assertEquals(500_000, m.tpdLimit)
        // maxOutputTokens is local-only and must be preserved from the catalog,
        // not reset to the default by an import that never mentioned it.
        assertEquals(32_768, m.maxOutputTokens)
    }

    @Test
    fun `fallback order follows priority, not file order`() {
        val out = RouterConfigCodec.decode(
            """
            {"fallback":[
              {"platform":"gemini","modelId":"b","priority":5},
              {"platform":"groq","modelId":"a","priority":1}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf("groq/a", "gemini/b"), out.settings.fallbackOrder)
    }

    @Test
    fun `an unknown model id is added rather than rejected`() {
        // A config may name a model newer than this catalog.
        val out = RouterConfigCodec.decode(
            """{"models":[{"platform":"groq","modelId":"future-model-9000","sizeLabel":"Frontier"}]}"""
        )
        assertTrue(out.warnings.isEmpty())
        val m = out.settings.models.first { it.modelId == "future-model-9000" }
        assertEquals("Frontier", m.sizeLabel)
    }

    // ── Degradation, not failure ─────────────────────────────────────────────

    @Test
    fun `a bad entry is skipped with a warning while the rest applies`() {
        val out = RouterConfigCodec.decode(
            """
            {
              "keys": [
                { "platform": "nope-not-real", "key": "x" },
                { "platform": "groq", "label": "no key here" },
                { "platform": "groq", "key": "gsk_good", "label": "good" }
              ],
              "routing": { "strategy": "nonsense" }
            }
            """.trimIndent()
        )
        assertEquals(1, out.keysImported)
        assertEquals(1, out.settings.keysFor("groq").size)
        assertFalse(out.routingApplied)
        assertEquals(3, out.warnings.size)
        assertTrue(out.warnings.any { it.contains("unknown provider") })
        assertTrue(out.warnings.any { it.contains("requires an API key") })
        assertTrue(out.warnings.any { it.contains("unknown strategy") })
    }

    @Test
    fun `out of range ranks are ignored with a warning, keeping the entry`() {
        val out = RouterConfigCodec.decode(
            """{"models":[{"platform":"groq","modelId":"llama-3.1-8b-instant","intelligenceRank":9999}]}"""
        )
        assertEquals(1, out.modelsImported)
        assertTrue(out.warnings.any { it.contains("rank must be 1..1000") })
        // Falls back to the catalog value rather than storing the bad one.
        assertEquals(2, out.settings.models.first { it.modelId == "llama-3.1-8b-instant" }.intelligenceRank)
    }

    @Test
    fun `unknown top level keys are ignored rather than failing the import`() {
        val out = RouterConfigCodec.decode(
            """{"routing":{"strategy":"fastest"},"somethingNewUpstream":{"a":1}}"""
        )
        assertEquals(RoutingStrategy.FASTEST, out.settings.strategy)
    }

    @Test(expected = ConfigParseException::class)
    fun `malformed json is a parse failure, not a silent empty import`() {
        RouterConfigCodec.decode("{ this is not json")
    }

    // ── Export ──────────────────────────────────────────────────────────────

    @Test
    fun `export redacts secrets by default and includes them only on request`() {
        val settings = RouterConfigCodec.decode(upstreamConfig).settings

        val redacted = RouterConfigCodec.encode(settings)
        assertFalse("secret leaked into a redacted export", redacted.contains("gsk_test_aaa"))
        assertFalse(redacted.contains("AIza_test"))
        assertFalse(redacted.contains("sk-local-1"))
        // The entry itself survives, so the shape round-trips.
        assertTrue(redacted.contains("\"platform\": \"groq\""))
        assertTrue(redacted.contains("\"label\": \"primary\""))

        val full = RouterConfigCodec.encode(settings, includeSecrets = true)
        assertTrue(full.contains("gsk_test_aaa"))
        assertTrue(full.contains("sk-local-1"))
    }

    @Test
    fun `export emits only keys the upstream strict schema defines`() {
        // Upstream validates with a .strict() object schema, so a single extra
        // key would make it reject the entire file. This is the compatibility
        // guarantee, so it is asserted structurally rather than by eyeball.
        val settings = RouterConfigCodec.decode(upstreamConfig).settings
        val root = json.parseToJsonElement(
            RouterConfigCodec.encode(settings, includeSecrets = true)
        ) as JsonObject

        assertTrue("unexpected root keys: ${root.keys - ROOT_KEYS}", root.keys.all { it in ROOT_KEYS })

        (root["keys"] as? JsonArray)?.forEach { entry ->
            val obj = entry as JsonObject
            assertTrue("unexpected key entry fields: ${obj.keys - KEY_KEYS}", obj.keys.all { it in KEY_KEYS })
        }
        (root["models"] as? JsonArray)?.forEach { entry ->
            val obj = entry as JsonObject
            assertTrue("unexpected model fields: ${obj.keys - MODEL_KEYS}", obj.keys.all { it in MODEL_KEYS })
        }
        (root["fallback"] as? JsonArray)?.forEach { entry ->
            val obj = entry as JsonObject
            assertTrue("unexpected fallback fields: ${obj.keys - FALLBACK_KEYS}", obj.keys.all { it in FALLBACK_KEYS })
        }
        (root["customProviders"] as? JsonArray)?.forEach { entry ->
            val obj = entry as JsonObject
            assertTrue("unexpected customProvider fields: ${obj.keys - CUSTOM_PROVIDER_KEYS}",
                obj.keys.all { it in CUSTOM_PROVIDER_KEYS })
        }
        (root["routing"] as? JsonObject)?.let { obj ->
            assertTrue("unexpected routing fields: ${obj.keys - ROUTING_KEYS}", obj.keys.all { it in ROUTING_KEYS })
        }
    }

    @Test
    fun `a bare model id round-trips as a string, not as an object`() {
        val settings = RouterConfigCodec.decode(upstreamConfig).settings
        val root = json.parseToJsonElement(RouterConfigCodec.encode(settings)) as JsonObject
        val models = ((root["customProviders"] as JsonArray)[0] as JsonObject)["models"] as JsonArray

        val bare = models.filterIsInstance<JsonPrimitive>().map { it.content }
        assertTrue("bare id should stay a plain string", "plain-model-id" in bare)
        // The detailed entry stays an object.
        assertTrue(models.any { it is JsonObject && (it["model"] as JsonPrimitive).content == "tuned-70b" })
    }

    @Test
    fun `export omits catalog models that were never overridden`() {
        // A full dump would bury the deliberate overrides and pin this catalog's
        // ranks into a file meant to outlive it.
        val untouched = RouterSettings()
        val root = json.parseToJsonElement(RouterConfigCodec.encode(untouched)) as JsonObject
        assertNull(root["models"])
    }

    @Test
    fun `settings survive an export and re-import`() {
        val original = RouterConfigCodec.decode(upstreamConfig).settings
        val reimported = RouterConfigCodec
            .decode(RouterConfigCodec.encode(original, includeSecrets = true))
            .settings

        assertEquals(original.strategy, reimported.strategy)
        assertEquals(original.keySelection, reimported.keySelection)
        assertEquals(original.customWeights, reimported.customWeights)
        assertEquals(original.fallbackOrder, reimported.fallbackOrder)
        assertEquals(
            original.keys.map { it.platform to it.label }.toSet(),
            reimported.keys.map { it.platform to it.label }.toSet(),
        )
        assertEquals(
            original.models.first { it.key == "groq/llama-3.3-70b-versatile" }.rpmLimit,
            reimported.models.first { it.key == "groq/llama-3.3-70b-versatile" }.rpmLimit,
        )
    }

    // ── Compatibility with the upstream documented example ──────────────────

    /**
     * Copied verbatim from freellmapi docs/install.md. If this stops importing,
     * the compatibility claim is broken regardless of what the unit tests above
     * say about our own round trip.
     */
    private val upstreamDocsExample = """
    {
      "keys": [
        { "platform": "groq", "key": "gsk_...", "label": "main" },
        { "platform": "google", "key": "AIza...", "enabled": true }
      ],
      "customProviders": [
        {
          "baseUrl": "http://host.docker.internal:11434/v1",
          "label": "Ollama",
          "models": [
            { "model": "llama3.1:8b", "displayName": "Local Llama", "supportsTools": true }
          ]
        }
      ],
      "models": [
        {
          "platform": "groq",
          "modelId": "llama-3.3-70b-versatile",
          "displayName": "Llama 3.3 70B",
          "supportsTools": true,
          "fallbackEnabled": true
        }
      ],
      "routing": { "strategy": "balanced" }
    }
    """.trimIndent()

    @Test
    fun `the upstream documented example imports cleanly`() {
        val result = RouterConfigCodec.decode(upstreamDocsExample)
        assertTrue("unexpected warnings: ${result.warnings}", result.warnings.isEmpty())
        assertEquals(2, result.keysImported)
        assertEquals(1, result.customProviders)
        assertEquals(RoutingStrategy.BALANCED, result.settings.strategy)
    }

    @Test
    fun `platform google maps onto the gemini catalog id`() {
        // Upstream names Google's platform "google"; this catalog names it after
        // the model family. Without the alias the key is silently skipped, which
        // is the worst possible failure for an import.
        val s = RouterConfigCodec.decode(upstreamDocsExample).settings
        assertEquals(1, s.keysFor("gemini").size)
        assertTrue("gemini" in s.configuredPlatforms())
    }

    @Test
    fun `export writes google back, so the file is one upstream accepts`() {
        val settings = RouterConfigCodec.decode(upstreamDocsExample).settings
        val root = json.parseToJsonElement(
            RouterConfigCodec.encode(settings, includeSecrets = true)
        ) as JsonObject
        val platforms = (root["keys"] as JsonArray).map {
            ((it as JsonObject)["platform"] as JsonPrimitive).content
        }
        assertTrue("export must use the upstream platform name", "google" in platforms)
        assertFalse("gemini is not an upstream platform name", "gemini" in platforms)
    }

    @Test
    fun `an http custom endpoint is accepted, not just https`() {
        // The documented example uses http://host.docker.internal, so rejecting
        // plain http would fail on upstream's own sample.
        val result = RouterConfigCodec.decode(upstreamDocsExample)
        val id = RouterSettings.customPlatformId("http://host.docker.internal:11434/v1")
        assertNotNull(result.settings.provider(id))
    }

    @Test
    fun `google survives a full export and re-import round trip`() {
        val once = RouterConfigCodec.decode(upstreamDocsExample).settings
        val twice = RouterConfigCodec
            .decode(RouterConfigCodec.encode(once, includeSecrets = true))
            .settings
        assertEquals(once.keysFor("gemini").size, twice.keysFor("gemini").size)
        assertEquals(once.configuredPlatforms(), twice.configuredPlatforms())
    }

    private companion object {
        val ROOT_KEYS = setOf("keys", "customProviders", "models", "fallback", "routing")
        val KEY_KEYS = setOf("platform", "key", "label", "baseUrl", "enabled")
        val MODEL_KEYS = setOf(
            "platform", "modelId", "endpoint", "displayName", "intelligenceRank", "speedRank",
            "sizeLabel", "rpmLimit", "rpdLimit", "tpmLimit", "tpdLimit", "monthlyTokenBudget",
            "contextWindow", "enabled", "supportsVision", "supportsTools", "fallbackEnabled",
        )
        val FALLBACK_KEYS = setOf("platform", "modelId", "endpoint", "priority", "enabled")
        val CUSTOM_PROVIDER_KEYS = setOf("baseUrl", "apiKey", "label", "models")
        val ROUTING_KEYS = setOf("strategy", "weights", "keySelectionStrategy")
    }
}
