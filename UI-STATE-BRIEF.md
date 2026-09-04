# Reyaak: UI and UX state brief

Hand-off for visual design. Every state below was read out of the source. Copy is verbatim.

---

## 1. What Reyaak is

An on-device autonomous AI agent for Android, built with Kotlin Multiplatform and Compose Material 3. It runs an agent loop as a foreground service and routes every turn through its own multi-provider LLM router, scoring free-tier models on reliability, speed, and intelligence, benching whatever breaks, and falling through to the next candidate.

Everything is local: conversations in a Room database, provider keys encrypted with a hardware-backed Keystore key, no telemetry.

**Design register:** the product is *autonomy you can audit*. Provenance, health, scores, and run state are shown rather than hidden. The design should feel like an instrument panel that happens to be beautiful, not a chat app with a settings screen. Trust and legibility beat decoration.

---

## 2. Design system as it exists today

Palette sampled from the launcher icon. Dynamic color is deliberately **off** so the wallpaper cannot repaint the app. Dark is the default (`darkTheme` pref defaults to `true`).

### Dark scheme

| Role | Hex | Notes |
|---|---|---|
| `primary` | `#B49BF5` | violet, the accent |
| `onPrimary` | `#080A1B` | |
| `primaryContainer` | `#1A1F38` | user bubbles |
| `onPrimaryContainer` | `#B49BF5` | |
| `secondary` | `#6FA8E8` | blue, currently near-unused |
| `background` | `#080A1B` | navy, the app ground |
| `onBackground` | `#E6E9F5` | mist |
| `surface` | `#11152A` | cards |
| `onSurface` | `#E6E9F5` | |
| `surfaceVariant` | `#1A1F38` | advisories, inactive strip, active history row |
| `onSurfaceVariant` | `#9AA2C0` | all secondary and caption text |
| `outline` | `#2E3552` | dividers, inactive dots, progress tracks |
| `error` | `#E9899B` | |

### Light scheme

| Role | Hex |
|---|---|
| `primary` | `#5B4BC4` |
| `onPrimary` | `#FFFFFF` |
| `primaryContainer` | `#E7E2FB` |
| `onPrimaryContainer` | `#5B4BC4` |
| `secondary` | `#1F6BB0` |
| `background` | `#F7F7FC` |
| `onBackground` | `#14162B` |
| `surface` | `#FFFFFF` |
| `onSurface` | `#14162B` |
| `surfaceVariant` | `#ECEDF6` |
| `onSurfaceVariant` | `#585E7A` |
| `outline` | `#C9CCDE` |
| `error` | `#A3324A` |

### Type

Stock Material 3 type scale, no custom font. Styles in use: `headlineSmall`, `titleMedium`, `titleSmall`, `bodyLarge`, `bodyMedium`, `bodySmall`, `labelLarge`, `labelMedium`, `labelSmall`. `FontWeight.SemiBold` on card and row titles, `Bold` on the top-bar title and About header.

**Monospace is semantic, not decorative.** `FontFamily.Monospace` marks machine truth: tool ids, model ids, platform names, scores, latencies, token counts, version strings, masked key suffixes, section titles, agent activity, and CLI mode. Prose is never monospace. Preserve this split.

### Shape and rhythm

- Corner radii in use: `8` (CLI composer), `12` (advisory, text fields, notice, playground result), `14` (most cards), `16` (agent status card, bubbles), `20` (chat composer), `26`/`32` (nav pill / nav bar), `CircleShape` (status dots, help toggle).
- Screen padding `16dp`. Card interior `16dp`, agent status card `20dp`. Vertical gaps between cards `10–12dp`.
- `NavBarSpace = 88.dp`: the floating bar draws **over** content, so every scrolling screen reserves this at the bottom itself.

### Honest assessment

The color palette and the monospace-as-truth convention are real identity. Everything else is stock Material 3: default type scale, default `Card`/`Switch`/`OutlinedTextField`/`FilterChip`/`AlertDialog`. There are no icons at all, glyphs are text characters (`◆ ⇄ ◉ ⚙ ☰ ← + >_ ↑ ↓ ? × •`). Large room to invent: type, iconography, density, the status and readiness components, empty-state illustration.

