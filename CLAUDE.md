# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Design documents

`notes/` holds the design record, and it is **normative** — where code and these documents disagree,
that is a bug in one of them, so revise the doc rather than letting the code drift away from it. Read the
relevant one before working in its area; most of the value is in the *reasoning*, not the conclusions.

- **`notes/terrain-architecture.md`** — the two-tier terrain system (composable field toolkit + bespoke
  presets), and why each piece is shaped the way it is. Built and shipped.
- **`notes/the-art-design.md`** — "the Art": the books, the language, slots and tags, consequences,
  book editing, the economy. Phases 1–2 are built; everything from the resolver on is design only.
- **`notes/the-art-implementation-plan.md`** — the eight phases and what each has to prove.

## What this is

**Ages and the Art** — a Minecraft mod (Mystcraft-inspired: author dimensional "Ages" from written Symbol pages, link between them) for **Minecraft 1.21.1**, built as a **multiloader** mod running on both **Fabric** and **NeoForge** from one codebase. Mod id `agesandtheart`, root package `co.voik.agesandtheart`.

Current state: a working **runtime-dimension spike** exists (see "Ages / runtime dimensions" below) — `/age create|tp|list` authors a persistent dimension that survives restart. It's **command-driven and Fabric-only** for now; the player-facing books, the symbol grammar, and a NeoForge backend are still to come. The rest is template scaffolding (a demo title-screen Mixin, hello-world logging).

The version is pinned to 1.21.1 because Fabric's Fantasy `0.6.4+1.21` (our runtime-dimension library) targets it — do not bump Minecraft versions without revisiting that constraint.

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

