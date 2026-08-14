# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Design documents

`notes/` holds the design record, and it is **normative** — where code and these documents disagree,
that is a bug in one of them, so revise the doc rather than letting the code drift away from it. Read the
relevant one before working in its area; most of the value is in the _reasoning_, not the conclusions.

**Unbuilt work keeps its full reasoning; finished work is compressed to two lines in `decisions.md`.** When
something lands, move it — do not leave the narrative of building it behind, and do not let a document
correct itself in place. Rewrite the paragraph that is now wrong.

- **`notes/the-world-model.md`** — **read this first, and before anything under `age/`.** What a world is
  made of (properties holding a value, a weighted set or a cast), what a word is, how a book resolves, and
  what a word costs. Derived from scratch 2026-08-12 and **not built** — it supersedes `the-art-design.md`
  §3 and §4, and the code still implements those. Its §10 maps the old concepts onto the new ones.
- **`notes/the-art-design.md`** — "the Art": the books, the language, aspects and tags, consequences,
  book editing, the economy. Still normative for §5 (consequence), §6 (book editing), §7 (the economy) and
  §8 (learning); **§3 and §4 are superseded** by the world model above.
- **`notes/the-art-implementation-plan.md`** — the phases and what each has to prove. Phases 1–4.5 are
  done and are a status line each; Phase 4's remainder and Phases 5–8 carry their full context.
- **`notes/the-tag-layer.md`** — the thirty-nine tags, where each is derived from, and why three separate
  things are called rarity. The implementation of the world model's §7, and **not built**. Read it before
  touching `art/preset_tags/`, adding a tag, or authoring a word that queries one.
- **`notes/terrain-architecture.md`** — the two-tier terrain system (composable field toolkit + bespoke
  presets): the evaluation contract, where things live, the performance budget. Built and shipped.
- **`notes/decisions.md`** — the compact ledger of settled decisions and hard-won learnings, one line each
  for the decision and the reason. Read it before re-proposing anything; several entries exist because an
  idea was tried and collapsed.
- **`notes/generator-versions.md`** — what moved at each `CURRENT_GENERATOR_VERSION` bump, and which Ages
  it moved. Read it when bumping the stamp, and add a row.
- **`notes/per-age-skies-research.md`** — the sky renderer's reference: render-state traps and what a
  per-Age sky cannot change. Read §3 before touching the renderer. **Its API details were verified against
  the 1.21.1 jar** and the renderer was rebuilt for 26.1 during the upgrade — so trust its _conclusions_
  about what a sky can and cannot do, and re-check any signature it quotes.
- **`notes/version-upgrade.md`** — the 1.21.1 → 26.1.2 move: the dependency matrix, what the build chain
  cost, and the two 26.1 subsystems (retained-mode GUI, model-based fluids) that changed what is worth
  building. Read it before assuming any pre-upgrade note still holds.
- **`notes/ui-libraries-research.md`** — why the screens are vanilla widgets and not a UI framework, and
  the component layer that decision implies. Read it before proposing a library or hand-drawing a screen.
- **`notes/minecraft-ui-conventions.md`** — what a vanilla screen actually looks like, measured off the
  26.1.2 jar: the bevel rule, the border widths, the 18-pixel grid. There is no official style guide, which
  is why this exists. Read it before drawing a control.
- **`notes/corruption-research.md`** — what 26.1 allows for a proximity gradient around a wound, and why
  positional environment layers beat a post-processing chain. Read it before reaching for a post effect.
- **`notes/water-colour-research.md`** — how to make an Age's water shift colour over time: the tint is baked
  into the chunk mesh, and 26.1's `GameTime` UBO is the way around that. Nothing is built; it is meant to be
  built alongside the wound renderer, which needs the same pipeline.
- **`notes/link-panel-research.md`** — the live view of an Age on a bound book's panel: why a preview
  `ClientLevel` beats a hand-written mesh builder, and the refactor it demands first — our sky and cloud
  hooks read `Minecraft.getInstance().level` and the main render target, and a second level breaks both.
  Nothing is built. The three files it names moved to Ephemeris with the renderer, so read it before
  touching the sky or cloud hooks **there** — the refactor it demands is now that project's to make.
- **`notes/neoforge-dimensions-research.md`** — the record of how runtime Ages stopped being Fabric-only.
  DynamicDimensions is dormant with no 26.1 and Fantasy is LGPL against our MIT, so neither could be used;
  what it cost to own the technique instead was four access-widener lines and one Mixin, not the ~10 into
  server internals this note first estimated. **Read it before looking at either library again.**
