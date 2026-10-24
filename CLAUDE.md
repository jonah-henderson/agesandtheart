# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Design documents

`notes/` holds the design record, and it is **normative** — where code and these documents disagree,
that is a bug in one of them, so revise the doc rather than letting the code drift away from it. Read the
relevant one before working in its area; most of the value is in the _reasoning_, not the conclusions.

**Unbuilt work keeps its full reasoning; finished work keeps only what a future builder needs.** When
something lands, delete the narrative of building it; a choice between genuinely open designs becomes one
`decisions.md` entry. Rewrite the paragraph that is now wrong rather than correcting it in place.

- **`notes/the-world-model.md`** — **read this first, and before anything under `age/`.** What a world is
  made of (properties holding a value, a weighted set or a cast), what a word is, how a book resolves, and
  what a word costs. **Built**, normative and current — it supersedes `the-art-design.md` §3 and §4. Its §3 is
  words as meanings and how a row of pages is read; `in <biome>` siting is built, its widening is not.
- **`notes/the-art-design.md`** — "the Art": the books, the language, consequences, book editing, the economy.
  Normative for §1–§2 and §5–§9; what was its §3 and §4 is the world model above, and only §3's two disciplines
  and §4's found books, failure channels and pipeline remain. Read §7.4 before touching `desk/` or the desk's
  screens.
- **`notes/the-art-implementation-plan.md`** — where things stand and what is open: Phases 1–6 done, Phase 7's
  open items, Phase 8 tabled, Phase 9's list, and an any-time list. **Start here when picking the next job.**
- **`notes/vocabulary-pass-plan.md`** — what is left of the pass that writes the words: the tag hand-tuning,
  the corpus audit and its gaps, the unbucketed words, and three design questions. It opens with the line that
  says to delete it when the last item goes. **Author with `scripts/scrivener.sh`**, whose `Verdict` is what a
  valid word is; read this before touching `art/word/`.
- **`notes/instability-tiers-plan.md`** — what is left of the instability tiers, which are **built** and live in
  design §5.0 (the strange tier holds fifteen manifestations): foreign ground's gaps, the mirage experiment,
  and the wounds' rework. Read it before adding a manifestation or touching `art/manifestation/`.
- **`notes/strange-words-plan.md`** — the words that let a writer ask for what instability inflicts. **Built but
  for its remainder:** the meanings the words do not carry yet, loose ends from the per-Age clock and danger, and
  two questions for Jonah.
- **`notes/implements-afield-plan.md`** — what the desk implements do away from a desk. **Unbuilt.** The
  frequency tuner reveals a place's code — five rime crystals, derived from the place and recorded forever —
  and the crystal viewer looks at it through one fixed live eye; the geologist's tools survey their chunk;
  the seismograph records vibrations in every world; the transcriber, in desk range, lays a descriptive book
  onto the desk or copies it into a new instance of its Age. The grammar guide's use afield is still open.
  Read it before touching an implement's block or `RimeColour`.
- **`notes/the-tag-layer.md`** — the fifty-three tags (forty-one properties and the twelve colours), where each
  is derived from, and why three separate things are called rarity. **Built**; what is left is the hand-tuning
  its §7 step 4 describes, done through Scrivener's tag screen (`/age tags` answers the same question against a
  running server). Read it before touching `art/preset_tags/`, adding a tag, or authoring a word that queries one.
- **`notes/terrain-architecture.md`** — the two-tier terrain system (composable field toolkit + bespoke
  presets): the evaluation contract, where things live, the performance budget. Built and shipped.
- **`notes/walk-list.md`** — **what is built and unwalked.** A walked item is struck, and becomes a
  `decisions.md` entry only where the walk decided a real fork. Read it before walking anything or before
  assuming a recent change has been seen.
- **`notes/decisions.md`** — **the forks that were decided**, each one subject sentence and one decision
  sentence, and only where several designs were genuinely open. It exists so that old decisions are not
  retrodden: read it before re-proposing anything. No history, walk logs or measurements belong in it.
