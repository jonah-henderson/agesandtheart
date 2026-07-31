# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Design documents

`notes/` holds the design record, and it is **normative** — where code and these documents disagree,
that is a bug in one of them, so revise the doc rather than letting the code drift away from it. Read the
relevant one before working in its area; most of the value is in the *reasoning*, not the conclusions.

**Unbuilt work keeps its full reasoning; finished work is compressed to two lines in `decisions.md`.** When
something lands, move it — do not leave the narrative of building it behind, and do not let a document
correct itself in place. Rewrite the paragraph that is now wrong.

- **`notes/the-art-design.md`** — "the Art": the books, the language, aspects and tags, consequences,
  book editing, the economy. The normative design, and the one document that is not compressed.
- **`notes/the-art-implementation-plan.md`** — the phases and what each has to prove. Phases 1–4.5 are
  done and are a status line each; Phase 4's remainder and Phases 5–8 carry their full context.
- **`notes/terrain-architecture.md`** — the two-tier terrain system (composable field toolkit + bespoke
  presets): the evaluation contract, where things live, the performance budget. Built and shipped.
- **`notes/decisions.md`** — the compact ledger of settled decisions and hard-won learnings, one line each
  for the decision and the reason. Read it before re-proposing anything; several entries exist because an
  idea was tried and collapsed.
- **`notes/generator-versions.md`** — what moved at each `CURRENT_GENERATOR_VERSION` bump, and which Ages
  it moved. Read it when bumping the stamp, and add a row.
- **`notes/per-age-skies-research.md`** — verified 1.21.1 reference for the sky renderer: the render-state
  traps and what a per-Age sky cannot change. Read §3 before touching the renderer.

## What this is

**Ages and the Art** — a Minecraft mod (Mystcraft-inspired: author dimensional "Ages" from written Symbol pages, link between them) for **Minecraft 1.21.1**, built as a **multiloader** mod running on both **Fabric** and **NeoForge** from one codebase. Mod id `agesandtheart`, root package `co.voik.agesandtheart`.

Current state: a working **runtime-dimension spike** exists (see "Ages / runtime dimensions" below) — `/age create|tp|list` authors a persistent dimension that survives restart. It's **command-driven and Fabric-only** for now; the player-facing books, the symbol grammar, and a NeoForge backend are still to come. The rest is template scaffolding (a demo title-screen Mixin, hello-world logging).

The version is pinned to 1.21.1. **That pin's original cause is gone** — it was Fantasy, which has since kept pace with Minecraft all the way to 26.2 — so what holds us here now is *our own* code: the sky renderer is a verified 1.21.1 reference, and `AgeChunkGenerator`'s access-widener/transformer lines, the surface rules and the structure-placement codecs are all version-shaped. Upgrading is a piece of work with the sky renderer as its acceptance test, not a version bump. See `notes/ui-libraries-research.md`.

## Requirements

Java 21 (Temurin, via SDKMAN). Gradle comes from the wrapper — always use `./gradlew`, never a system Gradle.

**Non-interactive shell gotcha:** SDKMAN's init lives in `~/.bashrc` and may not be sourced in non-login shells, so `java` can be missing from `PATH`. Before running Gradle, ensure Java is available, e.g.:
```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/current"; export PATH="$JAVA_HOME/bin:$PATH"
```

## Commands

```bash
# Build + remap both loaders (produces */build/libs/agesandtheart-<loader>-1.21.1-<version>.jar)
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

# Drive a headless server through a list of /age commands and read the output (see scripts/checks/)
scripts/drive-server.sh scripts/checks/regions.txt
```

**Server checks are `./gradlew :common:serverTest`, and they own their own acceptance.** A Kotest spec
tagged `NEEDS_SERVER` uses `DrivenServer` (in `common/src/test/kotlin/.../server/`) to boot a dedicated
server, drive it over **RCON**, and assert on what comes back. The concerns are split on purpose: the
driver starts, sends and stops, and *asserts nothing*; Kotest decides whether an answer is right, so a
failure carries a Power-Assert diagram rather than "nothing matched".

