# Reyaak — Architecture Plan

A self-learning autonomous AI agent that lives on Android, with its own lightweight
multi-provider LLM router.

This document is the plan the implementation follows. It records **decisions and the
reasons for them**, so a decision can be revisited without re-deriving it. It is
written after reading the reference projects, not before.

---

## 0. Verified environment facts

These were measured on the build machine, not assumed. They constrain everything below.

| Fact | Value | Consequence |
|---|---|---|
| JDK | Temurin 21.0.12 | AGP 9 requires 17+; we target JVM 17 bytecode |
| Android SDK platforms installed | `android-34`, `android-37.0` | `compileSdk = 37`; **no** `android-36` |
| Build tools | 34.0.0, 36.0.0 | AGP resolves what it needs |
| Gradle | 9.7.1 (wrapper) | AGP 9.3.2 needs ≥ 9.4 (`ProjectTypeBinding`) |
| AGP | 9.3.2 (latest stable) | **Built-in Kotlin** — see below |
| Kotlin | 2.3.21 | AGP 9.3.2 requires KGP ≥ 2.2.10; 2.3.21 satisfies it |
| KSP | 2.3.11 | KSP `2.3.x` tracks Kotlin `2.3.x` — aligned, so Room works |

### Toolchain decisions worth knowing

**AGP 9 removed the `org.jetbrains.kotlin.android` plugin.** Kotlin compilation is
built in. Applying that plugin is now a hard error. Consequences:
- Android modules apply only `com.android.library` / `com.android.application`,
  plus *compiler* plugins (`plugin.serialization`, `plugin.compose`) and `ksp`.
- `compilerOptions` moved **inside** `android { kotlin { … } }`. The top-level
  `kotlin { }` block is not compatible with built-in Kotlin in the same module.
- `:router` is a pure-JVM module, so it still uses `org.jetbrains.kotlin.jvm`
  normally.

**`targetSdk = 34` is deliberate, and it is the single most consequential choice
in the whole project.** Reyaak's purpose is a long-lived background agent. From
API 35 onward Android enforces a 6-hour-per-24 cap on `dataSync` foreground
services and tightens boot-completed FGS starts. API 34 is the most permissive
regime still supported. `compileSdk` stays at 37 so new APIs remain visible.
This is a corner with a known ceiling: shipping on Google Play would require
raising `targetSdk` and adding WorkManager-chunked execution. It is marked with
a `ponytail:` comment in `app/build.gradle.kts` so it is not lost.

**Room is kept, not replaced with raw SQLite.** The self-learning engine versions
skills and will churn the schema; Room's migrations and compile-checked queries
earn their keep. This was contingent on KSP alignment, which was verified before
committing.

---

## 1. Reference study

Six repositories were cloned and read. **What each one actually is** matters more
than what its name suggests — three of the four "Hermes Mobile" projects are not
native Android at all.

| Repo | What it is | License | Verdict |
|---|---|---|---|
| `NousResearch/hermes-agent` | Python agent, ~4,600 `.py`. The canonical Hermes. | MIT © 2025 Nous Research | Design reference for Core |
| `tashfeenahmed/freellmapi` | TypeScript LLM gateway, ~506 `.ts`. 30+ providers. | MIT © 2026 Tashfeen Ahmed | **Design reference for Router** |
| `sesaloy/hermes-mobile` | Capacitor/WebView wrapper + bundled Python | MIT © Nous Research + contributors | Android packaging notes only |
| `plcunha/Hermes-Mobile` | Python/Kivy + buildozer | MIT © Nous Research + contributors | Mobile-constraint notes only |
| `sinonchum/hermes-mobile` | Flutter + 3 `.kt` files | **NO LICENSE FILE** | **Ideas only — no code reuse** |
| `Mohamedhasbini/hermes` | Tiny Python web service, 29 `.py` | MIT © 2026 Mohamad | Negligible |

`hermes-agent` carries **no trademark or naming clause** — plain MIT. The
obligation is attribution only. Reyaak is nevertheless an independent
implementation in Kotlin, not a rename: nothing is copied verbatim, and the
`NOTICE` file records the algorithmic debts honestly.