- **`notes/config-research.md`** — how mod config UIs work (they introspect a spec, or host a screen you
  write), the 26.1 landscape, and why the recommendation is NeoForge's `ModConfigSpec` with Forge Config API
  Port on Fabric. Nothing in it is built. Read it before adding the first config value.
- **`notes/walk-checklist.md`** — **the one file here that is not normative**: the in-game walk currently in
  progress, ticked off as it goes. Read it to find out what has been seen working and what has only been
  checked offline, and **update it when a walk turns something up or a fix lands**. It is deleted when the
  walk is done, and anything learned from it moves to `decisions.md` or a design doc.
- **`notes/authoring-tools.md`** — how structures and 3D models get authored: the external tooling and its
  version state, and what our own datapacks could carry that they do not yet. Nothing in it is built. Read it
  before building a structure, a model, or anything that wants to be pack data.

## What this is

**Ages and the Art** — a Minecraft mod (Mystcraft-inspired: author dimensional "Ages" from written Symbol pages, link between them) for **Minecraft 26.1.2**, built as a **multiloader** mod running on both **Fabric** and **NeoForge** from one codebase. Mod id `agesandtheart`, root package `co.voik.agesandtheart`.

Current state: Phases 1–4.5 are done and Phase 5 (the playable slice) is in progress. The Art's language, grammar, resolver and terrain system are built and checked; `/age write` authors an Age from a sentence. Phase 5 has added word pages, the notebook, the writer's desk with its screen, descriptive and linking books, the book entity, and the two acquaintance devices that put the derived corpus within reach. **Runtime dimensions work on both loaders**, on **Ephemeris** — our own library, now its own project at `../ephemeris` (see "Ephemeris" below).

**26.1 is the first unobfuscated Minecraft release**, which is why there is no Parchment in the catalog: 1.21.11 was the last obfuscated one and there is nothing left to deobfuscate. Mappings-related advice written for the 1.21 line does not transfer.

## Requirements

Java 25 (Temurin, via SDKMAN). Gradle comes from the wrapper — always use `./gradlew`, never a system Gradle. Note that the Java level is real: Dokka 1.x cannot parse a "25.x" version string and fails before reading a line of source, which is why the catalog pins Dokka 2.

**Non-interactive shell gotcha:** SDKMAN's init lives in `~/.bashrc` and may not be sourced in non-login shells, so `java` can be missing from `PATH`. Before running Gradle, ensure Java is available, e.g.:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"; export PATH="$JAVA_HOME/bin:$PATH"
```

## Commands

```bash
# Build + remap both loaders (produces */build/libs/agesandtheart-<loader>-26.1.2-<version>.jar)
./gradlew build

# Launch the game in a dev sandbox (requires a graphical display)
./gradlew :fabric:runClient
./gradlew :neoforge:runClient

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

# The landform shape checks — opt-in, and they *warn* rather than fail (see "Tests")
./gradlew :common:landformTest

# Drive a headless server through a list of /age commands and read the output (see scripts/checks/)
scripts/drive-server.sh scripts/checks/regions.txt
```

**Server checks are `./gradlew :common:serverTest`, and they own their own acceptance.** A Kotest spec
tagged `NEEDS_SERVER` uses `DrivenServer` (in `common/src/test/kotlin/.../server/`) to boot a dedicated
server, drive it over **RCON**, and assert on what comes back. The concerns are split on purpose: the
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
  name and location so it can never reach a world a person plays.

**`scripts/drive-server.sh` remains, as a driver only.** It runs a list of `/age` commands against a
server and prints what they say, for the exploratory files that are meant to be _read_ — `aspects.txt`,
`regions.txt`, `generator-parity.txt`. Its old `#?` assertion layer is gone (`#?` lines are skipped so old
files still drive): it read the first integer on the matching line, which on a real server log is the
hour off the timestamp, so `at-least 100` could never pass and `at-most 2000` could never fail.

Run directories are `runs/` (Fabric) and `run/` (NeoForge), both git-ignored. The first build/run downloads Minecraft, mappings, and the loader toolchains — slow once, then cached.

**Tests:** **Kotest**, in three tasks split by what they cost and what they are worth.

```bash
./gradlew :common:test                     # the loop — everything but landforms and the server, ~17s
./gradlew :common:test -Pfast              # and without the specs that need Minecraft's registries
./gradlew :common:test --tests "*Grammar*" # one spec
./gradlew :common:serverTest               # boots a real server, drives it over RCON, ~4min
./gradlew :common:serverTest -Pfast        # ...without the two specs that *are* the runtime, ~25s
./gradlew :common:landformTest             # the shape of the rock — opt-in, ~220s, and it only *warns*
```