---

## 3. Chrome

### Top bar

`TopAppBar`, container = `background` (flat, no elevation). Title is bold: the pushed page title, or `"Reyaak"` on Chat, or the tab label otherwise.

| Slot | State | Content |
|---|---|---|
| Nav icon | page pushed | `←` |
| Nav icon | Chat tab, no page | `☰` (opens History) |
| Nav icon | other tabs | empty |
| Actions | Chat tab only | `+` (new conversation), then `>_` / `◆` (CLI toggle) |

### Floating nav bar

Four tabs, in order: **Chat `◆`**, **Router `⇄`**, **Agent `◉`**, **Settings `⚙`**.

Bar: full width minus `16dp` horizontal, `64dp` tall, radius `32dp`, vertical gradient `surfaceVariant@0.92 → surface@0.86`, `1dp` gradient border `onSurface@0.14 → onSurface@0.04`, `navigationBarsPadding`. Compose has no backdrop blur, so this is faux glass.

Pill: `52dp` tall, radius `26dp`. Selected: background `primary@0.16`, tint animates to `primary`, glyph scales to `1.12`. Unselected: transparent, tint `onSurfaceVariant`.

**Bar is hidden entirely while the keyboard is open.**

### Navigation model

Four tabs, plus three pages pushed one level deep: **History**, **About**, **Playground**. No NavHost. System back pops a page. Switching tabs drops any pushed page. Arriving on Router re-samples health and quota.

Transitions: push slides in from the right (`tween 260`, offset `it/3`) with fade; pop reverses; tab-to-tab cross-fades.

Haptic `LongPress` fires on: nav tab select, agent start/stop, help toggle, send, new conversation, CLI toggle, history open, order move, playground run.

---

## 4. Screen specs

### 4.1 Chat (`◆`)

Layout, top to bottom: agent strip, then transcript **or** empty state (weight 1), then composer.

**Agent strip** (full-width tappable row, `16dp`/`10dp` padding, opens Agent tab)

| State | Trigger | Content | Visual |
|---|---|---|---|
| Running | `agentRunning` | `8dp` dot + `Agent: {activity}` (mono) + `Manage` | bg `surface`, dot `primary`, action `primary` |
| Stopped | `!agentRunning` | `8dp` dot + `Agent stopped` + `Start` | bg `surfaceVariant`, dot `outline` |

`activity` values from core: `Listening`, `Stopped`, plus per-turn strings.

**Empty state** (centered, `32dp` padding)

| State | Trigger | Title | Body | Action |
|---|---|---|---|---|
| No keys | `!hasKeys` | `Add a provider key to start` | `Reyaak routes across {N} providers, most of them with a free tier. Add a single key and the router does the rest.` (N counted from the catalog) | `Open Router` |
| Agent stopped | `!agentRunning` | `Start the agent to chat` | `The agent is what answers, so it has to be running. It keeps working while the app is in the background.` | `Open Agent` |
| Ready | else | `Ask Reyaak anything` | `The router picks a model for each turn and falls back if one is unavailable.` | none |

**Message bubble** (keyed list item, animates into place; gap `12dp`)

| State | Trigger | Visual |
|---|---|---|
| User | `fromUser` | `primaryContainer` / `onPrimaryContainer`, 86% width, right aligned, corners `16/16/16/4` |
| Assistant | else | `surface` / `onSurface`, full width, left aligned, corners `16/16/4/16` |
| Error | `error != null` | `errorContainer` / `onErrorContainer`; message appended as `bodySmall` |
| Streaming, empty | `streaming && content.isBlank()` | three animated dots + `routing…`, or `running {tool}…` when a tool is active |
| Streaming, partial | `streaming` with text | text grows in place, no indicator |
| Provenance footnote | `footnote != null` | below bubble, mono `labelSmall`: `{platform} · {model} · {ms}ms · {n} attempts · {n} tok` |