`sesaloy` bundles an ARM64 ADB binary derived from LADB, whose license forbids
unofficial Play Store builds. **Reyaak does not go near this** — no ADB, no
bundled binaries.

### 1.1 What freellmapi gets right (and Reyaak takes)

Its `services/scoring.ts` is the best thing in either reference. It replaced a
pile of hand-tuned incompatible bonuses with a principled model:

```
base      = w_rel·reliability + w_speed·speed + w_intel·intelligence   ∈ [0,1]
effective = base × headroomFactor × rateLimitFactor
```

Every signal is normalized to `[0,1]` and combined as a **convex combination**, so
the weights are interpretable and the base can never escape `[0,1]`. Two
always-on **multiplicative guardrails** then pull a candidate down as it becomes
dangerous, without reordering healthy candidates against each other.

The pieces Reyaak ports, essentially verbatim (they are small, and correct):

- **Reliability via Thompson sampling.** `Beta(α, β)` posterior with
  `α = successes + 1`, `β = failures + 1` (uniform prior — an unseen model is
  genuinely uncertain, not assumed good or bad). Sampling the posterior rather
  than taking its mean makes exploration automatic and proportional to
  uncertainty, so a model is never permanently frozen out after two failures.
  Beta is drawn from two Gamma draws (Marsaglia & Tsang).
- **Speed as a saturating curve**, not a global-max normalization:
  `throughput = 1 − exp(−tokPerSec / 60)`, weight 0.6; TTFB a linear ramp from
  300 ms (full credit) to 5000 ms (zero), weight 0.4. Unmeasured models get an
  optimistic prior of 0.6 so they still get explored. A *saturating* curve is the
  point — otherwise one very fast tiny model makes every reasonable larger model
  look broken.
- **Timeouts count against speed.** A timeout is recorded as its wall-clock
  latency with zero output tokens, capped at 120 s. Without this a model that
  hangs on half its calls keeps a pristine speed score, because only successful
  rows feed the speed axis.
- **Intelligence as tier-then-rank**: `tier×1000 − sqrt(rank)×31`, min-max
  normalized across the candidate set. The `sqrt` compression is what makes a
  rank edit actually visible; strict tier dominance is preserved because the
  worst in-tier rank (`sqrt(1000)×31 ≈ 980 < 1000`) still beats the best rank of
  the tier below. An unknown tier scores at the **floor, not excluded** —
  "unknown" is no opinion, not "worst".
- **Headroom guardrail**: a shared ramp, flat at 1.0 while ≥ 20 % of quota
  remains, then linear down to a 0.1 floor at exhaustion. Applied to both the
  monthly token budget and the live rate-window utilization. Recovery needs no
  bookkeeping — the windows slide, so utilization falls on its own.
- **Rate-limit guardrail**: `1 − (penalty/10) × 0.6`. At max penalty a model
  keeps 40 % of its score — demoted hard, never excluded, so it can recover.
- **Presets as weight vectors**: `balanced` (.5/.25/.25), `smartest`
  (.35/.1/.55), `fastest` (.35/.55/.1), `reliable` (.7/.15/.15). One engine, four
  presets, plus a manual `priority` chain.

Its `lib/error-classify.ts` and `providers/base.ts` contribute a **battle-tested
error taxonomy** — the kind of thing only production traffic teaches:

- Retryable / fail-over-able: `408, 409, 410, 422, 429, ≥500`. Fatal: `400, 401`.
- `403` is *not* fatal — the key is valid but this model is off its tier. Fail
  over and remember.
- `413` and context-length errors are fail-over-able: **another candidate may
  have a larger window.** If every candidate rejects it, surface an honest 413.
- `404`/`410` mean the model was pulled upstream — bench the *model*, not the key.
- **Three-tier `Retry-After` parsing**: the header (delta-seconds *or* HTTP-date)
  → a depth-capped DFS through the error body for `retryDelay`/`retry_after`
  (Gemini answers 429 with a `google.rpc.RetryInfo` of `"17s"`) → a prose regex
  anchored on an explicit retry phrase. All clamped to 24 h, because a malformed
  or hostile `Retry-After` would otherwise bench a key forever.