- **`notes/accretion-audit.md`** — the audit against LLM accretion. Passes 1 and 2 are done; it is now the brief
  for pass 3 (renames and comment trimming, waiting on Jonah's go-ahead) and its one open finding.
  `notes/audit/tooling/` holds the pass-2 workflow, scan and digest that pass 3 adapts. **Re-date every finding
  against the commits since before acting on it.**
- **`notes/generator-versions.md`** — what moved at each `CURRENT_GENERATOR_VERSION` bump, and which Ages
  it moved. Read it when bumping the stamp, and add a row.
- **`notes/version-upgrade.md`** — what each Minecraft hop has cost this codebase (26.1.2, 26.2, 26.3), the
  traps the compiler cannot see, and the two 26.1 subsystems that changed what is worth building. **26.3 is the
  line; 26.2 support lives on the `mc-26.2-support` branch on Jonah's other machine** and is revisited near 1.0.
  Read it before the next port.
- **`notes/minecraft-ui-conventions.md`** — what a vanilla screen actually looks like, measured off the
  26.1.2 jar: the bevel rule, the border widths, the 18-pixel grid. There is no official style guide, which
  is why this exists. Read it before drawing a control; screens are vanilla widgets composed through `client/ui/`.
- **`notes/palm-beach-design.md`** — our own Age-only biome: white sand, a reef lagoon, palms, surf. Built and
  walked. Its one real piece of engineering is a **declared climate home** for a biome of ours, since
  `BiomePreference.homesFor` drops a biome it has never seen somewhere random; the surf is flat client-side
  particles on per-stretch wave clocks. Read it before touching either.
- **`notes/water-colour-research.md`** — how to make an Age's water shift colour over time: the tint is baked
  into the chunk mesh. A **still** colour is built (`cyan waters`, through Ephemeris's `GroundTints`); pipelines
  reading `GameTime` already exist, and what is open is how water's mesh reaches one, and which Ages get it.
- **`notes/coloured-light-research.md`** — coloured light, and why the lightmap can never give it: it is a
  16×16 texture indexed by (block, sky) with no position in it, so every lightmap route tints the *whole
  view*. The route that works is per-vertex colour at section-compile time — built on both loaders (Fabric
  through Indigo's quad transform); Sodium is open. Read it before proposing coloured light, a lightmap trick,
  or a post effect. Also holds `CardinalLighting`, a per-Age look knob for one line of datapack.
- **`notes/energy-interop-research.md`** — settled: **no energy API either way**; machines run on a block of arc
  crystal beside them, and any reopening is export-only. Read its version-trap section before writing energy
  code: NeoForge replaced the whole API in 21.9 and every older tutorial fails *silently*.
- **`notes/link-panel-research.md`** — the live view of an Age on a bound book's panel, **built**: why a preview
  `ClientLevel` beats a hand-written mesh builder, and the register of everything vanilla keeps exactly one of
  per client. **Read that register before standing up a second level of any kind** — every fault this feature
  had was an item on it.
- **`notes/neoforge-dimensions-research.md`** — why runtime levels are our own code (now in Ephemeris) rather
  than DynamicDimensions or Fantasy, and the traps found owning them. **Read it before looking at either library
  again.**
- **`notes/config-research.md`** — how mod config UIs work (they introspect a spec, or host a screen you
  write), why it is NeoForge's `ModConfigSpec` with Forge Config API Port on Fabric, and which axis (datapack or
  config) a parameter belongs on. **Built** — the spec is `AgeConfig` in `common`; add an option there, not to a
  second spec. Open: Configured on Fabric, and Fabric's once-a-second "Correcting" loop.
- **`notes/before-release.md`** — what has to be true before anyone else plays this: the renames and codec
  changes that now cost a migration, the content debts, the Sodium survey and the assets still standing in on
  vanilla's, and the case for a base dimension being any dimension, modded ones included.
- **`notes/release-process.md`** — the one-command release: `scripts/release.sh` tests and builds a chosen commit
  in throwaway worktrees, tags it, attaches the jar to a GitHub Release and moves the packwiz pack on the orphan
  `pack` branch. **Built**, and it has made every release; Ephemeris has its own script and the catalog pins
  its release. Read it before touching the tags or the release.
- **`notes/dni-sources.md`** — where the D'ni comes from, and how good the transliteration actually is.
  The two fan compilations we spell from (and why they stay out of this repository), the finding that the
  fuller one's headwords *are* our typeface encoding, and that the rules reproduce 84% of its 717 pairs.
  Open: `ogla` vs `oglan`. Read it before touching `art/script/dni.json` or `art/transliteration.json`.
- **`notes/authoring-tools.md`** — how structures and 3D models get authored: the external tooling, and what our
  own datapacks could carry that they do not yet. Part II holds the open forks for pack data (a datapack
  registry, data-driven terrain fields); Parts III and IV are built — the `.gen` grammar notation, and blocks
  drawn from an entity renderer and stood on, with the one constraint (axis-aligned shapes) any further shaped
  collider must answer. Read it before building a structure, a model, or anything that wants to be pack data.
- **`../ephemeris/notes/sky-limits.md`** — what a per-level sky can and cannot change, in Ephemeris's own notes.

## What this is

**Ages and the Art** — a Minecraft mod (Mystcraft-inspired: author dimensional "Ages" from written Symbol pages, link between them) for **Minecraft 26.3**, with 26.2 kept on the `mc-26.2-support` branch on Jonah's other machine, built as a **multiloader** mod running on both **Fabric** and **NeoForge** from one codebase. Mod id `agesandtheart`, root package `co.voik.agesandtheart`.

Current state: Phases 1–5 are done and Phase 6 (consequence) is built through its last register. The Art's language, grammar, resolver and terrain system are built and checked; `/age write` authors an Age from a sentence, and the loop runs without commands — word pages, the notebook, the writer's desk, descriptive and linking books, and the three acquaintance devices that put the derived corpus within reach. An Age's instability now reaches generation: seams widen, wounds open, the wounds go on opening, and collapse tears the floor. Phase 7 (rewards) is mostly built — danger scoring, deretheni, the D'ni city, yema, scarab colonies — and a reward never asks who wrote an Age: the Art's own books are kept off reward conditions by their grammars. Phase 8 (book editing) is tabled (design §6.6), and Phase 9 (the asset pass) is under way for items while blocks still borrow vanilla's textures. **Runtime dimensions work on both loaders**, on **Ephemeris** — our own library, now its own project at `../ephemeris` (see "Ephemeris" below).

**26.1 is the first unobfuscated Minecraft release**, which is why there is no Parchment in the catalog: 1.21.11 was the last obfuscated one and there is nothing left to deobfuscate. Mappings-related advice written for the 1.21 line does not transfer.

## Requirements

Java 25 (Temurin, via SDKMAN). Gradle comes from the wrapper — always use `./gradlew`, never a system Gradle. Note that the Java level is real: Dokka 1.x cannot parse a "25.x" version string and fails before reading a line of source, which is why the catalog pins Dokka 2.

**Non-interactive shell gotcha:** SDKMAN's init lives in `~/.bashrc` and may not be sourced in non-login shells, so `java` can be missing from `PATH`. Before running Gradle, point at the JDK `.sdkmanrc` pins — not SDKMAN's `current`, which on some machines is a system JRE with no compiler:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4-tem"; export PATH="$JAVA_HOME/bin:$PATH"
```

## Commands

```bash
# Build + remap both loaders (produces */build/libs/agesandtheart-<loader>-26.2-<version>.jar)
./gradlew build

# Launch the game in a dev sandbox (requires a graphical display)
./gradlew :fabric:runClient
./gradlew :neoforge:runClient

# The same, without the Ephemeris version requirement — for when the catalog is ahead of ../ephemeris
./gradlew :fabric:dev
./gradlew :neoforge:dev

# Headless servers (usable without a display; good for smoke-testing that the mod loads)
./gradlew :fabric:runServer
./gradlew :neoforge:runServer

# NeoForge data generation
./gradlew :neoforge:runData

# The offline test suite (Kotest) — see "Tests" below
./gradlew :common:test
./gradlew :common:test -Pfast

# The server checks — boots a real server, drives it over RCON, asserts in Kotest
./gradlew :common:serverTest

# The checks that take ten seconds or more, run before a release (see "Tests")
./gradlew :common:longTest

# The landform shape checks — opt-in, and they *warn* rather than fail (see "Tests")
./gradlew :common:landformTest

# Drive a headless server through a list of /age commands and read the output (see scripts/checks/)
scripts/drive-server.sh scripts/checks/regions.txt

# Scrivener — a vocabulary editor for Ages and the Art (see "Authoring a word" below)
scripts/scrivener.sh              # a new word
scripts/scrivener.sh colossal     # an authored one
scripts/scrivener.sh --audit      # every authored word, worst first
```

**Server checks are `./gradlew :common:serverTest`, and they own their own acceptance.** A Kotest spec
tagged `NEEDS_SERVER` uses `DrivenServer` (in `common/src/test/kotlin/.../server/`) to boot a dedicated
server, drive it over **RCON**, and assert on what comes back. The parts of that Scrivener needs too —
`Rcon`, `LaunchSpec`, `ServerLaunch`, and `MinecraftRegistries` — live in the **`preview`** source set,
which `test` can see and which cannot see `test`; one direction only, so a check never becomes something a
tool depends on. The concerns are split on purpose: the
driver starts, sends and stops, and _asserts nothing_; Kotest decides whether an answer is right, so a
failure carries a Power-Assert diagram rather than "nothing matched".

RCON is what makes that possible. `DedicatedServer.runCommand` is `prepareForCommand()` /
`executeBlocking(…)` / `getCommandResponse()`, so each command runs on the server thread, the call waits
for it, and the reply holds **that command's output and nothing else** — no barrier tokens, no slicing a
shared log, no timestamps to strip. Failures come back too (`RconConsoleSource.acceptsFailure()`).

Two things that follow, and both are load-bearing:

- **The commands can answer in JSON**, written `/age <subcommand> json …` (the literal goes straight after
  the subcommand because `/age write`'s sentence is greedy). Prose stays the default and is unchanged in
  game. A structured answer is _one_ message, which matters because RCON concatenates a command's messages
  with no separator — so the buffer _is_ the document. See `age/Report.kt`.
- **The server is started without Gradle.** These specs run inside a Gradle-launched JVM and a nested
  `./gradlew` would wait on the outer build's locks, so `:fabric:exportServerLaunch` writes the launch
  command down and `DrivenServer` starts the JVM itself. It writes to a fresh `checks-…` world, restores
  `server.properties`, and removes that world afterwards — the only deletion in the harness, fenced on the
  name and location so it can never reach a world a person plays. **It tidies up when killed, too**: a
  shutdown hook covers Ctrl-C and `SIGTERM`, and `LauncherWatch` inside the launched server covers
  `SIGKILL`, which nothing in the launching JVM can. Before that, a cancelled run left a server holding a
  world lock and a port for as long as it took somebody to notice.

**`scripts/drive-server.sh` remains, as a driver only.** It runs a list of `/age` commands against a
server and prints what they say, for the exploratory files that are meant to be _read_ — `aspects.txt`,
`regions.txt`, `generator-parity.txt`. Its old `#?` assertion layer is gone (`#?` lines are skipped so old
files still drive): it read the first integer on the matching line, which on a real server log is the
hour off the timestamp, so `at-least 100` could never pass and `at-most 2000` could never fail.

Run directories are `runs/` (Fabric) and `run/` (NeoForge), both git-ignored. The first build/run downloads Minecraft, mappings, and the loader toolchains — slow once, then cached.

**The age workshop writes books, and opens them in the game.** `scripts/scrivener.sh` → "the age
workshop" composes a sentence a page at a time, read by the same `Reader` a book is: the pages that would take
what is waiting come first, and a page that would land nothing where it is laid is set aside and counted (`^i`
shows them). The cost, the flaws and the parts still open move as each page goes down. `^o`
boots a dedicated server, writes the Age, and sends you in when you connect — kept up between rewrites, so
changing a page and looking again is seconds. The world is thrown away when the screen is left, fenced on the name so it can never reach one somebody plays (`ThrowawayWorldCheck`).
Drafts live in `.authoring/ages/` and hold the **pages**, never the recipe.

**Scrivener reaches past the words.** Its main menu is four groups — the words, the world they describe, the
age workshop, and the tool itself. Its tag screen edits `art/preset_tags/` with the overlay
visible — every carrier says whether its weight was **authored**, **overridden**, **derived**, or **dropped**, because
setting a weight on a derived tag *writes* an entry, clearing one lets the derived value back, and removing
a derived tag is `drop` rather than deletion. It also renames a tag across the carriers, every word that
asks and the opposed-tags table together, which is the one edit a text editor is worst at. One further screen
is **read-only on purpose**: the derivation rules, where enter runs one rule *by itself* to show exactly
what it catches. The file already states its rule perfectly and hides only its consequence —
`notes/decisions.md` carries the split.

**Authoring a word is `scripts/scrivener.sh`, and it works offline.** A full-screen editor over the real corpus:
`Vocabulary.load(shippedData(), worldgen)` is the same call `VocabularyCheck` makes. A word is a list of meanings,
each one idea with its chances, and the editor shows each meaning's effects under it and adds one by what it does,
then where, then what. **Its verdict is the one copy of the per-word rules** (`Verdict`): it refuses what would
not ship and names the check that holds each, nudges toward the rulings in `notes/decisions.md`, and
`VocabularyCheck` asks it of every word. `^t` reads a book with the word as it stands, through `Trial`. What offline cannot know is datapack content — a tag only a bound registry grants
(`ore`), the pack's own placed features, the game's block tags — and `--refresh` drives `/age tags` and
`/age holdings` over RCON into `.authoring/server-snapshot.json` for every later run to read. A server is an
errand, never a condition.

It lives in `common/src/preview/kotlin/.../authoring/` and is started by the script rather than by Gradle,
because a TTY is what Gradle cannot give a `JavaExec`. `:common:exportAuthoringLaunch` writes the launch
down; the script runs it. **The codec owns the value and `WordFile` the file**: the `_comment` is carried beside
the word and laid first, and `AuthoringCheck` holds that every authored word writes back byte for byte.
`--rewrite` puts the corpus back in that layout and is run by hand, like `:common:grammars`.

**Tests:** **Kotest**, in four tasks split by what they cost and what they are worth.

```bash
./gradlew :common:test                     # the loop — everything but landforms and the server, ~40s
./gradlew :common:test -Pfast              # and without the specs that need Minecraft's registries
./gradlew :common:test --tests "*Grammar*" # one spec
./gradlew :common:serverTest               # boots a real server, drives it over RCON, ~2min
./gradlew :common:longTest                 # the checks that take ten seconds or more — before a release, ~2min
./gradlew :common:landformTest             # the shape of the rock — opt-in, ~220s, and it only *warns*
```

**`-Pon=<feature>` narrows any of them to one subject**, which is the iteration loop worth using:

```bash
./gradlew :common:test -Pon=sky            # ~1.5s instead of 17
./gradlew :common:serverTest -Pon=sky      # the sky checks that need a server, and nothing else
```

Features are `advancements aspects compatibility consequence desk levels meanings pages phenomena rewards sky stations terrain words`, and the map lives in
`common/build.gradle.kts`. **They are named after where the specs already live**, because the packages
already mirror the code they check — so this is a name for a directory rather than a second taxonomy to
keep in step. A feature with nothing in a given task runs nothing rather than failing, so `-Pon=desk` is a
fair thing to say to `serverTest`. A misspelled one lists what it should have been.

**A check is under ten seconds or it is long** (Jonah, 2026-10-20): `RUNS_LONG` on the spec or the one test,
and only `longTest` runs it, before a release; while developing, what it checks is looked at in the game. A
test another reads from takes its spec with it. Measured 2026-10-20 at 2m05s for `serverTest` and 2m13s for
`longTest`, from eleven minutes for the two together. **What made a fresh Age cost seconds was strongholds**:
every level searches 128 places for their rings when it opens, on the pool chunks generate on, so `DrivenServer`
writes a data pack into the checks' world giving the stronghold no biome to stand in (`BootLogCheck` holds that
it loaded). And **a check forces only the chunks it reads, side by side** — a chunk loaded on its own drags its
neighbours through generation with it.
**A wait in a server spec asks the server, never the clock**: `forceload add` returns once its chunks are
generated — **not once their entities are**, so a creature summoned straight after is out of sight of `@e` — and
`DrivenServer.waitUntil` asks until a chunk counts as loaded, which is what to wait on before summoning or
counting. `sprint` and `step` return when the server says the ticks have run.
**Iterate on one loader**; `-Pchecks.loader=neoforge` and the unfiltered run are what a merge is for.