Typing dots: three `6dp` circles, `primary`, staggered `520ms` reverse tween with `140ms` offsets, rising `3dp` and fading `0.45 → 1.0`.

**CLI mode** (same transcript, gap `2dp`, all mono `bodySmall`)

- User line: `$ {text}` in `primary`
- Assistant line: plain in `onBackground`, or `routing…` while streaming
- Error line: `! {message}` in `error`
- Footnote line: `# {note}` in `onSurfaceVariant`

**Composer** (`Surface`, tonal elevation `3dp`, `imePadding`)

Field: `OutlinedTextField`, weight 1, max 6 lines, radius `20dp` (`8dp` in CLI), IME action Send.

| Placeholder | Trigger |
|---|---|
| `Start the agent to chat` | `!agentRunning` |
| `Add a provider key to chat` | `!hasKeys` |
| `reyaak $` | CLI mode |
| `Message` | ready |

Send button: `FilledIconButton` `48dp`, `primary`. Glyph `↑` when enabled, `…` when disabled. Springs from scale `0.86` to `1.0` the moment the draft is non-blank (`dampingRatio 0.45`, `stiffness 520`).

`canSend = agentRunning && hasKeys && !streaming`.

Auto-follow: the list scrolls to the newest item only if already within 3 items of the bottom.

---

### 4.2 Agent (`◉`)

Scrolling list: status card, start/stop button, Skills card, Tools card, footer note, then zero to two advisories.

**Status card** (radius `16dp`, padding `20dp`)

| State | Trigger | Content |
|---|---|---|
| Running | `state.running` | `10dp` `primary` dot + `Running` (`titleMedium` SemiBold) |
| Stopped | else | `10dp` `outline` dot + `Stopped` |

Help toggle top-right: `28dp` circle, `surfaceVariant`, glyph `?` → `×` when open.

Divider, then three `SpaceBetween` rows, label `onSurfaceVariant` / value mono `onSurface`: **Doing now** = `activity`; **Turns answered** = count; **Heartbeats** = count.

Help panel (expanded), three labelled explanations:
- `Doing now`: `What the agent is working on this second: listening, routing a turn, answering, or how long it has been idle.`
- `Turns answered`: `Chat turns the agent has completed since it started, successful or failed. This is the number that means work got done.`
- `Heartbeats`: `The loop checking in on a timer to prove Android has not frozen the process. Useful only for diagnosing that; it is not a measure of activity.`

**Start/stop button** (full width)

| State | Control | On tap |
|---|---|---|
| Stopped | filled `Start agent` | asks POST_NOTIFICATIONS first on API 33+ if not granted, then starts |
| Running | outlined `Stop agent` | stops immediately, no confirmation |

**Footer:** `The agent runs as a foreground service so Android keeps the process alive. It heartbeats, answers turns, and calls the tools above when a question needs them; scheduled work arrives in a later phase.`

**Advisories** (only when the condition holds; `surfaceVariant` card, radius `12dp`, text + text-button). Both conditions are re-read on `ON_RESUME`.

| Trigger | Text | Action |
|---|---|---|
| notifications off | `Notifications are off, so the agent will run without showing what it is doing.` | `Open notification settings` |
| battery not exempt | `Battery optimisation can pause the agent when the screen is off. Exempting Reyaak keeps it running on schedule.` | `Allow` |

---

### 4.3 Skills card (inside Agent)

Header `Skills` + subtitle:
- `None active. The agent answers as itself.` when zero enabled
- `{n} active, applied from your next message.` otherwise

`New` text button. Divider. One row per skill: name (`bodyMedium`), then summary or the first 70 chars of the instructions (`labelSmall`, max 2 lines), then `Edit`, then a `Switch`.

Footer: `Active skills are added to the prompt after the base rules and before your personalisation, so your own voice still wins.`

**Skill dialog** (title = skill name, or `New skill` when blank): fields `Name`, `One-line summary`, `Instructions` (4 to 10 lines). Built-in skills show `Built in. Deleting resets it to the shipped wording rather than removing it.` Buttons: `Save` (enabled only when name and instructions are both non-blank), `Delete` or `Reset` for built-ins, `Cancel`.