RCON is what makes that possible. `DedicatedServer.runCommand` is `prepareForCommand()` /
`executeBlocking(…)` / `getCommandResponse()`, so each command runs on the server thread, the call waits
for it, and the reply holds **that command's output and nothing else** — no barrier tokens, no slicing a
shared log, no timestamps to strip. Failures come back too (`RconConsoleSource.acceptsFailure()`).

Two things that follow, and both are load-bearing:

- **The commands can answer in JSON**, written `/age <subcommand> json …` (the literal goes straight after
  the subcommand because `/age write`'s sentence is greedy). Prose stays the default and is unchanged in
  game. A structured answer is *one* message, which matters because RCON concatenates a command's messages
  with no separator — so the buffer *is* the document. See `age/Report.kt`.
- **The server is started without Gradle.** These specs run inside a Gradle-launched JVM and a nested
  `./gradlew` would wait on the outer build's locks, so `:fabric:exportServerLaunch` writes the launch
  command down and `DrivenServer` starts the JVM itself. It writes to a fresh `checks-…` world, restores
  `server.properties`, and removes that world afterwards — the only deletion in the harness, fenced on the
  name and location so it can never reach a world a person plays.

**`scripts/drive-server.sh` remains, as a driver only.** It runs a list of `/age` commands against a
server and prints what they say, for the exploratory files that are meant to be *read* — `aspects.txt`,
`regions.txt`, `generator-parity.txt`. Its old `#?` assertion layer is gone (`#?` lines are skipped so old
files still drive): it read the first integer on the matching line, which on a real server log is the
hour off the timestamp, so `at-least 100` could never pass and `at-most 2000` could never fail.

Run directories are `runs/` (Fabric) and `run/` (NeoForge), both git-ignored. The first build/run downloads Minecraft, mappings, and the loader toolchains — slow once, then cached.

**Tests:** `:common:test` is the whole offline suite — **Kotest**, one task, ~135 tests in about 16 seconds.

```bash
./gradlew :common:test                    # everything
./gradlew :common:test -Pfast             # skip the specs that need Minecraft's registries (~5s)
./gradlew :common:test --tests "*Grammar*" # one spec
```

The specs live in `common/src/test/kotlin/`, in packages mirroring the code they check. They are the
former `preview` "check" instruments — same assertions, same hand-written failure messages, now discovered
and reported individually. Two things about how they are written:

- **Assertions are plain `check(condition) { "what went wrong" }`.** The Kotlin **Power-Assert** compiler
  plugin is on for the `test` source set only, so a failure prints that sentence *and* a diagram of every
  subexpression. Kotest's matchers are available and used where they read better, but `check` is the house
  style here because the messages were the point and they ported unchanged.
- **`@Tags(NEEDS_REGISTRIES)` marks a spec that needs `Bootstrap.bootStrap()`** — a few seconds, paid once
  per JVM, and the only slow thing in the suite. The annotation form matters: Kotest constructs a spec to
  discover its tests, so a fixture built in the constructor would be paid even under `-Pfast`. Anything
  expensive inside a spec should be `by lazy`.

Property-based tests use `kotest-property` (`checkAll`) — see `SpansCheck`, and note it generates the
*recipe* for a value rather than the value, because that is what shrinks and what prints legibly.

## Architecture

This is **not** Architectury. Platform abstraction is done with plain `java.util.ServiceLoader`; Fabric uses **Loom**, NeoForge uses **ModDevGradle (MDG)**. Understanding the project means understanding four cross-cutting mechanisms:

**1. The three modules and how `common` reaches the loaders.**
`common/` holds all real logic and compiles against vanilla Minecraft only (via MDG/NeoForm) — it must not reference Fabric or NeoForge types. `fabric/` and `neoforge/` are thin adapters. Critically, `common` is **not** consumed as a jar: `common/build.gradle.kts` exposes its sources through `commonJava`/`commonKotlin`/`commonResources` configurations, and `buildSrc/.../multiloader-loader.gradle` wires those into each loader's compile/resource tasks. Net effect: common source is compiled *into* each loader jar. There is no separate "common" mod to ship.

**2. The platform split (SPI pattern), spanning four files.**
When shared code needs something loader-specific, it goes through an interface, never a direct call:
- `common/.../platform/services/PlatformHelper.kt` — the interface
- `fabric/.../platform/FabricPlatformHelper.kt` / `neoforge/.../platform/NeoForgePlatformHelper.kt` — implementations
- `*/src/main/resources/META-INF/services/co.voik.agesandtheart.platform.services.PlatformHelper` — SPI registration (one line naming the impl)
- `common/.../platform/Services.kt` — `Services.PLATFORM` resolves the right impl at runtime

To add a new platform-divergent capability: add a method to `PlatformHelper`, implement it in both loader classes. Only introduce a *new* ServiceLoader interface when it's a genuinely distinct service — `AgeBackend` (runtime dimensions) is the established example: it's a second service loaded via `Services.AGE_BACKEND`, with `FabricAgeBackend`/`NeoForgeAgeBackend` impls and their own `META-INF/services` files. Don't fragment `PlatformHelper` for one-off needs.

**3. Entrypoints differ per loader; both funnel into `common`.**
- Fabric: `fabric.mod.json` `entrypoints.main` → `co.voik.agesandtheart.AgesAndTheArtKt::init` (a top-level `fun init()` in `fabric/.../AgesAndTheArt.kt`), using the `kotlin` adapter (Fabric Language Kotlin).
- NeoForge: `@Mod("agesandtheart")` on the class in `neoforge/.../AgesAndTheArt.kt`; its constructor runs (Kotlin for Forge provides the Kotlin entry).
Both immediately call `CommonSetup.init()`. Keep loader entrypoints tiny; put logic in `common`.

**4. No Mixins currently — add them Java-side only when needed.**
The mod has **no Mixins** right now; everything goes through Fabric API hooks + the `ServiceLoader` split, so the template's demo mixins were removed. **One is planned and already justified** — the Fabric side of an Age's selective spawn policy, because the natural-versus-player-directed distinction exists only as `MobSpawnType` at the spawn call site and no Fabric API event carries it (every module was checked). See the implementation plan, Phase 4.5. Do not treat that as licence: the rule below stands, and that exception earned its place by enumerating the alternatives and solving two thirds of the problem without one. If you genuinely must patch a vanilla class: Mixins are written in **Java** (Kotlin isn't viable), one `*.mixins.json` config per module referenced from each loader's metadata (`fabric.mod.json`, `neoforge.mods.toml`). On **Loom 1.13** the mixin annotation processor / refmap is off by default — do **not** re-add a `loom { mixin { … } }` block. Always prefer a loader event/API over a Mixin when one exists.

## Ages / runtime dimensions

The core mechanic — creating dimensions ("Ages") at runtime and persisting them — lives in `common/.../age/`, with the one loader-specific piece behind the `AgeBackend` service:

- **`AgeRecipe`** — **what an Age is, as data**: the world it was written from (a composition of slot presets, or one of the few bespoke generators), its seed, the character drawn for it, the instability and words it was written with, and the generator version that made it. Codec-serialised, and the *only* record of an Age — the dimension is rebuilt from it on every open. `AgePreset` names the generation presets; its `key` is the save format, so renaming one orphans every Age already written with it (`RecipeCheck` guards this).
- **`AgeGeneration`** — turns a recipe into a `ChunkGenerator`, in an exhaustive `when` over `AgePreset`. A pure function of the recipe (plus the server, for registries), because an Age must rebuild identically on every open.
- **`AgeSavedData`** — vanilla `SavedData` on the overworld's data storage, persisting each Age's recipe. Runtime-dimension libraries do **not** auto-restore dimensions on restart, so we track them ourselves. Reads the pre-recipe format (an id list plus generator-kind strings) and migrates it.
- **`Ages`** — loader-agnostic policy: `create` / `open` / `ensure` / `delete` (delegating to `Services.AGE_BACKEND`) and `reloadSaved` (replay on boot).
- **`age/word/`** — **the Art's language.** `Word` (tier, the slots it may fill, a signed tag query),
  `PresetProfile`/`PresetTags` (what the world is like), `Vocabulary` (the corpus, **loaded from datapack
  JSON** under `data/<namespace>/art/` — words, per-slot tag tables, antonym pages, structural words), and
  `Resolver` (a parsed sentence + seed → composition, cost and instability). `DerivedWords` gives **every
  block and biome in the pack a word of its own**, so the corpus is ~1100 offline and more on a server.
  Resolution is a **pure function of (vocabulary, sentence, seed)**; the resolved composition is what
  persists, never the words (design §4.6).
- **`age/word/grammar/`** — **the parser**, and a boundary worth respecting. `Grammar.read(vocabulary,
  pages) → Sentence` is the entire port; `Sentence`/`Phrase`/`Constraint`/`Scope`/`Polarity`/`Group` are
  ours and carry no parser concepts, which is what lets checks build sentences by hand and lets the parser
  be replaced by rewriting one file. **`ArtGrammar.kt` is the only file in the mod that may import
  `org.antlr`** — `GrammarCheck` fails the build if any other does. The grammar itself is
  `common/src/main/antlr/.../Art.g4`; it has **no lexer rules**, because the input is a list of pages
  already looked up in the `Vocabulary` and stamped with a class. A section is opened by an **aiming page**
  (`landmass`, `climate`, `sky`) and never by a word that fills something — presets are ours, not the
  player's. `Readout.of(sentence)` says the parse back as prose, which is how attachment is visible at all.
- **`age/word/BookGenerator.kt`** — the pipeline run backwards: a vocabulary and a seed in, a well-formed
  book out. Content (Phase 7's found books) and test harness (`BookCheck` fuzzes 2000 a run) in one build.
- **`Instability`** — how far an Age is at odds with itself, in four registers, each `Flaw` naming the words,
  slot and tags involved. Provenance is the point: a flaw has to be diagnosable, and §5's consequences read
  this long after the book was written. Part of the recipe.
- **`AgeCommand`** — the `/age` Brigadier tree (vanilla, so it's in `common`); the debug trigger until books exist. `/age write <name> [seed] <words…>` authors an Age from a sentence and `/age words` lists the vocabulary. `/age compare <a> <b>` generates two Ages and diffs them block for block — write two with the same seed to check a recipe reproduces.
- **`AgeBackend`** (service) — `FabricAgeBackend` implements it with **Fantasy** (`Fantasy.get(server).getOrOpenPersistentWorld(id, config)`); `NeoForgeAgeBackend` is an `isSupported = false` stub, so `/age create` on NeoForge reports "not supported yet" instead of crashing.

Reload trigger is loader-specific: Fabric's `SERVER_STARTED` event calls `Ages.reloadSaved`. Fantasy must be called on the server thread (commands and lifecycle events already are). **Fantasy is Fabric-only**, so all Fantasy references stay in the `fabric` module — never in `common`.

## Conventions

- **Versions live in `libs.versions.toml`** (Gradle version catalog) — the single source of truth. Change dependency/loader versions there, not in module build files.
- **Bundling a third-party library is a solved problem — copy the ANTLR wiring rather than inventing one.** It is the mod's only bundled dependency and the pattern is in the build files with the reasoning attached. In short: Fabric needs `implementation` + `include` (Loom synthesises a `fabric.mod.json` for the nested jar itself); NeoForge needs the dependency **three times** — `implementation`, `jarJar` with a **version range** (never a pin, or jar-in-jar cannot pick one copy when two mods bundle it), and `additionalRuntimeClasspath`, because on 1.21.1 it will not otherwise load in a run. **Prefer a library with no dependencies of its own.** A Kotlin library is the hard case: KFF supplies the stdlib as a *mod*, which lives in NeoForge's game module layer where an ordinary library cannot see it, so `kotlin.Pair` goes missing at runtime and `FMLModType` does not rescue it.
- **Widening vanilla access takes two files, both in `common`.** `common/src/main/resources/agesandtheart.accesswidener` (Fabric/Loom) and `common/src/main/resources/META-INF/accesstransformer.cfg` (NeoForge/MDG) must be kept in step — `common` itself compiles against the **AT**, so that is the one that decides whether shared code even builds. Prefer composing vanilla's public API; widen only with a comment saying what it buys. Note `javap` misreports nested-type visibility (the real modifier lives in the outer class's `InnerClasses` attribute) and **decompiled sources drop `final` from class declarations** — trust the compiler, not the sources.
- **Mod identity lives in `gradle.properties`** (`modId`, `modName`, `group`, `version`, `license`, etc.). Metadata files (`fabric.mod.json`, `neoforge.mods.toml`, `pack.mcmeta`, `*.mixins.json`) are **templated**: their `${...}` placeholders are filled at build time by `processResources` (see `buildSrc/.../multiloader-common.gradle`). Edit identity/versions in `gradle.properties` + the catalog, not by hand in the manifests.
- Shared build logic is in `buildSrc/` convention plugins (`multiloader-common`, `multiloader-loader`); per-module `build.gradle.kts` files stay small.
- Use `Constants.LOG` (SLF4J) for logging and `Constants.MOD_ID` as the namespace. `Util.kt` provides `String.location()` to build `agesandtheart:<path>` `ResourceLocation`s.

## Kotlin style

The overriding goal is **readability** — a reader should understand code without a decoder ring, and large sections should read almost like English. These rules are enforceable; follow them and flag any deliberate deviation with a local comment.

**TS reader's map:** `val`≈`const`, `var`≈`let`, `List`≈`readonly T[]`, `MutableList`≈`T[]`, `?.`/`?:`≈`?.`/`??`, `data class`≈typed record, `when`≈powerful `switch`. Null-safety is compiler-enforced — lean on it. (We're on **Kotlin 2.4.0**: `..<` ranges and `when` *guard conditions* (2.2+) are both available.)

**Naming**
- Full words, no abbreviations: `blockPosition` not `bp`, `surfaceY` not `y`, `buffer` not `buf`. Single letters only for `it` in a trivial lambda or a genuine math axis.
- UpperCamelCase types; lowerCamelCase functions/properties/locals; **SCREAMING_SNAKE_CASE** for `const val` and `object`/top-level `val` constants.
- Booleans read as predicates (`isSupported`, `hasSkyLight`, `canReach`). Functions are verbs, properties are nouns — property access must be cheap and side-effect-free.
- Never name a file/class `Util`/`Helper`/`Manager`/`Misc` for *new* code (existing `AgeManager`/`Util.kt` are grandfathered; don't add to the pattern). Multi-declaration files get a descriptive name (`Rgba.kt`).
- **Prefer slightly verbose and unambiguous over clever and compact.** A cute name passes review because its author still holds the metaphor in their head; the cost lands later on someone who doesn't. `capabilityBonus` not `capability` when it returns a score; `climatePointsFromOtherPresets` not `climatesElsewhere`; `sawADressingThatIgnoresMaterials` not `reachedAListeningOne`. Extra characters are cheap; a re-read is not. Applies to comments as much as identifiers. **Exception:** where a metaphor is already load-bearing across the codebase (`steer` a preset, `territory`, `seam`, `mingled`, `readiness`), keep it — consistency beats a lone improvement.
- **Never use a term naming a real population as a metaphor for inability.** "A preset *deaf to* a parameter" became "a preset that *ignores* a parameter". The domain usually already has the neutral verb — here `ignoresMaterial`/`ignoresClimate` were sitting right there.

**Immutability**
- `val` unless a `var` is provably required. Compute a value once with an `if`/`when` expression instead of reassigning a `var` across branches.
- Read-only collection types (`List`/`Set`/`Map`) built with `listOf`/`setOf`/`mapOf`; use `Mutable*` only where you actually mutate, kept as local as possible. Expose read-only, back with a private `mutableListOf` if needed.
- `const val` for compile-time constants; **name every magic number/string** (`OPERATOR_PERMISSION_LEVEL = 2`, not a bare `2`).

**Functions & purity**
- Prefer **pure functions** (output depends only on input, no side effects) for calculation — they're unit-testable without a running server. Keep world/entity mutation in thin, clearly-named functions at the edges.
- Single-expression functions use expression bodies (`fun area(w: Int, h: Int) = w * h`); state return types on public API.
- Return values instead of mutating parameters. Extract named helpers over inline comments — a well-named call *is* the comment. No giant imperative functions.
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

**Comments describe the code, and nothing else.** A KDoc says what a thing is and how to use it; the
reasoning, the history, the alternatives rejected and the dated quotes belong in `notes/` — `decisions.md`
for a settled call, the design doc or the plan for anything unbuilt. Keep at the code only what would bite
an editor *at that spot*: an ordering that must not change, a trap already fallen into, a constraint whose
violation is silent. State it in a clause, not a section, and let `notes/` carry the argument.

Concretely: no `##` headings inside a KDoc, no changelogs, no "this used to be X and Y changed it", no
dates or attributions. Prefer no KDoc at all where the name already says it — `/** The shape of the
rock. */` on `TERRAIN` earns nothing. A `(design §3.4)` pointer is worth keeping where the design
genuinely constrains the code, and worth dropping from ordinary description.

**Scope functions** — by intent, never nested, never chained >2 deep: `apply` (configure & return), `also` (side effect in a chain), `let` (null-guard/transform), `run`/`with` (configure & compute). If a block grows past a few lines, extract a named function.

**Anti-patterns to avoid** (common in mod code): `!!`; `lateinit` abuse (prefer `val` + constructor or `by lazy`); companion-object soup; **mutable global state** in `object`s/companions; magic numbers; deeply nested scope-function chains; `MutableList` leaking through public API; `when` + `else` on sealed/enum types silently swallowing new cases.

**Save compatibility — not yet a constraint (2026-07-27, revisit at first release).** The mod is still in initial development with no players and no saves worth keeping, so **renaming slot keys, changing codec shapes and bumping `generatorVersion` are all free** — say so and move on. Do *not* add `FORMER_KEYS`-style alias tables, either-or codecs chosen purely to keep old files byte-identical, or treat "no version had to move" as a design goal; prefer the clearer shape and let test Ages break. Still true regardless: a recipe must round-trip *within* a version (`RecipeCheck`), which is correctness rather than compatibility.

**When to break the rules:** hot per-tick loops may justify a plain `for`, a `var` accumulator, or primitive arrays (measure first, comment why); Java/MC interop forces platform types and mutable builders (contain them at the boundary). Immutability and functional style are defaults, not religion — but a break should be **local and commented**, never the ambient style.

## Domain constraint to keep in mind

Minecraft registries (items, blocks, **dimensions**, …) freeze after server startup — content cannot be added mid-game through normal registration. The mod's core feature (authoring dimensions at runtime) works around this via Fantasy, and the persistence model is ours: store each Age's recipe/id as data and re-create the dimension on load rather than registering it permanently. Design new "Age" state as replayable data, not as registered objects.

(History: DynamicDimensions was the original, cross-loader pick; we switched to Fantasy after its only Maven host went offline. That's why runtime dimensions are Fabric-only for now — restoring cross-loader means adding a NeoForge `AgeBackend`.)