**`landformTest` warns rather than fails, deliberately** (`ignoreFailures`), and
`common/src/test/kotlin/.../worldgen/Landforms.kt` carries the whole argument — read it before moving a
check in or out. In short: sixteen specs sampling millions of terrain columns were three quarters of a
five-minute cycle paid by everyone, they assert the emergent shape of layered noise so hand-authoring a
landform trips one long before the landform is wrong, and the terrain system is built and rarely moves.
The task ends by naming every failure under a banner, because a check that cannot fail a build is a check
that can drift. **Run it only when you are changing landforms**, and read what it says — nothing else pulls
it in. `build` and `check` reach `test` and no further, deliberately: a task wired into a gate it cannot
fail is the worst of both, minutes spent on an answer nobody has to read.

A check earns the tag by **sampling a field and asserting a shape**. Pure ones do not, however
terrain-adjacent: `SpansCheck` is arithmetic, `ChooseCheck` a selection rule, `DepthCacheCheck` a cache
agreeing with what it caches. Those stay in the task everyone runs.

The specs live in `common/src/test/kotlin/`, in packages mirroring the code they check. They are the
former `preview` "check" instruments — same assertions, same hand-written failure messages, now discovered
and reported individually. Two things about how they are written:

- **Assertions are plain `check(condition) { "what went wrong" }`.** The Kotlin **Power-Assert** compiler
  plugin is on for the `test` source set only, so a failure prints that sentence _and_ a diagram of every
  subexpression. Kotest's matchers are available and used where they read better, but `check` is the house
  style here because the messages were the point and they ported unchanged.
- **`@Tags(NEEDS_REGISTRIES)` marks a spec that needs `Bootstrap.bootStrap()`** — a few seconds, paid once
  per JVM, and the only slow thing in the suite. The annotation form matters: Kotest constructs a spec to
  discover its tests, so a fixture built in the constructor would be paid even under `-Pfast`. Anything
  expensive inside a spec should be `by lazy`.

Property-based tests use `kotest-property` (`checkAll`) — see `SpansCheck`, and note it generates the
_recipe_ for a value rather than the value, because that is what shrinks and what prints legibly.

## Architecture

This is **not** Architectury. Platform abstraction is done with plain `java.util.ServiceLoader`; Fabric uses **Loom**, NeoForge uses **ModDevGradle (MDG)**. Understanding the project means understanding four cross-cutting mechanisms:

**1. The three modules and how `common` reaches the loaders.**
`common/` holds all real logic and compiles against vanilla Minecraft only (via MDG/NeoForm) — it must not reference Fabric or NeoForge types. `fabric/` and `neoforge/` are thin adapters. Critically, `common` is **not** consumed as a jar: `common/build.gradle.kts` exposes its sources through `commonJava`/`commonKotlin`/`commonResources` configurations, and `buildSrc/.../multiloader-loader.gradle` wires those into each loader's compile/resource tasks. Net effect: common source is compiled _into_ each loader jar. There is no separate "common" mod to ship.