---

### 4.4 Tools card (inside Agent)

Header `Tools` + `The agent calls these itself, mid-answer, when a question needs something it does not already know.`

**Tool row** (per registered tool): label, then the tool id in mono `labelSmall`, then a three-state readiness line, then a `Switch`.

| Readiness | Trigger | Copy |
|---|---|---|
| Active | usable | `Active` |
| Off | `!enabled` | `Off` |
| Blocked | on but not usable | `On, but not connected yet` |

All three currently render in the same `onSurfaceVariant`. **This is the single most important thing to fix visually:** readiness is the whole "why isn't this working" answer and it is styled as throwaway caption text.

**fastCRW backend section**

| State | Copy |
|---|---|
| Key set | `Hosted API, key stored` |
| Self-hosted | `Self-hosted, no key needed` |
| Unset | `Not set. Tools use the built-in backend, which needs no key but does not render JavaScript.` |

`Configure` / `Hide` toggles an animated section: `fastCRW API key` field, `Self-hosted URL (optional)` field with placeholder `http://192.168.1.10:3000`, `Save` (enabled only when a draft differs from stored), `Get a key`, and the note `Stored encrypted with the same hardware-backed key as your provider keys. A self-hosted crw needs no key at all.`

**Gmail section** (only if the tool is registered)

| State | Copy | Control |
|---|---|---|
| Not connected | `Not connected. Sign in to let the agent read your mail. Read-only: it can never send or delete.` | `Sign in` |
| Signing in | (unchanged) | `Signing in…`, disabled |
| Error | `Sign-in did not complete.` in `error` color | `Sign in` |
| Connected | the account email | `Disconnect` |

**Mailbox (IMAP) section** (only if registered)

| State | Copy |
|---|---|
| Unset | `Not set. An address and an app password reach Outlook, Yahoo, Zoho, Fastmail, a work server, or Gmail, with nothing to register.` |
| Bridge-only provider | `That provider only serves IMAP through a desktop bridge, which a phone cannot reach.` in `error` color |
| Configured | `{user} via {server}` |

Expanded form: `Email address` (placeholder `you@example.com`), `App password` (masked), `IMAP server (optional)` whose placeholder is a **live guess from whatever has been typed so far**, falling back to `imap.example.com`. `Save` is dirty-gated. Note: `Port 993, TLS. Stored encrypted with the same hardware-backed key as your provider keys. Most providers want an app password rather than your account password: make one in their security settings.`

Saving credentials auto-enables the tool, so there is no configured-but-off state.

---

### 4.5 Router (`⇄`)

The densest screen. Scrolling list inside a `Box` with two overlays.

Section titles are uppercase mono `labelMedium` in `primary`.

**Routing strategy:** `FilterChip` flow row: `Balanced`, `Fastest`, `Smartest`, `Reliable`, `Manual order`. Description below changes with selection:

| Strategy | Description |
|---|---|
| Balanced | `Reliability leads; speed and intelligence split the rest.` |
| Fastest | `Prefers throughput, but a fast broken model still loses.` |
| Smartest | `Prefers capability, with reliability keeping it honest.` |
| Reliable | `Whatever is most likely to just work.` |
| Manual order | `Follows your explicit order and skips scoring.` |
| Custom (imported only) | `Custom weights from an imported config.` |

**Chain card, `Next request would try`**

- Empty: `Nothing is routable yet. Add a provider key below.`
- Populated: ranked rows. Rank in mono, padded to 2. Display name (rank 1 is SemiBold `onSurface`, rest `onSurfaceVariant`). Platform in mono `labelSmall`. Score to 3 decimals. A `3dp` `LinearProgressIndicator` per row, **animated**, drawn relative to the leader's score rather than to 1.0. Leader is tinted `primary`, the rest `onSurfaceVariant`.
- Exclusions, when present: `Excluded` label then one mono line per excluded model.