Resilience constants worth inheriting: sliding-window model breaker (3 failures
in 15 min → 10 min bench), auth-failure cooldown 5 min, a **45 s wall-clock
fallback budget** as the real stopping condition (not just a retry count), and
daily-quota resets computed to next UTC midnight.

### 1.2 The decisive structural fact about providers

Of the ~30 platforms freellmapi supports — Groq, Cerebras, Mistral, NVIDIA,
OpenRouter, SiliconFlow, GitHub Models, Ollama, and many more — **nearly all are
OpenAI-compatible.** They differ only in base URL, auth header, and small quirks.
Its own tree reflects this: one `openai-compat.ts` (459 lines) covers dozens,
while `google.ts` (876 lines) is the outlier that needs real translation.

**Reyaak therefore ships one generic adapter plus one Gemini adapter, not N
adapters.** Adding a provider becomes a table row, not a class. This is the
single biggest simplification available, and it is not a compromise — it is what
the reference implementation's own file sizes already tell you.

`services/quirks.ts` turned out to be *advisory documentation* for a dashboard
(title/body/severity), not behavioural switches. Reyaak does not port it;
behavioural deviations become boolean flags on the model record.

### 1.3 What Reyaak deliberately drops

Dropped because it is a *server* concern with no meaning on one phone:
multi-tenancy, client auth to the router, Redis, Docker, the web dashboard,
horizontal scale, request-retention policy, backups-as-a-service.

Dropped because the value does not justify the cost on a battery:
- **The periodic health prober.** freellmapi probes every key every ~5 min. On a
  phone that is a recurring radio wake for no user benefit. Reyaak learns health
  **passively** from real request outcomes, and probes a benched key **lazily**,
  only when its cooldown has expired and it is next needed.
- **Time-of-day peak reweighting** — an operator feature, off by default upstream.
- **Community-sourced reliability priors** — needs a shared backend.
- **Observed-speed-rank projection** — exists to drive a dashboard sort.

### 1.4 What hermes-agent contributes — and what it does not

The Memory/Skills split the spec demands is real there, and worth copying:

- **Memory = facts.** Markdown (`MEMORY.md`, `USER.md`), chunked into entries.
- **Skills = procedures.** A directory per skill with a `SKILL.md` carrying YAML
  frontmatter: `name`, `description`, `version`, `author`, `license`,
  `platforms`, `metadata.hermes.tags`, `related_skills`. Plus runtime state:
  `use_count`, `state` (active/stale/archived), `created_by`, `pinned`, `source`
  (base vs agent-created).

Two mechanisms are worth taking almost as-is:

1. **Skill authoring has no distillation engine.** `/learn` builds *one prompt*
   that instructs the live agent to author the skill with the tools it already
   has. No extra model, no extra pipeline. Reyaak does the same.
2. **The curator's safety invariants** are exactly what the spec's "never allow
   uncontrolled modification" needs, and they are stated as hard rules:
   - only ever touches **agent-created** skills;
   - **never auto-deletes — only archives**, and archive is recoverable;
   - **pinned skills bypass every automatic transition**;
   - runs on an auxiliary client, never disturbing the main session.
   Its scheduling is **inactivity-triggered, not a cron daemon** — which is
   precisely right for a phone.
3. **The expensive pass is opt-in.** By default the curator does only the
   *deterministic* inactivity prune; the LLM consolidation pass is off unless
   asked for. Reyaak keeps this default.

Also worth porting: tools are classified by **side-effect capability**, with
read-only tools listed explicitly and **unknown/MCP tools treated as
effect-capable by default** — a default-deny posture that matters once the agent
can write.

**What hermes-agent does *not* have: autonomous learning from task outcomes.**
`/learn` is user-initiated. There is no loop that watches a task fail and
improves a skill by itself. Its `learning_graph.py` is a *visualization*, and
`learning_mutations.py` is *user-initiated* edit/delete. So the spec's
`Task → Execute → Evaluate → Learn` arc is **genuinely new work**, not a port.
This is the part of Reyaak with no reference implementation to lean on, and it is
where the design risk actually lives.

Finally, a warning: `conversation_loop.py` is **8,609 lines**. Whatever else
Reyaak copies, it must not copy that. The essential loop is perhaps 200 lines;
the rest is accumulated enterprise surface.