**2. The platform split (SPI pattern), spanning four files per service.**
When shared code needs something loader-specific, it goes through an interface, never a direct call. Taking `Platform` as the example:

- `common/.../platform/services/Platform.kt` — the interface
- `fabric/.../platform/FabricPlatform.kt` / `neoforge/.../platform/NeoForgePlatform.kt` — implementations
- `*/src/main/resources/META-INF/services/co.voik.agesandtheart.platform.services.Platform` — SPI registration (one line naming the impl)
- `common/.../platform/Services.kt` — `Services.PLATFORM` resolves the right impl at runtime

**There are three services**, each with that same set of four files: **`Platform`** (environment questions), **`Network`** (payload sending) and **`InkFluids`** (the fluid the loaders model completely differently). To add a platform-divergent capability, add a method to whichever of the three owns the concern. Only introduce a _fourth_ when it is a genuinely distinct service rather than a one-off need — `InkFluids` earns it because Fabric's fluid API and NeoForge's share no types at all.

**3. Entrypoints differ per loader; both funnel into `common`.**

- Fabric: `fabric.mod.json` `entrypoints.main` → `co.voik.agesandtheart.AgesAndTheArtKt::init` (a top-level `fun init()` in `fabric/.../AgesAndTheArt.kt`), using the `kotlin` adapter (Fabric Language Kotlin).
- NeoForge: `@Mod("agesandtheart")` on the class in `neoforge/.../AgesAndTheArt.kt`; its constructor runs (Kotlin for Forge provides the Kotlin entry).
  Both immediately call `CommonSetup.init()`. Keep loader entrypoints tiny; put logic in `common`.
  **Every list a loader would otherwise write out lives in `common`, and a loader only loops over it or
  calls it from its own event**: `CommonSetup` for the server's hooks (tick, chunks, players, stopping), `Payloads` for
  the payload routes and their server handlers, `AgeContent.mobAttributes`, and on the client
  `client/ClientSetup` (tick, chunks, disconnect), `client/ClientRegistrations` (renderers, screens),
  `client/ClientPayloads` (clientbound handlers) and `client/AgeFluidLooks` (fluid models). A new hook is a
  line in one of those; a new event is a function there and one line per loader.

**4. Sixty Mixins and two accessors in `common`, one more in `fabric`, all Java.**
`common/src/main/resources/agesandtheart.mixins.json` declares the shared ones, and each earned its place by there being no loader event that carries what it needs. Each carries its own argument in-file; read that before touching one.

- **`ServerPlayerMixin`** — the learned-word set. Four injectors: `readAdditionalSaveData` / `addAdditionalSaveData` persist it, `restoreFrom` carries it through death, and `initMenu` attaches the `ContainerListener` that notices a page arriving in the inventory. That last one is vanilla's own `inventory_changed` seam, which is why it beats polling.
- **`StruckCreaturesMixin`** — what a book struck is never made in its Age, by any path, and nothing living is
  made where nothing lives (`generation/StruckCreatures`): a shrieker's warden in `no monsters`, a spawner's
  zombie in `no zombie`, anything left out of an `only`. It
  targets both `ServerLevel` and `WorldGenRegion` at `addFreshEntity`, the one method every *new* entity
  passes through and an arriving one does not: Fabric's load event fires after the fact and for teleports
  too, NeoForge's cancels but cannot tell a teleport, and neither sees what a structure places in a chunk
  being generated.
- **`ServerLevelMixin`** — local difficulty near a wound (§5.1). No event exists on either loader: difficulty is computed on demand and returned by value, so this one method is the only place it exists.
- **`LightningBoltMixin`** — a bolt landing in a tempest. The entity-join event would fire re-entrantly inside `addFreshEntity` and cannot see the private `visualOnly` flag that marks a trap's harmless bolt.
- **`EnderDragonMixin`** and **`DragonHoldingPatternMixin`**, with **`DragonPhaseAccessor`** serving the
  second — a dragon an Age wrote, rather than the End's. Vanilla's is written around the world origin in
  two places `fightOrigin` did not reach: its flight ring is laid at absolute coordinates, and the landing
  approach steers by the player's absolute position normalised into a bearing from `(0, 0)`. Both are
  no-ops in the End, where the origin *is* zero. The accessor exists because `@Shadow` does not walk a
  target's hierarchy and `dragon` is declared on `AbstractDragonPhaseInstance`.
- **`client/ClientLevelMixin`** — a wound arriving where the client can see it. `setBlocksDirty` is the
  only seam that fires on both sides for a block change: `onPlace` is skipped client-side, so the index the
  wound renderer draws from would otherwise never hear about one torn after its chunk arrived. Neither
  loader has a client block-change event.
- **`ItemRemainderAccessor`** — contained plasma leaves its empty unit in a furnace. `Properties.craftRemainder` builds its template at once and needs the remainder registered, where ours are all built before any is registered; the field is final and its getter too, so it is set in `CommonSetup.afterContentRegistered`.
- **`client/LevelRendererMixin`** — draws the Age's wounds in one submission. Declared under the config's `"client"` array, not `"mixins"`. The loader alternatives exist here (Fabric's world-render events, NeoForge's `RenderLevelStageEvent`) and are declined deliberately: they are different objects with different stages where the vanilla seam is identical on both sides.
- **`MerchantResultSlotMixin`** and **`MerchantQuickMoveMixin`** — a page or notebook bought unwritten gets its word at the instant of purchase. An offer's `given_item_modifiers` run once for a stack handed out many times, so the draw cannot live there; and `quickMoveStack` merges into the inventory *before* calling `onTake`, so the shift-click path needs its own seam.
- **`fabric/.../mixin/fabric/client/RendererManagerMixin`** — the **only Mixin outside `common`**, declared by its own `agesandtheart-fabric.mixins.json`, and the only one that targets another mod. Fabric API's Indigo redirects chunk building away from `ModelBlockRenderer`, so coloured light needs a second seam on that loader; the registry takes exactly one renderer plug-in and Indigo has it, so what is handed out is decorated instead. `TintingRenderer` carries the argument and the alternatives. **A Fabric-only Mixin needs the Fabric config** — the common one ships in both jars, so a target that exists on only one loader cannot go in it.
- **`client/MainRenderTargetMixin`** — the linking panel's world render lands on the panel's target instead of the window. `LevelRenderer.renderLevel` hard-codes `getMainRenderTarget()` and sizes its whole frame graph from it, there is no parameter or event on either loader, and `Minecraft.mainRenderTarget` is `private final`. The redirect is live only inside `PanelTarget.redirecting`. It is the **only** Mixin a panel in a book needs: everything else it borrows from the client has a public setter, and `BorrowedFrame` is where that is done. A panel on a book lying open — on a lectern, or fallen where somebody linked from it — takes one more, **`client/GameRendererMixin`**, which renders it from the head of `extractGui` — the phase `BookScreen` renders its own in — since a book lying open has no screen to lend it the frame.
- **`client/LevelLoadingScreenMixin`** — a link's picture behind vanilla's loading screen, which replaces the
  transit the moment the player is moved (`LinkTransit`). Vanilla picks that background from the destination
  and an Age gets the menu's; NeoForge could swap the screen, Fabric has no event that does, and the
  background is all that differs.
- **`LecternBlockMixin`** and **`LecternBlockEntityMixin`** — books on vanilla lecterns (design §7.8.2). The click and the auto-close ride `useWithoutItem` and the lectern's own scheduled tick, and the block entity is taught to tell a client which book it holds, which vanilla never does. The `book_open` property rides `StateDefinitionBuilderMixin` beside deep waterlogging. The renderer is not a Mixin: `LecternBookRenderer` extends vanilla's, and `client/ClientRegistrations` registers it in vanilla's place.
- **`BlocksAttacksMixin`** and **`MaceItemMixin`** — what a potion of crushing resistance turns aside
  besides the deep. An axe disables a shield through `BlocksAttacks.disable`, the one method every
  `disable_blocking_for_seconds` weapon goes through; a mace's smash is `getAttackDamageBonus` and the
  shockwave is whoever `knockbackPredicate` accepts. Neither loader has an event for any of the three.