# Drive the headless server through a list of /age commands and stop it (see scripts/checks/)
scripts/drive-server.sh scripts/checks/slots.txt
```

**Headless checks go through `scripts/drive-server.sh`.** It waits for each command to *finish*
before sending the next — by echoing a unique token back through `say`, since console commands are
drained by the server thread in order — rather than sleeping a guessed interval. It also writes to a
throwaway world by default (`--level` to override) and restores `server.properties` on the way out,
so a check can never disturb a save. It drives a server; it does not assert, so read the output.

Run directories are `runs/` (Fabric) and `run/` (NeoForge), both git-ignored. The first build/run downloads Minecraft, mappings, and the loader toolchains — slow once, then cached.

**Tests:** there is no test suite yet; `./gradlew test` is currently a no-op. When tests are added, prefer putting loader-agnostic logic tests in `common` and run a single one with `./gradlew :common:test --tests "co.voik.agesandtheart.SomeTest"`.

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
The mod has **no Mixins** right now; everything goes through Fabric API hooks + the `ServiceLoader` split, so the template's demo mixins were removed. If you genuinely must patch a vanilla class: Mixins are written in **Java** (Kotlin isn't viable), one `*.mixins.json` config per module referenced from each loader's metadata (`fabric.mod.json`, `neoforge.mods.toml`). On **Loom 1.13** the mixin annotation processor / refmap is off by default — do **not** re-add a `loom { mixin { … } }` block. Always prefer a loader event/API over a Mixin when one exists.

## Ages / runtime dimensions

The core mechanic — creating dimensions ("Ages") at runtime and persisting them — lives in `common/.../age/`, with the one loader-specific piece behind the `AgeBackend` service:

- **`AgeRecipe`** — **what an Age is, as data**: the preset it was written from, its seed, and the generator version that made it. Codec-serialised, and the *only* record of an Age — the dimension is rebuilt from it on every open. `AgePreset` names the generation presets; its `key` is the save format, so renaming one orphans every Age already written with it (`:common:recipecheck` guards this).
- **`AgeGeneration`** — turns a recipe into a `ChunkGenerator`, in an exhaustive `when` over `AgePreset`. A pure function of the recipe (plus the server, for registries), because an Age must rebuild identically on every open.
- **`AgeSavedData`** — vanilla `SavedData` on the overworld's data storage, persisting each Age's recipe. Runtime-dimension libraries do **not** auto-restore dimensions on restart, so we track them ourselves. Reads the pre-recipe format (an id list plus generator-kind strings) and migrates it.
- **`Ages`** — loader-agnostic policy: `create` / `open` / `ensure` / `delete` (delegating to `Services.AGE_BACKEND`) and `reloadSaved` (replay on boot).
- **`AgeCommand`** — the `/age` Brigadier tree (vanilla, so it's in `common`); the debug trigger until books exist. `/age compare <a> <b>` generates two Ages and diffs them block for block — write two with the same seed to check a recipe reproduces.
- **`AgeBackend`** (service) — `FabricAgeBackend` implements it with **Fantasy** (`Fantasy.get(server).getOrOpenPersistentWorld(id, config)`); `NeoForgeAgeBackend` is an `isSupported = false` stub, so `/age create` on NeoForge reports "not supported yet" instead of crashing.

Reload trigger is loader-specific: Fabric's `SERVER_STARTED` event calls `Ages.reloadSaved`. Fantasy must be called on the server thread (commands and lifecycle events already are). **Fantasy is Fabric-only**, so all Fantasy references stay in the `fabric` module — never in `common`.

## Conventions

- **Versions live in `libs.versions.toml`** (Gradle version catalog) — the single source of truth. Change dependency/loader versions there, not in module build files.
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

**Immutability**
- `val` unless a `var` is provably required. Compute a value once with an `if`/`when` expression instead of reassigning a `var` across branches.
- Read-only collection types (`List`/`Set`/`Map`) built with `listOf`/`setOf`/`mapOf`; use `Mutable*` only where you actually mutate, kept as local as possible. Expose read-only, back with a private `mutableListOf` if needed.
- `const val` for compile-time constants; **name every magic number/string** (`OPERATOR_PERMISSION_LEVEL = 2`, not a bare `2`).

**Functions & purity**
- Prefer **pure functions** (output depends only on input, no side effects) for calculation — they're unit-testable without a running server. Keep world/entity mutation in thin, clearly-named functions at the edges.
- Single-expression functions use expression bodies (`fun area(w: Int, h: Int) = w * h`); state return types on public API.
- Return values instead of mutating parameters. Extract named helpers over inline comments — a well-named call *is* the comment. No giant imperative functions.
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

**Scope functions** — by intent, never nested, never chained >2 deep: `apply` (configure & return), `also` (side effect in a chain), `let` (null-guard/transform), `run`/`with` (configure & compute). If a block grows past a few lines, extract a named function.

**Anti-patterns to avoid** (common in mod code): `!!`; `lateinit` abuse (prefer `val` + constructor or `by lazy`); companion-object soup; **mutable global state** in `object`s/companions; magic numbers; deeply nested scope-function chains; `MutableList` leaking through public API; `when` + `else` on sealed/enum types silently swallowing new cases.

**When to break the rules:** hot per-tick loops may justify a plain `for`, a `var` accumulator, or primitive arrays (measure first, comment why); Java/MC interop forces platform types and mutable builders (contain them at the boundary). Immutability and functional style are defaults, not religion — but a break should be **local and commented**, never the ambient style.

## Domain constraint to keep in mind

Minecraft registries (items, blocks, **dimensions**, …) freeze after server startup — content cannot be added mid-game through normal registration. The mod's core feature (authoring dimensions at runtime) works around this via Fantasy, and the persistence model is ours: store each Age's recipe/id as data and re-create the dimension on load rather than registering it permanently. Design new "Age" state as replayable data, not as registered objects.

(History: DynamicDimensions was the original, cross-loader pick; we switched to Fantasy after its only Maven host went offline. That's why runtime dimensions are Fabric-only for now — restoring cross-loader means adding a NeoForge `AgeBackend`.)