---

## 2. Non-negotiable boundaries

These come from the brief and are enforced structurally, not by convention.

1. **The agent contains no provider-specific logic.** The only path is
   `Core → LLMClient → Router → provider`. `:core` cannot even see a provider
   type: the router exposes one `complete(request)` surface.
2. **FreeLLMAPI is not embedded.** No vendored TypeScript, no bundled Node, no
   sidecar process. It is a design reference; Reyaak's router is original Kotlin.
3. **Memory and Skills stay separate.** Facts and procedures are different
   tables, different lifecycles, different retrieval paths.
4. **Core code and security settings are never self-modifiable.** The
   self-learning engine may write *skill rows*. It has no tool that can reach
   app code, permissions, key storage, or the tool allow-list.
5. **UI, core, and router are independent modules** with a one-way dependency
   arrow.

---

## 3. Module architecture

```
:app     Android application  — Compose UI, foreground service, scheduler,
         notifications, Keystore. Knows about Core. Never about providers.
   │
   ▼
:core    Android library — agent loop, context builder, memory, skills,
         self-learning, tool registry, task manager, subagents. Room lives here.
   │
   ▼
:router  Pure JVM library — model routing, provider health, retry/fallback,
         rate limiting, capability matching, token accounting, streaming,
         tool calling, OpenAI-compatible surface.
```

`:router` is deliberately **pure JVM with no Android SDK on its classpath**. Two
payoffs: the routing logic is unit-testable with plain JUnit (no emulator, no
Robolectric — the scoring model is exactly the kind of thing that needs fast
property tests), and Android concerns are *structurally* prevented from leaking
into it. Secrets arrive through an interface the app implements over Keystore;
the router never touches Android storage.

---

## 4. Reyaak Router

### 4.1 Shape

```
LLMClient (in :core, the only caller)
   │  ChatRequest { messages, tools, capabilities needed, budget }
   ▼
Router
   ├── Catalog        provider + model table, capabilities, limits, pricing
   ├── Selector       candidate filter → score → rank  (§4.2)
   ├── HealthStore    passive outcomes, cooldowns, breakers  (§4.4)
   ├── RateLimiter    token bucket per (provider, key)  (§4.5)
   ├── FallbackLoop   attempt → classify → cooldown → next  (§4.3)
   ├── Adapters       OpenAiCompat  |  Gemini            (§4.6)
   └── UsageLedger    tokens, latency, cost, outcome per attempt
```

### 4.2 Selection

1. **Filter** the catalog to candidates that can serve the request at all:
   required capabilities (tools / vision / JSON mode), context window ≥ estimated
   prompt tokens, provider not benched, key present and not benched.
2. **Score** each candidate: `combineScore` from §1.1 — convex base × headroom ×
   rate-limit factor.
3. **Rank** and take the ordered list. That list *is* the fallback chain — the
   loop walks it. There is no separate "fallback config".

Token estimation starts as `chars/4` with a per-family correction factor. A real
tokenizer on-device is a large dependency for a decision that only needs to be
approximately right; the *consequence* of being wrong is bounded because
context-length errors are fail-over-able (§1.1). Kept behind an interface so a
real tokenizer can be dropped in.

Capability matching is a bitmask on the model record, not string sniffing.

### 4.3 Fallback loop

Stopping conditions, in priority order: wall-clock budget (default 45 s), max
attempts (default 8 — lower than freellmapi's 20, because a phone user is
waiting), or chain exhausted.

Each failed attempt is classified (§1.1), which decides three things
independently: whether to retry at all, what to bench (key? model? provider?),
and for how long. A stated `Retry-After` always wins over the heuristic ladder.

Mid-stream failure is the interesting case. Once bytes have been emitted to the
caller, silently switching providers would splice two different models' outputs
into one answer. Reyaak's rule: **before first token, fail over freely; after
first token, the attempt is committed** — surface the error and let the caller
decide. This mirrors the reference's `'done' | 'committed'` dispatch outcome.

### 4.4 Health — passive, not probed