- **`EntityFluidInteractionMixin`** — a player falling through a star fissure touches no fluid. `noPhysics`
  stops collisions and block effects but not `baseTick`'s fluid scan, so water in the tear's column turned
  the fall into sinking. A null `getFluidInteractionBox` empties every fluid reading at once; neither loader
  has an event for it.
- **`client/SectionOcclusionGraphMixin`**, **`client/SectionCompilerMixin`** and **`client/CameraMixin`** —
  what a fall through a tear does not draw (`client/BelowTheLid`). The veil covers every direction but the
  hole, so the cave ceilings between the eye and the opening were all that showed through it: the first
  drops every section wholly under the lid from the frame, the second rebuilds the section the lid cuts
  through without the blocks under it, and the third answers no fluid for the camera, whose water fog and
  narrowed field of view the player's own readings never reached. Neither loader has an event for any of
  the three. **Vanilla's section list and mesher only** — Sodium replaces both (`notes/before-release.md`).
- **`FlowingFluidMixin`** — an ebbing tide does not refill itself. The ebb takes the sea above where the
  tide stands and vanilla's infinite-water rule turned the flows back into sources as fast as they drained;
  this refuses that conversion only for the sea's water in the open, inside the tide's band and above where
  it stands (`Tide.holdsBack`), since `canConvertToSource` takes no position and its caller `getNewLiquid`
  does. Water still flows there; it only cannot become new sea.
- **`RubbleLandingMixin`** — rubble a fissure lets down settles before vanilla places it (`RubbleArrivals.settle`):
  what is `#agesandtheart:crushable` is broken under it, what is `#agesandtheart:breaks_rubble` breaks it, and
  anything else holds it up. `FallingBlockEntity.tick` reads the landing position once, straight after `move`, and neither loader has
  a falling-block event; a subclass would need the private `blockState` widened and an entity type of its own.
  Only falling blocks carrying the rubble tag are touched.
- **`EntityGravityMixin`** — an Age whose gravity is strange (`StrangeGravity`). `Entity.getGravity` is final and
  the one method every falling thing asks, where the `GRAVITY` attribute reaches only living things; both sides
  read their own copy of the Age's factor, since the client moves its own player. A fall's damage follows it
  in `LivingEntityMixin`, which counts the fall as the vanilla one that lands as hard.
- **`SkaterHungerMixin`** — a skater pays a walker's hunger at any speed (`RimeSkates`). Both
  `causeFoodExhaustion` charges are worked out inline, in `checkMovementStatistics` and `jumpFromGround`, and
  neither loader has a food-exhaustion event. The sprint's charge is found by slicing between the
  `SPRINT_ONE_CM` and `CROUCH_ONE_CM` stats rather than by ordinal, as `LivingEntityMixin`'s skate locals are
  found by name: 26.3 slid a local in front of an ordinal there and nothing failed.
- **`FeaturePlacerMixin`**, **`TreeFeatureMixin`** and **`JigsawPlacementMixin`** — what the strange tier does to
  new ground (`StrangeGround`): every feature drawn from one random per feature, trees handed a foliage setter
  that places nothing — or, where a book grew them of a block (`diamond_block trees`), one that places that block in
  each leaf's stead — and a village's start drawn from its pool's abandoned pieces. **`TreeGrowerMixin`** carries the
  block to a sapling grown later (`generation/GrownTrees`): `growTree` is the last call that knows which tree it is,
  and Fabric has no sapling event. Each is the one call where
  the thing is decided, and neither loader has a placement, tree or jigsaw event. All three answer off
  thread-locals the Age's generator sets around its own decoration and structure starts, so they cost one read
  anywhere else.
- **`BubbleColumnBlockMixin`** — in a level with an abyss, every bubble column is `DeepBubbleColumnBlock`'s, so one column runs on through ordinary water and deep water, each block keeping the water it replaced. In 26.1 a column is raised by the liquid over magma or soul sand calling the static `updateColumn`; neither loader has an event for it, and vanilla places its blocks without notifying anything, so a column stopped at the abyss had nothing to tell the deep water above to carry it on. Only vanilla's own column, only where there is an abyss.
- **`ChunkGeneratorMixin`**, **`JigsawStructureMixin`** and **`StructureMixin`** — what an `incongruous structures`
  Age builds where it does not belong (`generation/IncongruousStructures`): any structure in any biome, at a height
  drawn per start among as written, the surface, the sea floor, the sky, buried and deep. The biome test is built
  inline in the private `tryGenerateStructure`; a jigsaw start's height is its final `startHeight` and projection,
  handed to `JigsawPlacement.addPieces` in one call; every structure that sits on the ground asks through two
  shared `Structure` helpers. No loader event reaches any of them, and all three answer off a thread-local the
  Age's generator sets around its own structure starts. **`OceanMonumentStructureMixin`** joins them for the one
  structure with a biome check of its own, the ring of ocean a monument asks for around it. And the structure
  state is built over `StructuresAnywhere`, so no set is dropped for want of its biomes before any of this is asked.
- **`StructureStartMixin`** — what a book built the Age's structures of (`generation/StructureMaterials`):
  `nether_bricks structures` swaps each structure's commonest full block, and the variants of it the material
  has too, as its blocks are set. `placeInChunk` is the one call every structure's blocks go through, template
  pieces and coded ones alike, and neither loader has a structure-placement event; a `StructureProcessor` would
  reach only the templates. The structure is placed again through a level that swaps what is set, off a
  thread-local the Age's generator sets around its decoration.
- **`LevelRainMixin`** and **`client/ClientLevelRainMixin`** — a deluge's rain falls on biomes that never see rain
  (`Deluge.precipitationIn`, `Downpours.precipitationUnder`), as a sign that something is wrong; snow stays snow.
  Precipitation is a fixed property of each biome rather than an environment attribute, and neither loader has an
  event for it. Each redirects the biome's own answer inside the one method its side funnels through —
  `Level.precipitationAt` for `isRainingAt`, `ClientLevel.getPrecipitationAt` for the weather renderer and its
  splashes — so vanilla's refusals for no rain, no sky and cover stand.
- **`SpawnCapsMixin`**, **`SpawnStateMixin`** and **`MobCountsMixin`** — the mob caps a book moved
  (`generation/SpawnCaps`, `Spawns.CAPS`): `teeming inhabitants` holds four times vanilla's cap in every pass. A cap is
  `MobCategory.getMaxInstancesPerChunk`, a constant read in two places handed no level — the level's cap in
  `NaturalSpawner.SpawnState` and each player's in `LocalMobCapCalculator.MobCounts` — so the first takes the level's
  caps where its spawning begins, in `createState`, and the other two read them. Neither loader has a cap event.
- **`FishingHookMixin`** — what fishing in an Age gives (`generation/AgeFishing`): a caught fish drawn again by
  how thickly the book asked for each kind where the bobber is, and rich waters (`Fishing.TREASURE`) fishing with
  more luck, which vanilla's table spends on treasure. `retrieve` builds the luck and rolls the table in one
  method; Fabric has no fishing event and NeoForge's comes after the roll.
- **`NaturalSpawnerMixin`** — what an `incongruous inhabitants` Age lets live anywhere (`generation/IncongruousSpawns`):
  vanilla's placement check, the type's spawn predicate and the creature's own spawn rule all pass, the peaceful
  rule and the collision checks kept, scarabs excepted. Fabric has no natural-spawning event and NeoForge's
  spawn-placement event is per type at registration rather than per level. The chunk-generation pass is ours
  already (`ChunkGenerationSpawns`) and frees itself. A creature asked for in herds is placed by a cow's rules at the
  same three seams (`generation/HerdedSpawns`). **`MixinsRegisteredCheck` holds every Mixin class listed in
  the config** — an unlisted one is never applied and nothing says so.

The sky Mixins left with Ephemeris and are `co.voik.ephemeris.mixin.client.*` now — do not look for them here.

