package io.reyaak.router

import io.reyaak.router.catalog.Auth
import io.reyaak.router.catalog.ModelSpec
import io.reyaak.router.catalog.Provider
import io.reyaak.router.catalog.Wire
import io.reyaak.router.config.KeyRecord
import io.reyaak.router.config.RouterSettings
import io.reyaak.router.error.ErrorClass
import io.reyaak.router.error.ProviderException
import io.reyaak.router.health.HealthStore
import io.reyaak.router.limit.RateLimiter
import io.reyaak.router.model.ChatMessage
import io.reyaak.router.model.ChatRequest
import io.reyaak.router.model.ChatResponse
import io.reyaak.router.model.FinishReason
import io.reyaak.router.model.Role
import io.reyaak.router.model.Served
import io.reyaak.router.model.StreamEvent
import io.reyaak.router.model.ToolDefinition
import io.reyaak.router.model.Usage
import io.reyaak.router.provider.AttemptContext
import io.reyaak.router.provider.ProviderAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RouterTest {

    // ── Fixtures ────────────────────────────────────────────────────────────

    private val fastProvider = Provider(
        "fast", "Fast Co", "https://fast.example/v1", Auth.Bearer, Wire.OPENAI,
    )
    private val smartProvider = Provider(
        "smart", "Smart Co", "https://smart.example/v1", Auth.Bearer, Wire.OPENAI,
    )

    private val fastModel = ModelSpec(
        platform = "fast", modelId = "quick-8b", displayName = "Quick 8B",
        sizeLabel = "Small", intelligenceRank = 1, speedRank = 1,
        contextWindow = 8_000,
    )
    private val smartModel = ModelSpec(
        platform = "smart", modelId = "big-70b", displayName = "Big 70B",
        sizeLabel = "Frontier", intelligenceRank = 1, speedRank = 9,
        contextWindow = 200_000,
    )
    private val noToolsModel = ModelSpec(
        platform = "smart", modelId = "no-tools", displayName = "No Tools",
        sizeLabel = "Large", supportsTools = false, contextWindow = 100_000,
    )

    private fun settings(
        models: List<ModelSpec> = listOf(fastModel, smartModel),
        keys: List<KeyRecord> = listOf(
            KeyRecord("fast", "k1", "secret-fast"),
            KeyRecord("smart", "k1", "secret-smart"),
        ),
        strategy: io.reyaak.router.score.RoutingStrategy =
            io.reyaak.router.score.RoutingStrategy.BALANCED,
        fallbackOrder: List<String> = emptyList(),
    ) = RouterSettings(
        strategy = strategy,
        keys = keys,
        providers = listOf(fastProvider, smartProvider),
        models = models,
        fallbackOrder = fallbackOrder,
    )

    private fun ask(text: String = "hello", tools: List<ToolDefinition> = emptyList()) =
        ChatRequest(messages = listOf(ChatMessage(Role.USER, text)), tools = tools)

    /** Records what it was asked, and answers however the test says. */
    private class FakeAdapter(
        private val behaviour: (AttemptContext) -> Result<String>,
    ) : ProviderAdapter {
        val seen = mutableListOf<String>()

        override suspend fun complete(ctx: AttemptContext, request: ChatRequest): ChatResponse {
            seen += "${ctx.provider.id}/${ctx.model.modelId}"
            val text = behaviour(ctx).getOrThrow()
            return ChatResponse(
                message = ChatMessage(Role.ASSISTANT, text),
                finishReason = FinishReason.STOP,
                usage = Usage(10, 20),
                served = Served(ctx.provider.id, ctx.model.modelId, 1, 5),
            )
        }

        override fun stream(ctx: AttemptContext, request: ChatRequest): Flow<StreamEvent> = flow {
            seen += "${ctx.provider.id}/${ctx.model.modelId}"
            val outcome = behaviour(ctx)
            if (outcome.isFailure) throw outcome.exceptionOrNull()!!
            emit(StreamEvent.Text(outcome.getOrThrow()))
            emit(
                StreamEvent.Done(
                    FinishReason.STOP, Usage(10, 20),
                    Served(ctx.provider.id, ctx.model.modelId, 1, 5),
                )
            )
        }

        override suspend fun listModels(ctx: AttemptContext) = emptyList<String>()
    }

    /** An adapter that emits some text and THEN fails, to test the commit rule. */
    private class FailsMidStream : ProviderAdapter {
        override suspend fun complete(ctx: AttemptContext, request: ChatRequest): ChatResponse =
            throw fail(ctx)

        override fun stream(ctx: AttemptContext, request: ChatRequest): Flow<StreamEvent> = flow {
            emit(StreamEvent.Text("partial answer"))
            throw fail(ctx)
        }

        override suspend fun listModels(ctx: AttemptContext) = emptyList<String>()

        private fun fail(ctx: AttemptContext) = ProviderException(
            ctx.provider.id, ctx.model.modelId, 500, ErrorClass.TRANSIENT, null, "boom",
        )
    }

    private fun oops(ctx: AttemptContext, cls: ErrorClass, status: Int?) = ProviderException(
        ctx.provider.id, ctx.model.modelId, status, cls, null, "synthetic ${cls.name}",
    )

    private fun router(
        settings: RouterSettings,
        adapter: ProviderAdapter,
        health: HealthStore = HealthStore(),
        limiter: RateLimiter = RateLimiter(),
        nowMs: Long = 1_000_000L,
    ) = Router(
        settingsProvider = { settings },
        health = health,
        limiter = limiter,
        rng = Random(7),
        now = { nowMs },
        adapters = mapOf(Wire.OPENAI to adapter, Wire.GEMINI to adapter),
    )

    // ── Selection ───────────────────────────────────────────────────────────

    @Test
    fun `a model with no key is not a candidate`() = runTest {
        val r = router(
            settings(keys = listOf(KeyRecord("fast", "k1", "secret-fast"))),
            FakeAdapter { Result.success("ok") },
        )
        val selection = r.select(ask())
        assertEquals(listOf("fast/quick-8b"), selection.candidates.map { it.model.key })
        assertTrue(selection.exclusions.any { it.reason.contains("no key for smart") })
    }

    @Test
    fun `a request needing tools excludes models that cannot use them`() = runTest {
        val r = router(
            settings(models = listOf(fastModel, noToolsModel)),
            FakeAdapter { Result.success("ok") },
        )
        val tool = ToolDefinition("t", "does a thing", """{"type":"object"}""")
        val selection = r.select(ask(tools = listOf(tool)))
        assertEquals(listOf("fast/quick-8b"), selection.candidates.map { it.model.key })
        assertTrue(selection.exclusions.any { it.reason == "no tool support" })
    }

    @Test
    fun `a prompt larger than the context window excludes that model`() = runTest {
        val r = router(settings(), FakeAdapter { Result.success("ok") })
        // ~40k tokens at 4 chars each: over quick-8b, well under big-70b.
        val selection = r.select(ask("x".repeat(160_000)))
        assertEquals(listOf("smart/big-70b"), selection.candidates.map { it.model.key })
        assertTrue(selection.exclusions.any { it.reason.contains("context too small") })
    }

    @Test
    fun `a benched target is excluded until its cooldown expires`() = runTest {
        val health = HealthStore(now = { 1_000_000L })
        health.recordFailure(
            HealthStore.keyOf("fast", "quick-8b", "k1"),
            ErrorClass.RATE_LIMITED, "429", statedRetryAfterMs = 60_000,
        )
        val r = router(settings(), FakeAdapter { Result.success("ok") }, health = health)
        val selection = r.select(ask())
        assertEquals(listOf("smart/big-70b"), selection.candidates.map { it.model.key })
        assertTrue(selection.exclusions.any { it.reason.contains("cooling down") })
    }

    @Test
    fun `two keys on one platform each become their own candidate`() = runTest {
        val r = router(
            settings(
                models = listOf(fastModel),
                keys = listOf(
                    KeyRecord("fast", "k1", "aaa"),
                    KeyRecord("fast", "k2", "bbb"),
                ),
            ),
            FakeAdapter { Result.success("ok") },
        )
        // Free tiers are per-key, so a second key is genuinely more capacity.
        assertEquals(2, r.select(ask()).candidates.size)
    }

    @Test
    fun `a disabled key is not usable`() = runTest {
        val r = router(
            settings(
                models = listOf(fastModel),
                keys = listOf(KeyRecord("fast", "k1", "aaa", enabled = false)),
            ),
            FakeAdapter { Result.success("ok") },
        )
        assertTrue(r.select(ask()).candidates.isEmpty())
    }

    @Test
    fun `the fastest preset prefers the fast model and smartest prefers the smart one`() = runTest {
        val fastest = router(
            settings(strategy = io.reyaak.router.score.RoutingStrategy.FASTEST),
            FakeAdapter { Result.success("ok") },
        )
        assertEquals("fast/quick-8b", fastest.select(ask()).candidates.first().model.key)

        val smartest = router(
            settings(strategy = io.reyaak.router.score.RoutingStrategy.SMARTEST),
            FakeAdapter { Result.success("ok") },
        )
        assertEquals("smart/big-70b", smartest.select(ask()).candidates.first().model.key)
    }

    @Test
    fun `the priority strategy follows the declared order and ignores scoring`() = runTest {
        val r = router(
            settings(
                strategy = io.reyaak.router.score.RoutingStrategy.PRIORITY,
                fallbackOrder = listOf("smart/big-70b", "fast/quick-8b"),
            ),
            FakeAdapter { Result.success("ok") },
        )
        assertEquals(
            listOf("smart/big-70b", "fast/quick-8b"),
            r.select(ask()).candidates.map { it.model.key },
        )
    }

    // ── Fallback ────────────────────────────────────────────────────────────

    @Test
    fun `a failing candidate falls over to the next one`() = runTest {
        val adapter = FakeAdapter { ctx ->
            if (ctx.provider.id == "fast") Result.failure(oops(ctx, ErrorClass.TRANSIENT, 503))
            else Result.success("from smart")
        }
        val r = router(
            settings(strategy = io.reyaak.router.score.RoutingStrategy.FASTEST),
            adapter,
        )
        val response = r.complete(ask())
        assertEquals("from smart", response.message.content)
        assertEquals(listOf("fast/quick-8b", "smart/big-70b"), adapter.seen)
        assertEquals(2, response.served.attempts)
    }

    @Test
    fun `a fatal error stops the chain instead of burning every candidate`() = runTest {
        val adapter = FakeAdapter { ctx -> Result.failure(oops(ctx, ErrorClass.FATAL, 400)) }
        val r = router(settings(), adapter)
        val error = runCatching { r.complete(ask()) }.exceptionOrNull()
        assertTrue(error is RouterException)
        assertEquals(1, adapter.seen.size)
    }

    @Test
    fun `an invalid key produces an actionable message, not a stack trace`() = runTest {
        val adapter = FakeAdapter { ctx -> Result.failure(oops(ctx, ErrorClass.KEY_INVALID, 401)) }
        val r = router(settings(), adapter)
        val error = runCatching { r.complete(ask()) }.exceptionOrNull() as RouterException
        assertTrue(error.message!!.contains("Router screen"))
        assertEquals(ErrorClass.KEY_INVALID, error.lastErrorClass)
    }

    @Test
    fun `with no keys at all the error says so plainly`() = runTest {
        val r = router(settings(keys = emptyList()), FakeAdapter { Result.success("ok") })
        val error = runCatching { r.complete(ask()) }.exceptionOrNull() as RouterException
        assertTrue(error.message!!.contains("no provider key has been added yet"))
    }

    @Test
    fun `a locally rate limited candidate is skipped without a network call`() = runTest {
        val limited = fastModel.copy(rpmLimit = 1)
        val limiter = RateLimiter(now = { 1_000_000L })
        // Spend the single allowed request.
        limiter.record(limited, "k1", tokens = 10)

        val adapter = FakeAdapter { ctx ->
            if (ctx.provider.id == "fast") Result.success("should not happen")
            else Result.success("from smart")
        }
        val r = router(
            settings(
                models = listOf(limited, smartModel),
                strategy = io.reyaak.router.score.RoutingStrategy.FASTEST,
            ),
            adapter,
            limiter = limiter,
        )
        val response = r.complete(ask())
        assertEquals("from smart", response.message.content)
        // This is the bug the fix was for: the skipped candidate must never be
        // handed to the adapter.
        assertEquals(listOf("smart/big-70b"), adapter.seen)
    }

    // ── Streaming and the commit rule ───────────────────────────────────────

    @Test
    fun `streaming falls over freely before the first token`() = runTest {
        val adapter = FakeAdapter { ctx ->
            if (ctx.provider.id == "fast") Result.failure(oops(ctx, ErrorClass.TRANSIENT, 500))
            else Result.success("streamed reply")
        }
        val r = router(
            settings(strategy = io.reyaak.router.score.RoutingStrategy.FASTEST),
            adapter,
        )
        val events = r.stream(ask()).toList()
        assertEquals("streamed reply", events.filterIsInstance<StreamEvent.Text>().joinToString("") { it.delta })
        assertTrue(events.last() is StreamEvent.Done)
    }

    @Test
    fun `after the first token the attempt is committed and does not silently switch`() = runTest {
        // Splicing a second model's output onto a partial reply would produce
        // one answer written by two models, so a late failure must surface.
        val r = router(settings(), FailsMidStream())
        val collected = mutableListOf<StreamEvent>()
        val error = runCatching { r.stream(ask()).collect { collected += it } }.exceptionOrNull()

        assertTrue("expected a RouterException, got $error", error is RouterException)
        assertTrue((error as RouterException).message!!.contains("cut off"))
        // The partial text already delivered is not retracted.
        assertEquals("partial answer", collected.filterIsInstance<StreamEvent.Text>().joinToString("") { it.delta })
    }

    // ── Token estimation ────────────────────────────────────────────────────

    @Test
    fun `token estimate grows with content and counts tool schemas`() = runTest {
        val small = Router.estimateTokens(ask("hi"))
        val large = Router.estimateTokens(ask("x".repeat(4000)))
        assertTrue(large > small + 900)

        val withTool = Router.estimateTokens(
            ask("hi", listOf(ToolDefinition("t", "d".repeat(400), "p".repeat(400))))
        )
        assertTrue("tool schemas must count toward the estimate", withTool > small + 100)
    }
}