Per `(provider, key, model)`: decay-weighted success/failure counts feeding the
Beta posterior, EWMA throughput and TTFB, last error class, `cooldownUntil`.
Written on every real request outcome. Persisted so a process restart does not
amnesia the agent into hammering a dead provider.

Breaker: 3 failures in a 15-minute sliding window → 10-minute bench. Auth
failure → 5-minute bench plus a revalidation flag. Daily quota exhaustion →
bench until next UTC midnight.

### 4.5 Rate limiting

Client-side token bucket per `(provider, key)`, seeded from the catalog's known
rpm/tpm/rpd/tpd for that free tier, so Reyaak declines locally instead of
spending a round trip to earn a 429. Window utilization also feeds the headroom
guardrail, so a model at 82 % of its daily cap is *demoted* before it is
*exhausted* — the whole point of the guardrail.

### 4.6 Adapters

```kotlin
interface ProviderAdapter {
    suspend fun complete(req: ProviderRequest): ProviderResponse
    fun stream(req: ProviderRequest): Flow<StreamEvent>
}
```

Two implementations: `OpenAiCompatAdapter` (base URL + auth header + flags from
the catalog row) and `GeminiAdapter` (translates both directions). Streaming is
normalized to one `StreamEvent` type; tool-call deltas are accumulated by index
into complete calls before they reach `:core`.

A provider that cannot do native tool calling is simply **not a candidate** when
the request needs tools. Prompt-based tool emulation is not implemented — it is a
reliability trap, and the catalog has plenty of tool-capable free models.

### 4.7 OpenAI-compatible surface

The brief asks for one, and it is nearly free: `:core` already speaks
OpenAI-shaped requests internally. Exposing it over a **loopback-only** HTTP
listener (opt-in, off by default, `127.0.0.1`, bearer token) lets other apps on
the device use Reyaak's router, and makes the router testable with `curl`. It is
a thin shell over the same code path, not a second implementation.

---

## 5. Reyaak Core

**Agent loop** — one iteration: build context → call model via `LLMClient` →
if tool calls, dispatch and append results, iterate → else finish. Guards:
max iterations, wall-clock deadline, cooperative cancellation. Target ~200 lines.

**Context builder** — assembles sections in a fixed order with an explicit token
budget each: system prompt → user profile → activated skills → retrieved memory
→ task state → conversation tail. Sections are trimmed by priority, tail-first,
so the loop degrades instead of failing when the budget binds.

**Compaction** — when history exceeds a fraction of the window, summarize the
oldest span into one synthetic message and keep the tail verbatim. Trigger and
ratio are constants, not heuristics-on-heuristics.

**Memory (facts)** — Room table, chunked entries with source and timestamp.
Retrieval is keyword + recency to start; the embedding path stays behind the
interface. Written by an explicit model-decided tool call, so what is remembered
is inspectable.

**Skills (procedures)** — Room table mirroring the SKILL.md model: name,
description, body, version, state, source, pinned, useCount, lastUsedAt,
createdBy. Loaded on demand by description match, not eagerly — the whole point
of the ≤60-char description in the reference is that the index is cheap to keep
resident while bodies are not.

**Tool registry** — declared schema, permission class, and a side-effect flag
defaulting to *effect-capable*. Large results are truncated with a marker before
re-entering context.

**Task manager & subagents** — tasks are rows with state, schedule, and result;
subagents get a fresh context, an explicit tool subset, and a concurrency cap of
1 on mobile.

---

## 6. Self-Learning Engine

The spec's loop, mapped to concrete mechanisms — and honest about which parts
have no reference implementation:

| Stage | Mechanism | Ported? |
|---|---|---|
| Execute | agent loop records a run row per task | — |
| **Evaluate** | outcome classifier over run rows | **new work** |
| **Learn** | prompt the agent to author/patch a skill | ported (`/learn`) |
| Create/Improve | `skill_manage`-equivalent tool writes a skill row | ported |
| Test | dry-run the skill against the recorded failing case | **new work** |
| Version | `version` bump + prior body retained | ported |
| Activate | `state = active` | ported |
| Monitor | `useCount`, success rate after activation | ported |
| Rollback | restore prior version; archive, never delete | ported |

