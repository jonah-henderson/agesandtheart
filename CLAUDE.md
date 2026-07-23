# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
```

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
Both immediately call `CommonObject.init()`. Keep loader entrypoints tiny; put logic in `common`.

**4. Mixins are Java-only and registered per config.**
Vanilla-class patching uses SpongePowered Mixin, written in **Java** (Kotlin is not viable here). Configs: `common/.../resources/agesandtheart.mixins.json` (shared), plus `agesandtheart.fabric.mixins.json` / `agesandtheart.neoforge.mixins.json` (loader-specific). Each config is referenced from that loader's metadata (`fabric.mod.json` `mixins`, `neoforge.mods.toml` `[[mixins]]`). Adding a Mixin = create the Java class under a `mixin/` package + list it in the appropriate `.mixins.json`. Prefer loader events/APIs over Mixins when an option exists.

## Ages / runtime dimensions

The core mechanic — creating dimensions ("Ages") at runtime and persisting them — lives in `common/.../age/`, with the one loader-specific piece behind the `AgeBackend` service:

- **`AgeGen`** — builds the generation recipe (currently a superflat `ChunkGenerator` from `server.registryAccess()`). Loader-agnostic; v1 replaces this with symbol-driven generation, same `server -> generator` shape.
- **`AgeSavedData`** — vanilla `SavedData` on the overworld's data storage, persisting the set of Age ids. Runtime-dimension libraries do **not** auto-restore dimensions on restart, so we track ids ourselves.
- **`AgeManager`** — loader-agnostic policy: `createAge` / `openAge` (delegates to `Services.AGE_BACKEND`) and `reloadSavedAges` (replay on boot).
- **`AgeCommand`** — the `/age create|tp|list` Brigadier tree (vanilla, so it's in `common`); the debug trigger until books exist.
- **`AgeBackend`** (service) — `FabricAgeBackend` implements it with **Fantasy** (`Fantasy.get(server).getOrOpenPersistentWorld(id, config)`); `NeoForgeAgeBackend` is an `isSupported = false` stub, so `/age create` on NeoForge reports "not supported yet" instead of crashing.

Reload trigger is loader-specific: Fabric's `SERVER_STARTED` event calls `AgeManager.reloadSavedAges`. Fantasy must be called on the server thread (commands and lifecycle events already are). **Fantasy is Fabric-only**, so all Fantasy references stay in the `fabric` module — never in `common`.

## Conventions

- **Versions live in `libs.versions.toml`** (Gradle version catalog) — the single source of truth. Change dependency/loader versions there, not in module build files.
- **Mod identity lives in `gradle.properties`** (`modId`, `modName`, `group`, `version`, `license`, etc.). Metadata files (`fabric.mod.json`, `neoforge.mods.toml`, `pack.mcmeta`, `*.mixins.json`) are **templated**: their `${...}` placeholders are filled at build time by `processResources` (see `buildSrc/.../multiloader-common.gradle`). Edit identity/versions in `gradle.properties` + the catalog, not by hand in the manifests.
- Shared build logic is in `buildSrc/` convention plugins (`multiloader-common`, `multiloader-loader`); per-module `build.gradle.kts` files stay small.
- Use `Constants.LOG` (SLF4J) for logging and `Constants.MOD_ID` as the namespace. `Util.kt` provides `String.location()` to build `agesandtheart:<path>` `ResourceLocation`s.

## Domain constraint to keep in mind

Minecraft registries (items, blocks, **dimensions**, …) freeze after server startup — content cannot be added mid-game through normal registration. The mod's core feature (authoring dimensions at runtime) works around this via Fantasy, and the persistence model is ours: store each Age's recipe/id as data and re-create the dimension on load rather than registering it permanently. Design new "Age" state as replayable data, not as registered objects.

(History: DynamicDimensions was the original, cross-loader pick; we switched to Fantasy after its only Maven host went offline. That's why runtime dimensions are Fabric-only for now — restoring cross-loader means adding a NeoForge `AgeBackend`.)
