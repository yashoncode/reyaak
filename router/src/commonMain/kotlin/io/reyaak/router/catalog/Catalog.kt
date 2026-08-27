package io.reyaak.router.catalog

/**
 * How a provider frames requests on the wire.
 *
 * Almost every free provider speaks OpenAI's chat/completions shape, which is
 * why there is one generic adapter and only one exception. Google is the
 * exception worth handling natively: its error bodies carry a
 * `google.rpc.RetryInfo` retry delay that the OpenAI-compatibility shim drops,
 * and that delay is exactly what the router wants when it sets a cooldown.
 */
enum class Wire { OPENAI, GEMINI }

/** How the API key is presented. */
sealed interface Auth {
    /** `Authorization: Bearer <key>` — the overwhelming majority. */
    data object Bearer : Auth

    /** A bare key in a named header, e.g. Google's `x-goog-api-key`. */
    data class Header(val name: String) : Auth
}

data class Provider(
    /**
     * Stable id. Called "platform" in the interchange format, and the two are
     * the same string: the field is named `id` here and mapped on export.
     */
    val id: String,
    val label: String,
    val baseUrl: String,
    val auth: Auth,
    val wire: Wire,
    /** Where the user gets a key. Shown on the Router screen. */
    val consoleUrl: String = "",
    /** True for providers added by the user rather than shipped in the catalog. */
    val custom: Boolean = false,
)

/**
 * A model and everything the router knows about it.
 *
 * Field names and ranges deliberately mirror the freellmapi declarative-config
 * `models[]` entry so a config file round-trips without loss. The last three
 * fields are local-only and are NOT exported: that schema is strict, so emitting
 * an unknown key would make freellmapi reject the file. They are re-derived from
 * the builtin catalog on import.
 */
data class ModelSpec(
    val platform: String,
    val modelId: String,
    val displayName: String,
    /** Cross-provider capability tier: Frontier | Large | Medium | Small. */
    val sizeLabel: String,
    /** 1..1000, 1 = best. Feeds the intelligence axis. */
    val intelligenceRank: Int = 500,
    /** 1..1000, 1 = fastest. A seed for the speed axis until real samples land. */
    val speedRank: Int = 500,
    val contextWindow: Int? = null,
    val rpmLimit: Int? = null,
    val rpdLimit: Int? = null,
    val tpmLimit: Int? = null,
    val tpdLimit: Int? = null,
    /** Free-form in the interchange format, e.g. "1M". Kept as text. */
    val monthlyTokenBudget: String? = null,
    val enabled: Boolean = true,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    /** Whether this model may be used as a fallback candidate. */
    val fallbackEnabled: Boolean = true,

    // ── Local only, never exported ──────────────────────────────────────────
    val maxOutputTokens: Int = 8_192,
    val supportsJsonMode: Boolean = true,
    val supportsStreaming: Boolean = true,
) {
    /** Stable identity across the catalog. */
    val key: String get() = "$platform/$modelId"
}

/**
 * The seed catalog.
 *
 * Model ids drift as providers retire and rename things, so this is a starting
 * point, not a source of truth: every OpenAI-wire provider exposes `/v1/models`
 * and the Router screen can refresh from it. An entry the provider no longer
 * serves fails with a 404, which the classifier reads as "model pulled
 * upstream" and benches the model rather than the key.
 *
 * Rate limits are left null rather than guessed. An invented limit is worse
 * than none: the headroom guardrail treats null as "no opinion" and returns
 * 1.0, whereas a wrong number silently demotes a healthy model. Real limits
 * arrive from 429s and their stated retry delays, or from an imported config.
 */
object BuiltinCatalog {