Triggers, all cheap and deterministic: repeated failure of the same task shape,
a user correction, a repeated tool error, or a workflow seen N times. Evaluation
itself runs **on device idle + charging**, never mid-conversation.

Safety invariants, inherited and non-negotiable:
- touches **agent-created skills only**;
- **archives, never deletes**;
- **pinned skills are untouchable**;
- the deterministic pass is the default; the LLM consolidation pass is opt-in;
- no tool in the learning path can reach app code, permissions, or key storage.

---

## 7. Android runtime

- **Foreground service** (`dataSync`) hosts the agent loop and owns no agent
  logic. `START_STICKY`, notification shows current activity.
- **Notifications**: one low-importance ongoing channel for state; separate
  channel for results needing attention. `POST_NOTIFICATIONS` requested at
  first run.
- **Scheduler**: WorkManager for periodic/deferrable work (it survives reboot
  and respects Doze); the foreground service for the active loop. `AlarmManager`
  only if an exact wall-clock trigger is ever genuinely needed.
- **Boot**: `BootReceiver` re-arms schedules on `BOOT_COMPLETED` and
  `MY_PACKAGE_REPLACED`.
- **Battery**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is **opt-in and
  explained**, never silently requested. Learning work is gated on
  idle + charging.
- **Secure storage**: provider API keys in Android Keystore (`security-crypto`
  for the envelope). `allowBackup=false`, and data-extraction rules exclude
  every domain — keys and memory do not leave the device.
- **Process death**: all agent state is in Room, so a killed process resumes
  from rows, not memory.

---

## 8. Build order

Matches the brief; each phase ends with something runnable.

1. **Android runtime + basic agent** — module skeleton, foreground service,
   notification, Compose shell, single-turn agent against one hardcoded
   provider. *Proves the APK installs and the loop runs.*
2. **Memory + Skills + Tools** — Room schema, tool registry, memory and skill
   tools, retrieval, context builder with budgets.
3. **Self-learning** — run rows, outcome classifier, skill authoring/patching,
   versioning, archive/rollback, curator pass on idle+charging.
4. **Native Reyaak Router** — catalog, scoring model, health, rate limiting,
   fallback loop, both adapters, usage ledger. Core switches to `LLMClient`.
5. **Scheduler + Subagents + MCP** — WorkManager schedules, task manager,
   subagent spawning, MCP client.
6. **External APIs and integrations** — plus the loopback OpenAI-compatible
   listener.

**Phase 1 is complete.** The three-module build produces a 12.6 MB debug APK
containing: the adaptive launcher icon (generated from `art/icon-source.png` by
`tools/make_icons.py`, with monochrome and notification variants at five
densities), a `dataSync` foreground service that starts, ticks, and stops under
user control, the `POST_NOTIFICATIONS` request flow, an explained opt-in for
battery-optimisation exemption, boot/update restore gated on whether the user
had left the agent running, and a Compose UI themed from the icon palette.
27 unit tests pass (23 router, 4 app).

Not yet verified on hardware: no device or emulator is attached to the build
machine, so the service lifecycle has been verified by inspection and unit test
only, not by running it. The heartbeat in `AgentService.runLoop` is a
placeholder that the real agent loop replaces in phase 2 without changing the
service around it.

---

## 9. Security model

| Boundary | Rule |
|---|---|
| Provider keys | Keystore only. Never logged, never in `SharedPreferences`, never in backups. |
| Self-modification | Skills are data. Code, permissions, and the tool allow-list are not reachable by any agent tool. |
| Tool side effects | Unknown and MCP tools are effect-capable by default; read-only tools are explicitly listed. |
| Loopback API | Off by default. Bound to `127.0.0.1`, bearer token required. |
| Network | Only provider endpoints from the catalog. No telemetry, no phone-home. |
| Logs | Error bodies are redacted before persisting; only a parsed retry delay is kept from an error payload, never the payload. |

---

## 10. Attribution

`NOTICE` records the MIT licenses of the four reference projects whose designs
informed this implementation, naming what was drawn from each. Reyaak is an
independent Kotlin implementation, not a fork or a rename. No code was copied
from `sinonchum/hermes-mobile`, which carries no license.