**Manual order card** (only under the Manual strategy): title `Manual order`, subtitle `Nothing routable to order yet.` or `{n} models, tried top to bottom.`, then `Reset` and `Edit` (both disabled when empty), then the top 3 as `1. {name}  ·  {platform}` and `+ {n} more`.

**Order editor dialog** `Chain order`: scrolling list capped at `420dp`, each row rank + name + platform + `↑` `↓` text buttons (disabled at the ends), rows animate into their new position. `Done`.

**Configuration card:** `Import or export the router configuration. The format is the same declarative config FreeLLMAPI uses, so files move between them.` Then `Restart router` (full width), then `Import` and `Export` side by side.

- Export dialog `Include API keys?`: `An export without keys describes your setup safely. With keys it is a credential file, only for moving to another device.` Buttons `Without keys` / `With keys`.
- Import dialog `Import router config`: `Merge keeps your current keys and layers this file on top. Replace discards everything you have now.` Buttons `Merge` / `Replace`.

**Provider card** (one per provider): `8dp` dot (`primary` if a usable key exists, else `outline`), label, `{n} of {m} models enabled`, and `Manage` / `Hide`.

Expanded:
- `Keys`: `None yet.` or rows of label + `•••• {last4}` (mono) or `no secret stored`, each with a `Switch` and `Remove`.
- `Add key` and `Refresh models` (the latter disabled unless configured).
- `Models` header with bulk `Enable all` / `Disable all`.
- Model rows: display name, optional score in mono `labelSmall` `primary` (**absence of a score is itself information**: the router would not consider it), then a composed mono detail line `{size} · {n}k ctx · no tools · {n}% ok · {n} tok/s · {n}% quota used` where each segment appears only if known, then a `Switch`. When cooling down: `cooling down {n}s` in `error` plus a `Clear` button.

**Usage card** (only when usage exists): up to 10 rows, `{platform} · {model}` and mono `{n} turns · {n} tok · {n}ms`.

**Add key dialog** `Add {platform} key`: `API key` field, `Label` field prefilled `default` with supporting text `Add a second key with a different label for more free quota.`, optional `Get a key` link, `Save` (enabled only when the secret is non-blank), `Cancel`.

**Notice banner** (bottom-center overlay, dismissible): `errorContainer` when it is an error, else `surfaceVariant`. Body text, then up to 6 warnings as `• {warning}` plus `…and {n} more`, then `Dismiss` right-aligned. Real messages:

- `The key was empty, so nothing was saved.` (error)
- `No provider has a usable key yet, so there was nothing to sync.`
- `{scope}: catalog already current.`
- `{scope}: added {n}, disabled {n} retired.`
- `Could not sync models: {reason}` (error)
- `Router restarted. {sync result}`
- `Router restart failed: {reason}` (error)
- `That file could not be read as router config: {reason}` (error)

**Busy overlay:** an indeterminate `LinearProgressIndicator` pinned full-width to the top of the screen during any sync, restart, or import.

---

### 4.6 Settings (`⚙`)

Three cards: Personalisation, Appearance, and a navigation card.

**Appearance:** title `Appearance`, subtitle `Dark` or `Light`, and a `Switch`.

**Navigation card:** two full-width outlined buttons, `Playground` then `About`.

---

### 4.7 Personalisation card (inside Settings)

Header `Personalisation` + `Shapes how the agent talks to you. Every field is optional, and changes apply from your next message.`

Five outlined fields, radius `12dp`:

| Label | Placeholder | Lines |
|---|---|---|
| `What should the agent be called?` | `Reyaak` | 1 |
| `What should it call you?` | `Yash` | 1 |
| `What character should it have?` | `Dry, skeptical, no flattery` | 3 |
| `What should it know about you?` | `Android dev, Kotlin and KMP, prefers short answers` | 4 |
| `Anything else it should follow?` | `Show code before explaining it` | 4 |

Buttons: `Save` (enabled only when the draft differs from what is stored). Then `Revert` while dirty, or `Clear` when clean and non-empty. After saving: `Saved. It applies from your next message.`

---

### 4.8 History (pushed page)