**Always prefer a loader event or vanilla API over a Mixin when one exists**, and say in the commit which alternatives were checked. Mixins are written in **Java** (Kotlin isn't viable). On **Loom 1.17** the mixin annotation processor / refmap is off by default and 26.1 is unobfuscated anyway — do **not** re-add a `loom { mixin { … } }` block.

## Ages / runtime dimensions

The core mechanic — creating dimensions ("Ages") at runtime and persisting them — is split in two, and the
split is the point: the **model** and its store live in `common/.../age/`, and the **composition** that turns
one into a world — `Ages`, `AgeGeneration` and `AgeChunkGenerator` — lives in `common/.../generation/`. The
model is the bottom layer every feature imports; the composition is the top layer that imports every
feature, and keeping them in one package made a cycle of each. Opening a level is Ephemeris'
`RuntimeLevels` on both loaders:

- **`AgeRecipe`** — **what an Age is, as data**: the world it was written from (a composition of slot presets, or one of the few bespoke generators), its seed, the character drawn for it, the instability and words it was written with, and the generator version that made it. Codec-serialised, and the _only_ record of an Age — the dimension is rebuilt from it on every open. `AgePreset` names the generation presets; its `key` is the save format, so renaming one orphans every Age already written with it.
- **`AgeGeneration`** (in `generation/`) — turns a recipe into a `ChunkGenerator`, in an exhaustive `when` over `AgePreset`. A pure function of the recipe (plus the server, for registries), because an Age must rebuild identically on every open.
- **`AgeSavedData`** — vanilla `SavedData` on the overworld's data storage, persisting each Age's recipe. Runtime-dimension libraries do **not** auto-restore dimensions on restart, so we track them ourselves.
- **`Ages`** (in `generation/`) — loader-agnostic policy: `create` / `open` / `ensure` / `delete` (calling `RuntimeLevels.open` / `RuntimeLevels.delete` with the recipe and dimension type, which are ours) and `reloadSaved` (replay on boot).
- **`age/language/`** — **words, and how a book is read** (`notes/the-world-model.md` §3). A `Word` carries
  **meanings**, each one idea of one or more `Effect`s with its chance to apply where aimed, laid bare, or
  always; its price is read off them. `Acceptance` is derived, never written: an aspect's page takes what its
  part of the world owns, a member word what its preset honours, a minting a substance, and blocks take
  nothing. `Reader` reads a row of pages — a page is taken whole by the first word that accepts any of its
  meanings, a page nothing takes lands what it asserts or is moved to `age`, and `Draw` decides, page by page
  off the seed, which candidates land — into a `Reading`: each page's `Fate`, what could land and what did.
  `Reading.readout` says it back page by page; `Prose.of` is what a book *reads* as, clauses stored on the book
  and written into English by `ProseWriting` on the client. `DerivedWords` gives **every block and biome in the
  pack a word of its own**, so the corpus is ~1100 offline and more on a server.
- **`age/resolution/`** — **landed claims in, `Resolution` out**, and it never imports `age/language/`: a
  `Book` of `Landing`s and the `WorldFacts` it is resolved against become a composition, a cost and an
  instability (`Resolver`). Incoherence is charged from what words *could* land, before the draw, one flaw per
  pair. Resolution is a **pure function of (vocabulary, pages, seed)**; the resolved composition is what
  persists, never the words (design §4.6).
- **`age/word/`** — what holds the words: `Vocabulary` (the corpus, **loaded from datapack JSON** under
  `data/<namespace>/art/` — words, per-slot tag tables, opposed tags, structural pages), and
  `PresetProfile`/`PresetTags` (what the world is like — hung on `Taggable`, which is an `AuthoredPreset`
  this pack wrote or a `RegistryReference` into one of the game's registries).
- **`Holds`** — what an aspect holds: a catalogue, a weighted set, a population, or nothing but its own
  properties. One enum where there were two parallel `Kind`s, and what `Aspect` answers with rather than a
  type of its own.

> **`notes/the-world-model.md` has landed.** It was the design the code was written against and the two no
> longer disagree: `Domain` is gone — an aspect *is* its own page, so `art/domain/` no longer exists — and
> so are `Scope` and both `Kind` enums, replaced by `Holds`. There are no counts, `sky` became the vault
> against `sun`, `moon` and `stars`, and `in <biome>` (world model §3) is built. Read that document before changing
> anything under `age/aspect/`, `age/language/` or `age/resolution/`; it is normative and current, not a plan.
- **`age/word/generation/`** — the grammars the Art writes *out* of, which are **datapack content**
  (`art/generation/<name>.json`, authored as `src/main/generation/<name>.gen` and exported by
  `:common:grammars`): `basic_book`, `advanced_book` and `unstable_book` write the three kinds of found
  Descriptive Book (`FoundBookKind`, named by `write_found_book`'s `kind`), and `name` draws an Age's syllables. A weighted context-free grammar
  read forwards, and it shares no machinery with the reader on purpose. `BookCheck` runs every book grammar's
  output through the whole pipeline against the rules in `FoundBookRules` — the unstable one must come out
  flawed and the advanced one must use a modifier — and fuzzes 2000 random page rows beside it. A book
  naming the pack's own worldgen (`algae`, `torchflowers`) is skipped offline, where those are not words
  (`Vocabulary.awaitsAServer`), and judged by the same rules in `BookOnServerCheck` through `/age books json`.
  `/age book [<kind>] [<seed>]` reads one back.
- **`Instability`** — how far an Age is at odds with itself, in four registers, each `Flaw` naming the words,
  slot and tags involved. Provenance is the point: a flaw has to be diagnosable, and §5's consequences read
  this long after the book was written. Part of the recipe.
- **`AgeCommand`** — the `/age` Brigadier tree (vanilla, so it's in `common`), in `co.voik.agesandtheart.command` along with everything that builds it; the operator tools, kept permanently now that books have superseded them in play. `/age write <name> [seed] <words…>` authors an Age from a sentence and `/age words` lists the vocabulary. **The instruments are `AgeInstruments`, added to the same tree only when `Services.PLATFORM.isDevelopment`** — true for `runServer`, the server checks and Scrivener's server, false in a shipped jar. It is only the *list* of them now: one file per family (`CorpusInstruments`, `SkyInstruments`, `TerrainInstruments`, `PhenomenonInstruments`, `ConsequenceInstruments`, `PlayerInstruments`, `RewardInstruments`, `LootInstruments`, `LightInstruments`), each owning its own constants and helpers, where all twenty-six used to share one object and one block of seventy-odd constants. What they genuinely share is named rather than reached for through the command tree: `CommandArguments` carries the argument names, Brigadier's result codes, `reporting` and the two ways of naming an Age, and `Measurements` the units and readouts more than one family says a number with. The debug affordances are among them: `/age bind <name>` hands you a Descriptive Book already bound to an Age that exists (the only way to look at a *chosen* one through a panel), `/age weather` sets the weather of the Age you stand in, and `/age pages` hands out a notebook of every word while `/age forget` unlearns them. `/age compare <a> <b>` generates two Ages and diffs them block for block — write two with the same seed to check a recipe reproduces. The instruments are worth knowing before building another: `/age tags` says what a tag actually carries on a server, `/age spawns` what an Age offers a spawn attempt, `/age instability [<name>]` everything about an Age coming apart, the one stood in unless another is named — what its index drew and what shows now, a manifestation forced to any step count or the pull set outright until a restart (`force <manifestation> <steps>`, `gravity <pull>`, `clear`), its index, age and a deluge's rain set by hand (`index`, `age`, `rained`), how holed it is (`wounds`) and what it and the ground underfoot are worth (`danger`), `/age loot` opens every container pages are put in through vanilla's own tables and says how often each held one, `/age fenced` opens every chest table and names any word on a page or in a notebook that no found page may carry, `/age containers <radius>` counts the unopened containers around you by table and what of ours they would hold — with `/place structure`, or in an Age written for a city, what a structure really gives, `/age showing` what is being drawn, `/age scarab` brings a scarab in by the same placement a natural arrival takes, `/age tide` runs a tide pinned at a stage or on the moons, `/age paper_tree` reads a paper tree's heart and the Age's window, and `/age lightflood <pos>` floods the light at a block as coloured light does and compares it with the server's own light engine block for block.

Reload trigger is loader-specific: Fabric's `SERVER_STARTED` event and NeoForge's `ServerStartedEvent` both call `Ages.reloadSaved`. A level must be opened on the server thread (commands and lifecycle events already are).

`Ages.attach()` and `Skies.attach()` hang the per-Age work off `RuntimeLevelEvents.whenOpened` rather than off each call site, so writing an Age, linking to one and replaying the saved list on boot all end in the same place.

## Ephemeris

**Runtime dimensions are `co.voik.ephemeris`, a separate mod in a separate project at `../ephemeris`.** It
is included as a Gradle **composite build** (`includeBuild` in `settings.gradle.kts`), so editing it
rebuilds it here and neither project needs the other to build alone. Two things about that wiring are
easy to get wrong and were:

- **The dependency substitutions must be spelled out.** Gradle's automatic ones come from each included
  project's `group:name` (`co.voik.ephemeris:common`), not from the artifact coordinates the catalog names.
- **Declaring any capability on a project drops Gradle's implicit `group:name`.** `multiloader-common`
  declares several, so it restates the implicit one too — without it, an ordinary dependency filters out
  every real variant and fails on Dokka's internal ones, in an error naming neither cause nor cure.

Ephemeris ships **as a mod, declared as a dependency** rather than bundled: on NeoForge a nested plain
library cannot see the Kotlin standard library KFF provides. The two manifests do not share a version
grammar — NeoForge parses Maven ranges, Fabric its own semver predicates — which is why the catalog carries
`ephemerisRangeNeoForge` and `ephemerisRangeFabric`.

**Mod identity lives in `mod.properties`, not `gradle.properties`.** Gradle reads `gradle.properties` from
the build root and a project's own directory and nowhere else — never a parent project — so
`multiloader-common` walks up to the nearest `mod.properties`. This survives here even though this build
now carries one mod again, because the convention plugin is shared with Ephemeris.

Do not add `co.voik.agesandtheart` references to Ephemeris; that separation is the whole of what makes it
givable away. What it owns and what we own is in its README.

## Conventions

- **Versions live in `libs.versions.toml`** (Gradle version catalog) — the single source of truth. Change dependency/loader versions there, not in module build files.
- **The mod bundles nothing, and adding the first bundled library again is a solved problem.** ANTLR was the only one and it left with the parser; the wiring is gone from the build files, so the recipe is here instead. Fabric needs `implementation` + `include` (Loom synthesises a `fabric.mod.json` for the nested jar itself); NeoForge needs it **twice** — `implementation`, and `jarJar` with a **version range** (never a pin, or jar-in-jar cannot pick one copy when two mods bundle it). `additionalRuntimeClasspath` is not a third declaration: it was a 1.21.1 workaround, and NeoForge fixed nested-artifact loading in 1.21.9. **Prefer a library with no dependencies of its own.** A Kotlin library is the hard case: KFF supplies the stdlib as a _mod_, which lives in NeoForge's game module layer where an ordinary library cannot see it, so `kotlin.Pair` goes missing at runtime and `FMLModType` does not rescue it.
- **Widening vanilla access takes two files, both in `common`.** `common/src/main/resources/agesandtheart.accesswidener` (Fabric/Loom) and `common/src/main/resources/META-INF/accesstransformer.cfg` (NeoForge/MDG) must be kept in step — `common` itself compiles against the **AT**, so that is the one that decides whether shared code even builds. Prefer composing vanilla's public API; widen only with a comment saying what it buys. Note `javap` misreports nested-type visibility (the real modifier lives in the outer class's `InnerClasses` attribute) and **decompiled sources drop `final` from class declarations** — trust the compiler, not the sources.
- **Screens are composed from `client/ui/`, never hand-drawn.** The model is Flutter-shaped and deliberately thin over vanilla:
  - **Vanilla owns arrangement.** `GridLayout`, `LinearLayout`, `FrameLayout` and `LayoutSettings` (which already carries padding _and_ alignment) do the positioning. Do not write a layout engine — the one place ours was needed, standalone padding on a decorated box, is `Insets`.
  - **We own decoration**, the one concept vanilla's GUI lacks. A `Decoration` draws into a rectangle (`PanelSurface`, `SlotSurface`, `ParchmentSurface`); `DecorationWidget` makes one renderable; `DecoratedBox` is a `Layout` that puts one behind a child. It visits its surface **before** its child, so `visitWidgets(::addRenderableWidget)` stacks them correctly with no separate background pass.
  - **A thing brings its own appearance.** `SlotView` is a slot _and_ its recess, because an empty slot's recess is not decoration applied to a slot — it is what one looks like. Don't re-split these.
  - **Add widgets back-to-front in one list.** Render order is insertion order; there should be no second drawing hook to keep in step. Decorative widgets return `isMouseOver = false` so they never shadow a click meant for what they sit behind.
  - **Never state a position twice.** A rectangle used for drawing and re-derived for hit-testing is the defect this layer exists to prevent, and it caused every bug the desk screen shipped. Slot positions live in `desk/DeskSlots.kt` because the menu needs them too and cannot see client code. Prefer deriving a size (the wing's 46×124 falls out of its contents) over declaring it.
  - Two traps, both already paid for: `AbstractContainerWidget` routes clicks and scrolls straight to its children **without consulting its own `visible` flag**, so a hidden list still answers them unless the guards in `LabelledList` are copied; and switching tabs must toggle `visible` rather than rebuild widgets, because a rebuild mutates the widget list that the dispatching click is iterating.
- **Mod identity lives in `gradle.properties`** (`modId`, `modName`, `group`, `version`, `license`, etc.). Metadata files (`fabric.mod.json`, `neoforge.mods.toml`, `pack.mcmeta`, `*.mixins.json`) are **templated**: their `${...}` placeholders are filled at build time by `processResources` (see `buildSrc/.../multiloader-common.gradle`). Edit identity/versions in `gradle.properties` + the catalog, not by hand in the manifests.
- Shared build logic is in `buildSrc/` convention plugins (`multiloader-common`, `multiloader-loader`); per-module `build.gradle.kts` files stay small.
- **A D'ni name lives in `lang/`, never in an id — and that is the only reason to split the two.** Where a
  thing's display name is D'ni, its id says what the thing _is_ instead: `pitchstone` shows as "Deretheni",
  `paper_tree` as "Yema", `masterwork_ink` as "D'ni Ink". **That split is a swap-out seam and exists for
  exactly one purpose**: if the setting ever has to go, it should cost a language file and not a migration.
  Words of the Art get it free through `WordNames.readable`, whose `word.<namespace>.<path>` key is what a
  page actually shows, so a player reads "Yema" off a word called `paper_tree_log`.
  **Everywhere else, an id and its display name may simply be the same** (Jonah, 2026-09-07), and several
  are: `temperstone`, `rime_crystal`, `astrite`. A name we invented wholesale carries no D'ni to strip out,
  so there is nothing for the seam to protect and inventing a second name for the id buys nothing.
  Comments and design notes are exempt and should use the lore name, because that is where the reasoning
  lives.
- Use `Constants.LOG` (SLF4J) for logging and `Constants.MOD_ID` as the namespace. `Util.kt` provides `String.location()` to build `agesandtheart:<path>` `ResourceLocation`s.

## Kotlin style

The overriding goal is **readability** — a reader should understand code without a decoder ring, and large sections should read almost like English. These rules are enforceable; follow them and flag any deliberate deviation with a local comment.

**TS reader's map:** `val`≈`const`, `var`≈`let`, `List`≈`readonly T[]`, `MutableList`≈`T[]`, `?.`/`?:`≈`?.`/`??`, `data class`≈typed record, `when`≈powerful `switch`. Null-safety is compiler-enforced — lean on it. (We're on **Kotlin 2.4.0**: `..<` ranges and `when` _guard conditions_ (2.2+) are both available.)

**Naming**

- Full words, no abbreviations: `blockPosition` not `bp`, `surfaceY` not `y`, `buffer` not `buf`. Single letters only for `it` in a trivial lambda or a genuine math axis.
- UpperCamelCase types; lowerCamelCase functions/properties/locals; **SCREAMING_SNAKE_CASE** for `const val` and `object`/top-level `val` constants.
- Booleans read as predicates (`isSupported`, `hasSkyLight`, `canReach`). Functions are verbs, properties are nouns — property access must be cheap and side-effect-free.
- Never name a file/class `Util`/`Helper`/`Manager`/`Misc` for _new_ code (existing `AgeManager`/`Util.kt` are grandfathered; don't add to the pattern). Multi-declaration files get a descriptive name (`Rgba.kt`).
- **Prefer slightly verbose and unambiguous names over clever and compact ones.** A cute name passes review because its author still holds the metaphor in their head; the cost lands later on someone who doesn't. `capabilityBonus` not `capability` when it returns a score; `climatePointsFromOtherPresets` not `climatesElsewhere`; `sawADressingThatIgnoresMaterials` not `reachedAListeningOne`. Extra characters are cheap; a re-read is not.
- **Never use a term naming a real population as a metaphor for inability.** "A preset _deaf to_ a parameter" became "a preset that _ignores_ a parameter". The domain usually already has the neutral verb — here `ignoresMaterial`/`ignoresClimate` were sitting right there.

**Immutability**

- `val` unless a `var` is provably required. Compute a value once with an `if`/`when` expression instead of reassigning a `var` across branches.
- Read-only collection types (`List`/`Set`/`Map`) built with `listOf`/`setOf`/`mapOf`; use `Mutable*` only where you actually mutate, kept as local as possible. Expose read-only, back with a private `mutableListOf` if needed.
- `const val` for compile-time constants; **name every magic number/string** (`OPERATOR_PERMISSION_LEVEL = 2`, not a bare `2`).

**Functions & purity**

- Prefer **pure functions** (output depends only on input, no side effects) for calculation — they're unit-testable without a running server. Keep world/entity mutation in thin, clearly-named functions at the edges.
- Single-expression functions use expression bodies (`fun area(w: Int, h: Int) = w * h`); state return types on public API.
- Return values instead of mutating parameters. Extract named helpers over inline comments — a well-named call _is_ the comment. No giant imperative functions.
- **Build complex booleans from named intermediates**, so each piece reads on its own. This applies **first and foremost to ordinary conditionals** — every `if`, `return` and `while` whose condition has more than one clause:

  ```kotlin
  // Not this — you have to decode it before you can judge it:
  fun accepts(option: String): Boolean =
      option in options || (open && namesReferent(option) && ResourceLocation.tryParse(option) != null)

  // This — each clause says what it means, and the last line reads as the rule:
  fun accepts(option: String): Boolean {
      val isOneOfTheNamedOptions = option in options
      val looksLikeARegistryId = namesReferent(option) && ResourceLocation.tryParse(option) != null
      return isOneOfTheNamedOptions || (open && looksLikeARegistryId)
  }
  ```

  It applies equally to **lambdas** passed to `filter`/`none`/`any`/`count`/`sortedBy`, where nesting is the usual culprit — give each level a named local function:

  ```kotlin
  // Not this — three levels to hold in your head at once:
  setting.filter { word -> word.sets.keys.none { name -> seated.any { it.honoursParameterNamed(name) } } }

  // This:
  fun anythingSeatedHonours(parameter: String) = seated.any { it.honoursParameterNamed(parameter) }
  val wentUnheeded = setting.filter { word -> word.sets.keys.none(::anythingSeatedHonours) }
  ```

  **Be judicious** — a single-clause condition (`if (chosen.isEmpty())`) or a short lambda (`filter { it.slot == slot }`) is already readable, and naming it only adds noise.

- Default arguments over overloads; named arguments when passing multiple same-typed/boolean args.

**Control flow**

- `if`/`when`/`try` are expressions — assign or return them. `if` for two branches, `when` for 3+.
- Exhaustive `when` over `when` + `else` on sealed types/enums, so a new case breaks the build.
- `..<` for exclusive ranges (`0..<size`), never `0..n - 1`. String templates over `+`.

**Null-safety**

- **Never `!!`.** Use `?:` (default / `?: return` / `?: error("why")`) or `requireNotNull(x) { "why" }`. Treat Java/MC return values as nullable until proven otherwise, and resolve nullability at the boundary.
- Compare nullable booleans explicitly (`if (flag == true)`).

**Data modeling**

- `data class` for anything holding data (all-`val` unless mutation is required); `sealed`/`enum` for closed hierarchies (pairs with exhaustive `when`); `object` for stateless singletons and pure-function registries; `@JvmInline value class` for typed ids/units.

**Comments describe the code, and nothing else.** Keep comments brief, concise, and only use them to explain non-obvious code that can't be simplified another way. Do not use comments to expound at length on design decisions or the history of the code. Commit logs serve that purpose, and key design points that will guide future code go in the various notes documents. Comments are only, exclusively for explaining code that is not easily readable on first glance, and should only talk about the code in question. Do not editorialise.

Concretely: no `##` headings inside a KDoc, no changelogs, no "this used to be X and Y changed it", no
dates or attributions. Prefer no KDoc at all where the name already says it — `/** The shape of the
rock. */` on `TERRAIN` earns nothing. A `(design §5.4)` pointer is worth keeping where the design
genuinely constrains the code, and worth dropping from ordinary description.

**Scope functions** — by intent, never nested, never chained >2 deep: `apply` (configure & return), `also` (side effect in a chain), `let` (null-guard/transform), `run`/`with` (configure & compute). If a block grows past a few lines, extract a named function.

**Anti-patterns to avoid** (common in mod code): `!!`; `lateinit` abuse (prefer `val` + constructor or `by lazy`); companion-object soup; **mutable global state** in `object`s/companions; magic numbers; deeply nested scope-function chains; `MutableList` leaking through public API; `when` + `else` on sealed/enum types silently swallowing new cases.

**Save compatibility is a constraint (private beta has begun, Jonah, 2026-10-16).** Saves, books, notebooks and learned words exist in testers' hands, so **a change must keep what is already saved readable**: a renamed or merged word goes in `art/retired.json` (old name → new, read by `Vocabulary.word` and migrated out of a player's learned set on join) rather than being deleted; a codec change reads the old shape; a renamed slot key keeps its old one as an alias. A saved recipe must also round-trip *within* a version (`RecipeCheck`). `CURRENT_GENERATOR_VERSION` still bumps once between releases (memory: generator-bump-once-per-release), and a bump is what lets a changed generator leave existing Ages alone. `notes/before-release.md` §1 lists the candidates that would now cost a migration.

**When to break the rules:** hot per-tick loops may justify a plain `for`, a `var` accumulator, or primitive arrays (measure first, comment why); Java/MC interop forces platform types and mutable builders (contain them at the boundary). Immutability and functional style are defaults, not religion — but a break should be **local and commented**, never the ambient style.

## Domain constraint to keep in mind

Minecraft registries (items, blocks, **dimensions**, …) freeze after server startup — content cannot be added mid-game through normal registration. The mod's core feature (authoring dimensions at runtime) works around this through Ephemeris, and the persistence model is ours: store each Age's recipe/id as data and re-create the dimension on load rather than registering it permanently. Design new "Age" state as replayable data, not as registered objects.

(History: DynamicDimensions was the original cross-loader pick and went dormant; Fantasy replaced it and is Fabric-only and LGPL, so its source could not be borrowed. Owning the technique turned out to cost four access-widener lines and one Mixin — `notes/neoforge-dimensions-research.md` has the reckoning.)