**`-Pon=<feature>` narrows any of them to one subject**, which is the iteration loop worth using:

```bash
./gradlew :common:test -Pon=sky            # ~1.5s instead of 17
./gradlew :common:serverTest -Pon=sky      # the sky checks that need a server, and nothing else
```

Features are `aspects consequence desk levels phenomena sky terrain words`, and the map lives in
`common/build.gradle.kts`. **They are named after where the specs already live**, because the packages
already mirror the code they check — so this is a name for a directory rather than a second taxonomy to
keep in step. A feature with nothing in a given task runs nothing rather than failing, so `-Pon=desk` is a
fair thing to say to `serverTest`. A misspelled one lists what it should have been.

**The server suite is two specs and a rounding error**, measured 2026-08-10: of 167 seconds, `TempestCheck`
was 87 and `DeletionCheck` 62, and the other eight came to **two seconds between them**. Both earn it — one
waits out real weather, the other builds and destroys whole worlds — so they carry `NEEDS_TIME` and `-Pfast`
drops them, which is four minutes down to twenty-five seconds while still running eight tenths of the suite.
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

**There are four services**, each with that same set of four files: **`Platform`** (environment questions), **`AgeBackend`** (runtime dimensions), **`Network`** (payload sending) and **`InkFluids`** (the fluid the loaders model completely differently). To add a platform-divergent capability, add a method to whichever of the four owns the concern. Only introduce a _fifth_ when it is a genuinely distinct service rather than a one-off need — `InkFluids` earns it because Fabric's fluid API and NeoForge's share no types at all.

**3. Entrypoints differ per loader; both funnel into `common`.**

- Fabric: `fabric.mod.json` `entrypoints.main` → `co.voik.agesandtheart.AgesAndTheArtKt::init` (a top-level `fun init()` in `fabric/.../AgesAndTheArt.kt`), using the `kotlin` adapter (Fabric Language Kotlin).
- NeoForge: `@Mod("agesandtheart")` on the class in `neoforge/.../AgesAndTheArt.kt`; its constructor runs (Kotlin for Forge provides the Kotlin entry).
  Both immediately call `CommonSetup.init()`. Keep loader entrypoints tiny; put logic in `common`.

**4. Four Mixins, all in `common`, all Java.**
`common/src/main/resources/agesandtheart.mixins.json` declares them, and each earned its place by there being no loader event that carries what it needs. Each carries its own argument in-file; read that before touching one.

- **`ServerPlayerMixin`** — the learned-word set. Four injectors: `readAdditionalSaveData` / `addAdditionalSaveData` persist it, `restoreFrom` carries it through death, and `initMenu` attaches the `ContainerListener` that notices a page arriving in the inventory. That last one is vanilla's own `inventory_changed` seam, which is why it beats polling.
- **`ServerLevelMixin`** — local difficulty near a wound (§5.1). No event exists on either loader: difficulty is computed on demand and returned by value, so this one method is the only place it exists.
- **`LightningBoltMixin`** — a bolt landing in a tempest. The entity-join event would fire re-entrantly inside `addFreshEntity` and cannot see the private `visualOnly` flag that marks a trap's harmless bolt.
- **`client/LevelRendererMixin`** — draws the Age's wounds in one submission. Declared under the config's `"client"` array, not `"mixins"`. The loader alternatives exist here (Fabric's world-render events, NeoForge's `RenderLevelStageEvent`) and are declined deliberately: they are different objects with different stages where the vanilla seam is identical on both sides.

The sky Mixins left with Ephemeris and are `co.voik.ephemeris.mixin.client.*` now — do not look for them here.