`New conversation` outlined full-width button at the top.

Empty: `Nothing yet. Anything you send in Chat shows up here.`

Row card per conversation: title (from the first user message, `bodyLarge`, 1 line), optional last message flattened to one line and cut at 90 chars, and `{n} message` / `{n} messages` in mono `labelSmall`. Plus `Delete`.

Active conversation: card background `surfaceVariant` and title SemiBold. Rows animate their height on removal. Deleting the open conversation lands on the next most recent, or a fresh empty one.

No rename by design: the transcript names itself.

---

### 4.9 Playground (pushed page)

Header `Playground` + `Send one prompt straight through the router. No agent and no transcript: this is for checking a key or comparing a model.`

`Prompt` field, up to 5 lines.

Model pin, a full-width outlined dropdown trigger:

| State | Label |
|---|---|
| Nothing routable | `No routable model. Add a key first.` (disabled) |
| No pin | `Model: router chooses` |
| Pinned | `Model: {key}` |

Menu: `Router chooses` first, then `{platform} / {displayName}` per routable model.

`Run` (enabled when the prompt is non-blank and nothing is running). While running: an `18dp` circular spinner and a `Stop` button appear beside it.

Result panel (appears once there is a reply, an error, or a footnote): `background`-colored surface, radius `12dp`, capped at `320dp` with its own scroll. Reply in mono `onSurface`; error in `error`; footnote in mono `labelSmall` = `{provider} · {model} · {ms}ms · {n} attempts · {n} tok`, or on failure a per-attempt list of `{platform}/{model}: {outcome}`.

Stopping mid-stream keeps whatever text arrived.

---

### 4.10 About (pushed page)

Four cards. Section labels are uppercase mono `primary`.

1. `Reyaak` (`headlineSmall` Bold), version in mono, then three paragraphs on the agent, the router, and on-device storage.
2. `HOW IT FITS TOGETHER`: four bullets (router / core / UI / the agent reaches models only through the router).
3. `PRIVACY`: three bullets (Keystore key never leaves the device, backup and transfer disabled, no telemetry).
4. `BUILT BY`: `Yashwanth`, `github.com/yashoncode` in mono, and an `Open GitHub` button.

---

## 5. Cross-cutting patterns

Design each of these **once** as a system component. They currently repeat as one-offs.

1. **Three-state readiness.** Off / on-but-not-connected / active. Appears on every tool row, and as a two-state `8dp` dot on provider cards, the agent status card, and the chat agent strip. Needs one color-coded badge language, not caption text.
2. **Collapsed configuration section.** A summary row plus `Configure`/`Hide` or `Manage`/`Hide` revealing a form. Used by fastCRW, Gmail, IMAP, and every provider card. Animates its height.
3. **Dirty-gated Save.** `Save` is disabled until a draft differs from stored state. Used by fastCRW, IMAP, Personalisation, and the skill dialog. Needs a visible dirty affordance, not just a dead button.
4. **Advisory banner.** `surfaceVariant` card, one sentence, one text action. Agent screen only today; the pattern should generalize.
5. **Transient notice banner.** Bottom overlay, error or neutral, expandable warning list, dismissible. Router only today. This is the app's only real feedback channel and every other screen lacks one.
6. **Secret-bearing field.** Masked input, `•••• {last4}` display, and the recurring promise `Stored encrypted with the same hardware-backed key as your provider keys`. Deserves a visible trust marker.
7. **Async action with pending and error states.** Gmail sign-in, model refresh, router restart, import, export, playground run. Each currently invents its own indicator: a busy label, a top progress bar, an inline spinner.
8. **Provenance footnote.** Mono, dot-separated, always `{platform} · {model} · {ms} · {attempts} · {tokens}`. Appears in bubbles, CLI lines, and the playground. Should be one component.
9. **Score plus relative bar.** Chain rows only today, but it is the clearest expression of what the router does and could carry more weight in the design.
10. **Monospace as machine truth.** Already consistent. Keep it.

---

## 6. Every UX state worth mocking

