# CLAUDE.md

Working notes for agents on this repository. Read `ARCHITECTURE.md` for the
full design; this file is the short version plus the things that are easy to get
wrong.

## What this is

An on-device autonomous agent for Android, Kotlin Multiplatform, with its own
multi-provider LLM router underneath. Everything runs locally: conversations and
memory in Room, provider keys in a hardware-backed Keystore, no telemetry.

## Modules

    :router   provider catalogues, scoring, health, fallback     jvm + android + ios
    :core     agent loop, chat engine, memory, skills, tools     android + ios
    :ui       every Compose screen                               android + ios
    :app      the Android host                                   the only OS-facing module

The dependency direction is the architecture made structural. `:ui` cannot reach
a provider because `:router` is two modules below it. `ChatEngine` holds an
`LLMClient`, and only `LLMClient` holds the `Router`, so "the agent contains no
provider-specific logic" is a property of the type graph rather than a habit.

**`:ui` has no `androidMain` and no `iosMain`, deliberately.** Anything only an
OS can provide arrives from the host: opening a URL and picking a file as
lambdas, fonts and the launcher mark as composition locals (`LocalFonts`,
`LocalBrand`). Do not add a platform source set to `:ui` to solve this; pass it
in from `:app` the way everything else is passed in.

## Design provenance, and how to talk about it

Reyaak's agent architecture follows **Hermes Agent**
(<https://github.com/NousResearch/hermes-agent>, MIT, © 2025 Nous Research).
Hermes itself is Python 3.11 plus Node and cannot run inside an Android APK, so
none of its code is here: what was taken is the design, reimplemented in Kotlin.
`NOTICE` records the specific debts and is the correct and sufficient place for
them.

Two rules follow, and they are not in tension:

- **The product never mentions it.** No "Hermes" string in any screen, package
  name, class name, notification, system prompt, or commit message. Reyaak is
  the product name everywhere a user or a reader of the git log can see.
- **`NOTICE` always does.** MIT requires the copyright notice to travel with
  substantial portions of the work, and the ideas credit is owed regardless.
  Never delete or weaken those entries to make the lineage less visible.

If a future change ever copies Hermes source rather than reimplementing it, two
things must happen in the same commit: the MIT licence text ships with the
build, and the sentence in `NOTICE` claiming "no source code from them appears
in this repository" is corrected. Leaving that sentence in place while shipping
copied code is a false statement in the repo, which is worse than silence.

## Memory and skills

The split is load-bearing rather than tidy:

- **Memory is facts.** `MemoryStore`, Room-backed, retrieved when relevant. The
  user model (`MemoryKind.PROFILE`) goes into every prompt; facts
  (`MemoryKind.FACT`) are fetched by the `memory_search` tool.
- **Skills are procedures.** `SkillStore`, plain text the user switches on,
  which then applies to every turn.

Merging them would mean either loading every fact into every prompt or letting a
procedure be forgotten for being unused.

### The curation invariants

An agent that edits its own memory can lose the user's data, so these are
requirements, locked down by `MemoryStoreTest`. Do not relax one without
deleting the test that asserts it and saying why:

1. **Never touch what the user wrote.** `agentCreated = false` is out of reach
   of the curator. Editing a memory transfers ownership to the user, which is
   why `edit()` uses `updateContentAsUser` and `remember()`'s deduplicating
   update does not.
2. **Never auto-delete.** Curation archives, and archiving is reversible.
   `forget()` is the only hard delete and is reachable only from an explicit
   user action.
3. **Pinned bypasses everything automatic.**
4. **The expensive pass stays behind the cheap one.** `archiveStale()` is a SQL
   predicate: deterministic, free, safe to run unattended. Asking a model which
   memories are worth keeping costs a turn and can be wrong, so it must stay an
   explicit action, never something that happens while nobody is looking.

Maintenance is triggered by **inactivity**, in `AgentService.runLoop`, not by a
scheduler. No WorkManager: it would add a second execution path and could fire
mid-sentence, and a quiet stretch is the better signal anyway.

### Tool side effects

`AgentTool.effectful` defaults to **true**, and that direction is the point. A
tool added later, or supplied by a host this module has never seen, is assumed
to have effects until someone says otherwise, so a new tool is safe by default
rather than dangerous by default. `readOnlyDefinitions()` is what the unattended
path is allowed to see. Only `memory_search` is currently non-effectful:
anything that reaches the network or a mailbox counts as effectful even when it
only reads, because unattended use would leak data.

## Conventions that will bite you

- **Monospace is semantic.** `LocalFonts.current.mono` marks machine truth: ids,
  scores, latencies, counts, masked keys. Prose is never monospace. The design
  depends on this split; do not use mono for emphasis.
- **Phosphor is subsetted.** `app/src/main/res/font/phosphor.ttf` contains only
  the glyphs in `Ph`. Adding a constant without re-subsetting renders a blank
  box. Rebuild with `fontTools.subset` over the codepoints in `Ph`, and keep the
  fill cut in step when a glyph is used with `fill = true`.
- **Surfaces are alphas, not colours.** `ReyaakTokens.g1`/`g2`/`g3` over one
  background, each with a `line` hairline. Dropping the hairline is what makes
  the glass look flat. Material's `ColorScheme` is still populated, but only for
  the stock components that survive.
- **`NavBarSpace` is the bar's own height.** Callers add the system navigation
  inset themselves. The bar draws over content rather than displacing it.
- **No `fallbackToDestructiveMigration`, anywhere.** The database holds the
  user's conversations and the agent's memory. Schema changes are additive with
  an `AutoMigration` and an exported schema under `core/schemas`.

## Commands

    ./gradlew :core:jvmTest :router:jvmTest    # the unit tests that matter
    ./gradlew :ui:compileAndroidMain           # type-check the screens
    ./gradlew :app:assembleRelease             # signed APK, needs keystore.properties

`:ui:compileKotlinMetadata` is **skipped** in this project and proves nothing.
Use `:ui:compileAndroidMain` to type-check `:ui`.

Signing reads `keystore.properties` and `reyaak-release.jks`, both gitignored.
Absent, the release build goes unsigned rather than failing, so a fresh clone
still compiles.