**Always prefer a loader event or vanilla API over a Mixin when one exists**, and say in the commit which alternatives were checked. Mixins are written in **Java** (Kotlin isn't viable). On **Loom 1.17** the mixin annotation processor / refmap is off by default and 26.1 is unobfuscated anyway — do **not** re-add a `loom { mixin { … } }` block.

## Ages / runtime dimensions

The core mechanic — creating dimensions ("Ages") at runtime and persisting them — lives in `common/.../age/`, with the one loader-specific piece behind the `AgeBackend` service:

- **`AgeRecipe`** — **what an Age is, as data**: the world it was written from (a composition of slot presets, or one of the few bespoke generators), its seed, the character drawn for it, the instability and words it was written with, and the generator version that made it. Codec-serialised, and the _only_ record of an Age — the dimension is rebuilt from it on every open. `AgePreset` names the generation presets; its `key` is the save format, so renaming one orphans every Age already written with it (`RecipeCheck` guards this).
- **`AgeGeneration`** — turns a recipe into a `ChunkGenerator`, in an exhaustive `when` over `AgePreset`. A pure function of the recipe (plus the server, for registries), because an Age must rebuild identically on every open.
- **`AgeSavedData`** — vanilla `SavedData` on the overworld's data storage, persisting each Age's recipe. Runtime-dimension libraries do **not** auto-restore dimensions on restart, so we track them ourselves. Reads the pre-recipe format (an id list plus generator-kind strings) and migrates it.
- **`Ages`** — loader-agnostic policy: `create` / `open` / `ensure` / `delete` (delegating to `Services.AGE_BACKEND`) and `reloadSaved` (replay on boot).
- **`age/word/`** — **the Art's language.** `Word` (tier, the slots it may fill, a signed tag query),
  `PresetProfile`/`PresetTags` (what the world is like), `Vocabulary` (the corpus, **loaded from datapack
  JSON** under `data/<namespace>/art/` — words, domains, per-slot tag tables, antonym pages, structural words), and
  `Resolver` (a parsed sentence + seed → composition, cost and instability). `DerivedWords` gives **every
  block and biome in the pack a word of its own**, so the corpus is ~1100 offline and more on a server.
  Resolution is a **pure function of (vocabulary, sentence, seed)**; the resolved composition is what
  persists, never the words (design §4.6).
- **`age/word/grammar/`** — **the parser**, and a boundary worth respecting. `Grammar.read(vocabulary,
pages) → Sentence` is the entire port; `Sentence`/`Phrase`/`Constraint`/`Scope`/`Polarity`/`Group` are
  ours and carry no parser concepts, which is what lets checks build sentences by hand and lets the parser
  be replaced by rewriting one file. `ArtReading.kt` **is** that one file: recursive descent over a row of
  pages already looked up in the `Vocabulary` and stamped with a class, four productions long, and with no
  lexer because there is nothing left to lex. It names no aspect and no domain — which section admits which
  page is asked of the data (`Aspect.confinable`, `Aspect.madeOfSomething`, `Word.aspects`), so the
  player-facing division can be redrawn without touching it. **Every clause ends with the page it is about** and modifiers lead it —
  `pillars and hills landmass`, and a book with no aiming page closes with `age`. A clause is closed by an
  **aiming page** (`landmass`, `atmosphere`, `firmament`) and never by a word that fills something —
  presets are ours, not the player's. `Readout.of(sentence)` says the parse back as prose, which is how attachment is visible at
  all.
- **`Domain`** — **the player-facing division of the world, as datapack content** (`art/domain/<name>.json`).
  A domain names the aspects one aiming page opens, and `Vocabulary` synthesises the page from it, so a page
  and the parts it opens cannot disagree. `atmosphere` covers `{climate, atmosphere}` because a writer does
  not know that humidity and rainfall live in different objects; `firmament` covers `{sky}`. Never author an
  `art/word/<domain>.json` beside one — the load reports a collision if you do.

> **The design and the code disagree here on purpose, and the code is the side that moves.** Everything
> described above is accurate today and deliberately short-lived: `notes/the-world-model.md` replaces the
> whole of it, and **`Aspect`, `Domain`, `Scope` and one of the two `Kind` enums do not survive it**. A
> world becomes properties holding a value, a weighted set or a cast; aspects become ordinary words; there
> are no counts; and cost becomes specificity × versatility. **Read that document before changing anything
> under `age/aspect/` or `age/word/`**, and the implementation plan's "writer's-terms pass" for the order.
- **`age/word/generation/`** — the grammars the Art writes *out* of, which are **datapack content**
  (`art/generation/<name>.json`): `book` writes the found Descriptive Books a player learns structure from,
  `repair` writes the sentence a book that does not parse is filled into, `name` draws an Age's syllables. A
  weighted context-free grammar read forwards, and it shares no machinery with the parser on purpose.
  `BookCheck` runs `book`'s output through the whole pipeline and fuzzes 2000 random page rows beside it.
- **`Instability`** — how far an Age is at odds with itself, in six registers, each `Flaw` naming the words,
  slot and tags involved. Provenance is the point: a flaw has to be diagnosable, and §5's consequences read
  this long after the book was written. Part of the recipe.
- **`AgeCommand`** — the `/age` Brigadier tree (vanilla, so it's in `common`); the debug trigger until books exist. `/age write <name> [seed] <words…>` authors an Age from a sentence and `/age words` lists the vocabulary. `/age compare <a> <b>` generates two Ages and diffs them block for block — write two with the same seed to check a recipe reproduces.
- **`AgeBackend`** (service) — both halves call `RuntimeLevels.open` / `RuntimeLevels.delete` and are identical but for the class name. The service survives because the *policy* around a level (which recipe, which dimension type) is ours while opening one is Ephemeris'.

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
  - **We own decoration**, the one concept vanilla's GUI lacks. A `Decoration` draws into a rectangle (`PanelSurface`, `SlotSurface`, `ColourSurface`); `DecorationWidget` makes one renderable; `DecoratedBox` is a `Layout` that puts one behind a child. It visits its surface **before** its child, so `visitWidgets(::addRenderableWidget)` stacks them correctly with no separate background pass.
  - **A thing brings its own appearance.** `SlotView` is a slot _and_ its recess, because an empty slot's recess is not decoration applied to a slot — it is what one looks like. Don't re-split these.
  - **Add widgets back-to-front in one list.** Render order is insertion order; there should be no second drawing hook to keep in step. Decorative widgets return `isMouseOver = false` so they never shadow a click meant for what they sit behind.
  - **Never state a position twice.** A rectangle used for drawing and re-derived for hit-testing is the defect this layer exists to prevent, and it caused every bug the desk screen shipped. Slot positions live in `desk/DeskSlots.kt` because the menu needs them too and cannot see client code. Prefer deriving a size (the wing's 46×124 falls out of its contents) over declaring it.
  - Two traps, both already paid for: `AbstractContainerWidget` routes clicks and scrolls straight to its children **without consulting its own `visible` flag**, so a hidden list still answers them unless the guards in `LabelledList` are copied; and switching tabs must toggle `visible` rather than rebuild widgets, because a rebuild mutates the widget list that the dispatching click is iterating.
- **Mod identity lives in `gradle.properties`** (`modId`, `modName`, `group`, `version`, `license`, etc.). Metadata files (`fabric.mod.json`, `neoforge.mods.toml`, `pack.mcmeta`, `*.mixins.json`) are **templated**: their `${...}` placeholders are filled at build time by `processResources` (see `buildSrc/.../multiloader-common.gradle`). Edit identity/versions in `gradle.properties` + the catalog, not by hand in the manifests.
- Shared build logic is in `buildSrc/` convention plugins (`multiloader-common`, `multiloader-loader`); per-module `build.gradle.kts` files stay small.
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
rock. */` on `TERRAIN` earns nothing. A `(design §3.4)` pointer is worth keeping where the design
genuinely constrains the code, and worth dropping from ordinary description.

**Scope functions** — by intent, never nested, never chained >2 deep: `apply` (configure & return), `also` (side effect in a chain), `let` (null-guard/transform), `run`/`with` (configure & compute). If a block grows past a few lines, extract a named function.

**Anti-patterns to avoid** (common in mod code): `!!`; `lateinit` abuse (prefer `val` + constructor or `by lazy`); companion-object soup; **mutable global state** in `object`s/companions; magic numbers; deeply nested scope-function chains; `MutableList` leaking through public API; `when` + `else` on sealed/enum types silently swallowing new cases.

**Save compatibility — not yet a constraint (2026-07-27, revisit at first release).** The mod is still in initial development with no players and no saves worth keeping, so **renaming slot keys, changing codec shapes and bumping `generatorVersion` are all free** — say so and move on. Do _not_ add `FORMER_KEYS`-style alias tables, either-or codecs chosen purely to keep old files byte-identical, or treat "no version had to move" as a design goal; prefer the clearer shape and let test Ages break. Still true regardless: a recipe must round-trip _within_ a version (`RecipeCheck`), which is correctness rather than compatibility.

**When to break the rules:** hot per-tick loops may justify a plain `for`, a `var` accumulator, or primitive arrays (measure first, comment why); Java/MC interop forces platform types and mutable builders (contain them at the boundary). Immutability and functional style are defaults, not religion — but a break should be **local and commented**, never the ambient style.

## Domain constraint to keep in mind

Minecraft registries (items, blocks, **dimensions**, …) freeze after server startup — content cannot be added mid-game through normal registration. The mod's core feature (authoring dimensions at runtime) works around this through Ephemeris, and the persistence model is ours: store each Age's recipe/id as data and re-create the dimension on load rather than registering it permanently. Design new "Age" state as replayable data, not as registered objects.

(History: DynamicDimensions was the original cross-loader pick and went dormant; Fantasy replaced it and is Fabric-only and LGPL, so its source could not be borrowed. Owning the technique turned out to cost four access-widener lines and one Mixin — `notes/neoforge-dimensions-research.md` has the reckoning.)