    val providers: List<Provider> = listOf(
        Provider(
            id = "gemini",
            label = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            auth = Auth.Header("x-goog-api-key"),
            wire = Wire.GEMINI,
            consoleUrl = "https://aistudio.google.com/apikey",
        ),
        Provider(
            id = "groq",
            label = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://console.groq.com/keys",
        ),
        Provider(
            id = "cerebras",
            label = "Cerebras",
            baseUrl = "https://api.cerebras.ai/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://cloud.cerebras.ai",
        ),
        Provider(
            id = "mistral",
            label = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://console.mistral.ai/api-keys",
        ),
        Provider(
            id = "nvidia",
            label = "NVIDIA NIM",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://build.nvidia.com",
        ),
        Provider(
            id = "openrouter",
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://openrouter.ai/keys",
        ),
        Provider(
            id = "sarvam",
            label = "Sarvam AI",
            baseUrl = "https://api.sarvam.ai/v1",
            // Sarvam documents `api-subscription-key` first and accepts the same
            // key as a Bearer token, so Bearer is what this uses: it needs no
            // adapter change, and the wire is otherwise OpenAI chat/completions.
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://dashboard.sarvam.ai",
        ),
        // ── Additional free-tier providers ──────────────────────────────────
        // Every one of these speaks the OpenAI chat/completions shape with a
        // Bearer key, so they need no adapter code at all, only this row.
        // Models are deliberately NOT seeded for them: the upstream catalogue
        // is a live service that resyncs twice a day, so a hardcoded model id
        // is stale the week it is written. `listModels` probes /v1/models
        // instead, which is what "Refresh models" on the Router screen calls.

        Provider(
            id = "github",
            label = "GitHub Models",
            baseUrl = "https://models.github.ai/inference",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://github.com/settings/tokens",
        ),
        Provider(
            id = "huggingface",
            label = "HuggingFace",
            baseUrl = "https://router.huggingface.co/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://huggingface.co/settings/tokens",
        ),
        Provider(
            id = "zhipu",
            label = "Z.ai (Zhipu)",
            baseUrl = "https://api.z.ai/api/paas/v4",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://z.ai/manage-apikey/apikey-list",
        ),
        Provider(
            id = "modelscope",
            label = "ModelScope",
            baseUrl = "https://api-inference.modelscope.cn/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://modelscope.cn/my/myaccesstoken",
        ),
        Provider(
            id = "siliconflow",
            label = "SiliconFlow",
            baseUrl = "https://api.siliconflow.com/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://cloud.siliconflow.com/account/ak",
        ),
        Provider(
            id = "ollama",
            label = "Ollama Cloud",
            baseUrl = "https://ollama.com/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://ollama.com/settings/keys",
        ),
        Provider(
            id = "opencode",
            label = "OpenCode Zen",
            baseUrl = "https://opencode.ai/zen/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://opencode.ai/auth",
        ),
        Provider(
            id = "requesty",
            label = "Requesty",
            baseUrl = "https://router.requesty.ai/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://app.requesty.ai/api-keys",
        ),
        Provider(
            id = "reka",
            label = "Reka",
            baseUrl = "https://api.reka.ai/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://platform.reka.ai",
        ),
        Provider(
            id = "sealion",
            label = "SEA-LION",
            baseUrl = "https://api.sea-lion.ai/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://docs.sea-lion.ai",
        ),
        Provider(
            id = "llm7",
            label = "LLM7",
            baseUrl = "https://api.llm7.io/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://token.llm7.io",
        ),
        Provider(
            id = "pollinations",
            label = "Pollinations",
            baseUrl = "https://gen.pollinations.ai",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://pollinations.ai",
        ),
        Provider(
            id = "ovh",
            label = "OVHcloud AI Endpoints",
            baseUrl = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://endpoints.ai.cloud.ovh.net",
        ),
        Provider(
            id = "kilo",
            label = "Kilo Gateway",
            baseUrl = "https://api.kilo.ai/api/gateway/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://kilo.ai",
        ),
        Provider(
            id = "aion",
            label = "Aion Labs",
            baseUrl = "https://api.aionlabs.ai/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://aionlabs.ai",
        ),
        Provider(
            id = "ainative",
            label = "AINative Studio",
            baseUrl = "https://api.ainative.studio/api/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://ainative.studio",
        ),
        Provider(
            id = "agnes",
            label = "Agnes AI",
            baseUrl = "https://apihub.agnes-ai.com/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://agnes-ai.com",
        ),
        Provider(
            id = "anyapi",
            label = "AnyAPI",
            baseUrl = "https://api.anyapi.ai/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://anyapi.ai",
        ),
        Provider(
            id = "bai",
            label = "B.AI",
            baseUrl = "https://api.b.ai/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://b.ai",
        ),
        Provider(
            id = "routeway",
            label = "Routeway",
            baseUrl = "https://api.routeway.ai/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://routeway.ai",
        ),
        Provider(
            id = "bazaarlink",
            label = "BazaarLink",
            baseUrl = "https://bazaarlink.ai/api/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://bazaarlink.ai",
        ),
        Provider(
            id = "navy",
            label = "NavyAI",
            baseUrl = "https://api.navy/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://api.navy",
        ),
        Provider(
            id = "nara",
            label = "NaraRouter",
            baseUrl = "https://router.bynara.id/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://bynara.id",
        ),
        Provider(
            id = "orcarouter",
            label = "OrcaRouter",
            baseUrl = "https://api.orcarouter.ai/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://orcarouter.ai",
        ),
        Provider(
            id = "unorouter",
            label = "UnoRouter",
            baseUrl = "https://api.unorouter.com/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://unorouter.com",
        ),
        Provider(
            id = "xkiro",
            label = "xKiro",
            baseUrl = "https://api.xkiro.com/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://xkiro.com",
        ),
        Provider(
            id = "longcat",
            label = "LongCat",
            baseUrl = "https://api.longcat.chat/openai/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://longcat.chat/platform",
        ),
        Provider(
            id = "qianfan",
            label = "Baidu Qianfan",
            baseUrl = "https://qianfan.baidubce.com/v2",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://console.bce.baidu.com/qianfan",
        ),
        Provider(
            id = "volcengine",
            label = "Volcengine Ark",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://console.volcengine.com/ark",
        ),
        Provider(
            id = "xfyun",
            label = "iFlytek Spark",
            baseUrl = "https://spark-api-open.xf-yun.com/v1",
            auth = Auth.Bearer,
            wire = Wire.OPENAI,
            consoleUrl = "https://console.xfyun.cn",
        ),
    )