### Happy path
- Chat ready, empty transcript
- Chat with transcript, bubble mode
- Chat with transcript, CLI mode
- Agent running, all counters advancing
- Router with a healthy multi-model chain
- Tool row active
- Gmail connected
- History with several conversations, one active

### Empty and first run
- Chat: no keys (the app's real front door)
- Chat: keys but agent stopped
- Chat: ready, nothing sent yet
- Chain: `Nothing is routable yet.`
- Provider expanded with no keys: `None yet.`
- Manual order with nothing routable
- History empty
- Skills: none active
- Playground: no routable model
- Usage card absent entirely (renders only when data exists)

### Loading and pending
- Assistant bubble streaming, dots + `routing…`
- Assistant bubble streaming, dots + `running {tool}…`
- Assistant bubble streaming partial text
- Gmail `Signing in…`
- Router busy: top progress bar during sync, restart, import
- Playground running: spinner + `Stop`
- Model cooling down with a live countdown

### Error and recovery
- Message bubble in `errorContainer`
- CLI error line `! {message}`
- Agent-not-running error surfaced into the transcript
- Gmail `Sign-in did not complete.`
- IMAP bridge-only provider, in `error` color
- Router notice, error variant, with a warning list
- Playground failure with a per-attempt outcome list
- Empty-key rejection: `The key was empty, so nothing was saved.`

### Permission and OS
- Notifications-off advisory
- Battery-optimisation advisory
- Both advisories stacked
- OS notification permission dialog on first start
- Battery exemption system screen
- Google consent screen
- Document picker (import) and creator (export)

### Degraded
- No provider key anywhere: chat dead, playground dead, chain empty
- Agent stopped: composer disabled with an explanatory placeholder
- fastCRW unset: web tools fall back to the no-JavaScript backend
- Tool on but not connected
- Every model in a provider disabled
- Keyboard open: nav bar gone, composer lifted

### Overflow
- Very long single message
- Provider listing 300 models (the reason bulk enable/disable exists)
- Warning list over 6: `…and {n} more`
- Manual order over 3: `+ {n} more`
- Usage over 10 rows (silently truncated)
- Playground reply past `320dp`
- Long model display names (`maxLines = 1`, no tooltip)

---

## 7. Gaps to fix in the redesign

These are absent from the code today. Design them as new, not as existing behavior.

**High**

1. **Destructive actions have no confirmation.** `Delete` on a conversation and `Remove` on a provider key both fire immediately. So does `Stop agent`.
2. **No feedback channel outside Router.** The notice banner exists only on Router. Failures on Agent, Tools, Skills, and Personalisation are inline or silent. There is no snackbar or toast anywhere in the app.
3. **Readiness is caption text.** The three tool states, the two dot states, and the cooldown state all compete with body copy at `labelSmall` in `onSurfaceVariant`.
4. **No credential validation at entry.** Saving IMAP details or a provider key tests nothing. A wrong password surfaces much later as a failed turn, with no path back to the field that caused it.
5. **No failed-message retry.** An errored bubble is terminal: no retry, and no way to resend the prompt.

**Medium**

6. **No first-run onboarding.** The empty-chat no-keys state is the entire funnel into a two-step setup (add key, then start agent).
7. **Provider-level errors are homeless.** A per-provider sync failure lands in the shared bottom banner rather than on the card that failed.
8. **No scroll-to-bottom affordance** once the transcript is scrolled up, even though auto-follow deliberately stops.
9. **No offline state** distinct from a provider failure.
10. **No service-death state.** If Android kills the foreground service, the UI shows `Stopped` with no explanation, which is exactly what the heartbeat counter exists to diagnose.
11. **Gmail error is sticky.** It clears only on a retry or a disconnect, so a stale failure can outlive its cause.

**Low**

12. Skills has no empty state (the header and footer would render around nothing).
13. Usage silently truncates at 10 rows with no indication that more exist.
14. Playground results cap at `320dp` with no expand.
15. Truncated model names have no tooltip or overflow reveal.
16. No accessibility content descriptions on any glyph, and glyphs are the entire icon system.