    val models: List<ModelSpec> = listOf(
        // ── Google Gemini ───────────────────────────────────────────────────
        ModelSpec(
            "gemini", "gemini-2.5-pro", "Gemini 2.5 Pro", "Frontier",
            intelligenceRank = 2, speedRank = 6, contextWindow = 1_048_576,
            supportsVision = true, maxOutputTokens = 65_536,
        ),
        ModelSpec(
            "gemini", "gemini-2.5-flash", "Gemini 2.5 Flash", "Large",
            intelligenceRank = 2, speedRank = 3, contextWindow = 1_048_576,
            supportsVision = true, maxOutputTokens = 65_536,
        ),
        ModelSpec(
            "gemini", "gemini-2.0-flash", "Gemini 2.0 Flash", "Large",
            intelligenceRank = 4, speedRank = 2, contextWindow = 1_048_576,
            supportsVision = true, maxOutputTokens = 8_192,
        ),

        // ── Groq ────────────────────────────────────────────────────────────
        ModelSpec(
            "groq", "llama-3.3-70b-versatile", "Llama 3.3 70B", "Large",
            intelligenceRank = 3, speedRank = 1, contextWindow = 131_072,
            maxOutputTokens = 32_768,
        ),
        ModelSpec(
            "groq", "openai/gpt-oss-120b", "GPT-OSS 120B", "Large",
            intelligenceRank = 2, speedRank = 2, contextWindow = 131_072,
            maxOutputTokens = 32_768,
        ),
        ModelSpec(
            "groq", "qwen/qwen3-32b", "Qwen3 32B", "Medium",
            intelligenceRank = 2, speedRank = 1, contextWindow = 131_072,
            maxOutputTokens = 16_384,
        ),
        ModelSpec(
            "groq", "llama-3.1-8b-instant", "Llama 3.1 8B", "Small",
            intelligenceRank = 2, speedRank = 1, contextWindow = 131_072,
            maxOutputTokens = 8_192,
        ),

        // ── Cerebras ────────────────────────────────────────────────────────
        ModelSpec(
            "cerebras", "llama-3.3-70b", "Llama 3.3 70B", "Large",
            intelligenceRank = 3, speedRank = 1, contextWindow = 65_536,
        ),
        ModelSpec(
            "cerebras", "qwen-3-32b", "Qwen3 32B", "Medium",
            intelligenceRank = 3, speedRank = 1, contextWindow = 65_536,
        ),
        ModelSpec(
            "cerebras", "llama3.1-8b", "Llama 3.1 8B", "Small",
            intelligenceRank = 2, speedRank = 1, contextWindow = 32_768,
        ),

        // ── Mistral ─────────────────────────────────────────────────────────
        ModelSpec(
            "mistral", "mistral-large-latest", "Mistral Large", "Large",
            intelligenceRank = 2, speedRank = 5, contextWindow = 131_072,
            maxOutputTokens = 32_768,
        ),
        ModelSpec(
            "mistral", "mistral-small-latest", "Mistral Small", "Medium",
            intelligenceRank = 2, speedRank = 3, contextWindow = 131_072,
            maxOutputTokens = 32_768,
        ),
        ModelSpec(
            "mistral", "open-mistral-nemo", "Mistral Nemo", "Small",
            intelligenceRank = 3, speedRank = 3, contextWindow = 131_072,
            maxOutputTokens = 16_384,
        ),

        // ── NVIDIA NIM ──────────────────────────────────────────────────────
        ModelSpec(
            "nvidia", "meta/llama-3.3-70b-instruct", "Llama 3.3 70B", "Large",
            intelligenceRank = 4, speedRank = 6, contextWindow = 131_072,
        ),
        ModelSpec(
            "nvidia", "deepseek-ai/deepseek-r1", "DeepSeek R1", "Frontier",
            intelligenceRank = 4, speedRank = 9, contextWindow = 65_536,
            supportsTools = false, maxOutputTokens = 16_384,
        ),

        // ── OpenRouter ──────────────────────────────────────────────────────
        ModelSpec(
            "openrouter", "openrouter/auto", "OpenRouter Auto", "Large",
            intelligenceRank = 5, speedRank = 5, contextWindow = 131_072,
            maxOutputTokens = 16_384,
        ),
        // ── Wider seed set ──────────────────────────────────────────────────
        // Still a seed, not a catalogue: "Refresh models" replaces these from
        // each provider's own /v1/models, which is the only list that is ever
        // current. An id a provider has retired 404s, which the classifier
        // reads as "pulled upstream" and benches the model, not the key.

        ModelSpec(
            "gemini", "gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", "Medium",
            intelligenceRank = 6, speedRank = 2, contextWindow = 1_048_576,
            supportsVision = true, maxOutputTokens = 65_536,
        ),
        ModelSpec(
            "gemini", "gemini-2.0-flash-lite", "Gemini 2.0 Flash Lite", "Small",
            intelligenceRank = 8, speedRank = 2, contextWindow = 1_048_576,
            supportsVision = true, maxOutputTokens = 8_192,
        ),

        ModelSpec(
            "groq", "meta-llama/llama-4-scout-17b-16e-instruct", "Llama 4 Scout", "Large",
            intelligenceRank = 3, speedRank = 1, contextWindow = 131_072,
            supportsVision = true, maxOutputTokens = 8_192,
        ),
        ModelSpec(
            "groq", "meta-llama/llama-4-maverick-17b-128e-instruct", "Llama 4 Maverick", "Large",
            intelligenceRank = 2, speedRank = 2, contextWindow = 131_072,
            supportsVision = true, maxOutputTokens = 8_192,
        ),
        ModelSpec(
            "groq", "moonshotai/kimi-k2-instruct", "Kimi K2", "Frontier",
            intelligenceRank = 2, speedRank = 3, contextWindow = 131_072,
            maxOutputTokens = 16_384,
        ),
        ModelSpec(
            "groq", "openai/gpt-oss-20b", "GPT-OSS 20B", "Medium",
            intelligenceRank = 4, speedRank = 1, contextWindow = 131_072,
            maxOutputTokens = 32_768,
        ),
        ModelSpec(
            "groq", "deepseek-r1-distill-llama-70b", "DeepSeek R1 Distill 70B", "Large",
            intelligenceRank = 2, speedRank = 3, contextWindow = 131_072,
            maxOutputTokens = 16_384,
        ),
        ModelSpec(
            "groq", "gemma2-9b-it", "Gemma 2 9B", "Small",
            intelligenceRank = 8, speedRank = 1, contextWindow = 8_192,
            maxOutputTokens = 8_192,
        ),

        ModelSpec(
            "cerebras", "gpt-oss-120b", "GPT-OSS 120B", "Large",
            intelligenceRank = 2, speedRank = 1, contextWindow = 131_072,
            maxOutputTokens = 32_768,
        ),
        ModelSpec(
            "cerebras", "llama-4-scout-17b-16e-instruct", "Llama 4 Scout", "Large",
            intelligenceRank = 3, speedRank = 1, contextWindow = 131_072,
            maxOutputTokens = 8_192,
        ),
        ModelSpec(
            "cerebras", "qwen-3-235b-a22b-instruct-2507", "Qwen3 235B", "Frontier",
            intelligenceRank = 2, speedRank = 2, contextWindow = 131_072,
            maxOutputTokens = 32_768,
        ),

        ModelSpec(
            "mistral", "magistral-small-latest", "Magistral Small", "Medium",
            intelligenceRank = 4, speedRank = 4, contextWindow = 40_960,
            maxOutputTokens = 40_960,
        ),
        ModelSpec(
            "mistral", "devstral-small-latest", "Devstral Small", "Medium",
            intelligenceRank = 4, speedRank = 4, contextWindow = 131_072,
            maxOutputTokens = 32_768,
        ),
        ModelSpec(
            "mistral", "pixtral-12b-2409", "Pixtral 12B", "Small",
            intelligenceRank = 7, speedRank = 3, contextWindow = 131_072,
            supportsVision = true, maxOutputTokens = 8_192,
        ),

        ModelSpec(
            "nvidia", "meta/llama-4-maverick-17b-128e-instruct", "Llama 4 Maverick", "Large",
            intelligenceRank = 2, speedRank = 4, contextWindow = 131_072,
            supportsVision = true, maxOutputTokens = 8_192,
        ),
        ModelSpec(
            "nvidia", "qwen/qwen3-235b-a22b", "Qwen3 235B", "Frontier",
            intelligenceRank = 2, speedRank = 5, contextWindow = 131_072,
            maxOutputTokens = 32_768,
        ),
        ModelSpec(
            "nvidia", "nvidia/llama-3.3-nemotron-super-49b-v1", "Nemotron Super 49B", "Large",
            intelligenceRank = 3, speedRank = 4, contextWindow = 131_072,
            maxOutputTokens = 16_384,
        ),
        ModelSpec(
            "nvidia", "mistralai/mistral-small-24b-instruct", "Mistral Small 24B", "Medium",
            intelligenceRank = 5, speedRank = 3, contextWindow = 32_768,
            maxOutputTokens = 8_192,
        ),

        // OpenRouter's `:free` suffix is what makes the endpoint free-tier, so
        // it is part of the model id rather than a flag.
        ModelSpec(
            "openrouter", "deepseek/deepseek-r1:free", "DeepSeek R1 (free)", "Frontier",
            intelligenceRank = 2, speedRank = 6, contextWindow = 163_840,
            maxOutputTokens = 16_384,
        ),
        ModelSpec(
            "openrouter", "meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B (free)", "Large",
            intelligenceRank = 3, speedRank = 4, contextWindow = 131_072,
            maxOutputTokens = 8_192,
        ),
        ModelSpec(
            "openrouter", "qwen/qwen3-235b-a22b:free", "Qwen3 235B (free)", "Frontier",
            intelligenceRank = 2, speedRank = 5, contextWindow = 131_072,
            maxOutputTokens = 16_384,
        ),
        ModelSpec(
            "openrouter", "google/gemma-3-27b-it:free", "Gemma 3 27B (free)", "Medium",
            intelligenceRank = 6, speedRank = 3, contextWindow = 96_000,
            supportsVision = true, maxOutputTokens = 8_192,
        ),
        ModelSpec(
            "openrouter", "mistralai/mistral-small-3.2-24b-instruct:free", "Mistral Small 3.2 (free)", "Medium",
            intelligenceRank = 5, speedRank = 3, contextWindow = 131_072,
            maxOutputTokens = 8_192,
        ),

        // ── Sarvam AI ───────────────────────────────────────────────────────
        // Seeded rather than discovered: Sarvam serves no /v1/models endpoint,
        // so "Refresh models" reports a failure for this platform and the
        // startup sync leaves it alone (an unlistable provider is "unknown",
        // never "serves nothing"). These ids are therefore the catalog.
        ModelSpec(
            "sarvam", "sarvam-105b", "Sarvam 105B", "Large",
            intelligenceRank = 4, speedRank = 5, contextWindow = 131_072,
            maxOutputTokens = 16_384,
        ),
        ModelSpec(
            "sarvam", "sarvam-105b-conversations", "Sarvam 105B Conversations", "Large",
            // Tuned for real-time conversational turns, so it is seeded fast and
            // short: a long structured answer is not what it is for.
            intelligenceRank = 6, speedRank = 3, contextWindow = 131_072,
            maxOutputTokens = 4_096,
        ),

        ModelSpec(
            "zhipu", "glm-4.5-flash", "GLM 4.5 Flash", "Medium",
            intelligenceRank = 4, speedRank = 2, contextWindow = 131_072,
            maxOutputTokens = 16_384,
        ),
        ModelSpec(
            "zhipu", "glm-4-flash", "GLM 4 Flash", "Small",
            intelligenceRank = 7, speedRank = 1, contextWindow = 131_072,
            maxOutputTokens = 8_192,
        ),

        ModelSpec(
            "github", "openai/gpt-4.1-mini", "GPT-4.1 Mini", "Medium",
            intelligenceRank = 4, speedRank = 3, contextWindow = 128_000,
            supportsVision = true, maxOutputTokens = 16_384,
        ),
        ModelSpec(
            "github", "openai/gpt-4o-mini", "GPT-4o Mini", "Medium",
            intelligenceRank = 5, speedRank = 2, contextWindow = 128_000,
            supportsVision = true, maxOutputTokens = 16_384,
        ),
    )

    fun provider(id: String): Provider? = providers.firstOrNull { it.id == id }

    fun modelsFor(platform: String): List<ModelSpec> = models.filter { it.platform == platform }

    fun model(key: String): ModelSpec? = models.firstOrNull { it.key == key }
}
