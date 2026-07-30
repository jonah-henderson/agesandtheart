plugins {
    id("multiloader-common")
    alias(libs.plugins.moddev)
    // Gradle's own ANTLR plugin, so generating the parser needs no third-party Gradle plugin.
    antlr
}

neoForge {
    neoFormVersion = libs.versions.neoForm
    // Automatically enable AccessTransformers if the file exists
    val at = file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) {
        accessTransformers.from(at.absolutePath)
    }
    parchment {
        minecraftVersion = libs.versions.parchmentMC
        mappingsVersion = libs.versions.parchment
    }
}

configurations {
    create("commonJava") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
    create("commonKotlin") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
    create("commonResources") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
}

dependencies {
    antlr(libs.antlrTool)
    implementation(libs.antlrRuntime)
}

/**
 * Name the generated parser's package explicitly.
 *
 * Gradle's ANTLR plugin mirrors the grammar's directory into the output but never passes `-package`, so the
 * classes come out in the **default package** while sitting in a package-shaped directory. Java would merely
 * warn; **Kotlin cannot import from the default package at all**, so nothing in `common` could reach them.
 *
 * Only the argument, and deliberately *not* a matching `outputDirectory`: the plugin already mirrors the
 * grammar's own directory beneath the output root, so setting both doubles the path and yields
 * `…/grammar/co/voik/agesandtheart/grammar/ArtParser.java`. Where the file lands was always right; it was
 * the declaration inside it that was missing.
 */
tasks.named<org.gradle.api.plugins.antlr.AntlrTask>("generateGrammarSource") {
    arguments = arguments + listOf("-package", "co.voik.agesandtheart.grammar", "-visitor")
    // Clear the output first. ANTLR rewrites every file it still produces but removes none it no longer
    // does, so dropping a rule leaves a stale class on disk with the *old* token numbering — which compiles
    // perfectly and then misparses. Cheap, because Gradle skips the whole task when inputs are unchanged.
    val generated = outputDirectory
    doFirst { generated.deleteRecursively() }
}

// Everything in this module that walks its own source directories now reads a *generated* one too, and
// Gradle rightly refuses to guess the ordering. The loaders need no equivalent: they read the `commonJava`
// configuration, whose artifacts already name the generating task as their builder.
for (readsTheSources in listOf("compileKotlin", "dokkaJavadoc", "sourcesJar")) {
    tasks.named(readsTheSources) { dependsOn("generateGrammarSource") }
}

// The ANTLR *tool* drags in ST4 and friends, and the plugin puts the whole thing on the compile classpath
// through `api`. Only the runtime belongs there; the tool is a build-time concern.
configurations.named("api") { setExtendsFrom(emptyList()) }

artifacts {
    // Every Java source directory rather than `.singleFile` as before: with a generated parser there are
    // two, and the generated one does not exist until `generateGrammarSource` has run — so each carries
    // that task as its builder, or a loader compiles against a directory nobody has filled in yet.
    for (directory in sourceSets.main.get().java.sourceDirectories) {
        add("commonJava", directory) { builtBy(tasks.named("generateGrammarSource")) }
    }
    add("commonKotlin", sourceSets.main.get().kotlin.sourceDirectories.filter { it.name == "kotlin" }.singleFile)
    add("commonResources", sourceSets.main.get().resources.sourceDirectories.singleFile)
}

// A developer tool, deliberately in its own source set: the loaders only ever pull `main`'s directories
// (see the artifacts above), so nothing here can end up in a shipped jar.
val preview: SourceSet by sourceSets.creating

val main: SourceSet = sourceSets.main.get()
// Minecraft arrives compile-only under ModDevGradle, so it has to be forced onto the runtime side too.
preview.compileClasspath += main.compileClasspath + main.output
preview.runtimeClasspath += main.compileClasspath + main.runtimeClasspath + main.output

// The `antlr` plugin adds a grammar task *per source set*, so creating `preview` also created
// `generatePreviewGrammarSource` and put its output directory on the source set — even though every grammar
// we have lives in `src/main/antlr` and this one therefore generates nothing. Kotlin still reads that
// directory, and without this edge Gradle intermittently fails the build for consuming another task's output
// undeclared. It presented as "compilePreviewKotlin is flaky" for weeks and cost a check run more than once.
tasks.named("compilePreviewKotlin") { dependsOn(tasks.named("generatePreviewGrammarSource")) }

/**
 * Registers one of the offline developer instruments — a `main()` in the `preview` source set, run
 * without launching Minecraft.
 *
 * They divide into two kinds, which is what the Gradle group records: a **verification** instrument
 * asserts something and fails the build when it is wrong; a **documentation** instrument only reports,
 * and is read rather than trusted.
 */
fun instrument(name: String, group: String, mainClassName: String, description: String) =
    tasks.register<JavaExec>(name) {
        this.group = group
        this.description = description
        mainClass = mainClassName
        classpath = preview.runtimeClasspath
        javaLauncher = javaToolchains.launcherFor(java.toolchain)
    }

instrument(
    "noiseprofile", "documentation", "co.voik.agesandtheart.preview.NoiseProfileKt",
    "Reports what fraction each noise character leaves solid, per threshold.",
)

instrument(
    "noisebench", "documentation", "co.voik.agesandtheart.preview.NoiseBenchmarkKt",
    "Times NormalNoise offline, to price a 3D-noise field before building one.",
)

instrument(
    "preview", "documentation", "co.voik.agesandtheart.preview.TerrainPreviewKt",
    "Renders terrain-shape previews to PNG (build/preview). Takes a preset, e.g. --args=hills",
)

instrument(
    "resolverspike", "documentation", "co.voik.agesandtheart.preview.ResolverSpikeKt",
    "THROWAWAY: does constraint satisfaction over weighted tags work? Read it, don't build on it.",
)

instrument(
    "spanscheck", "verification", "co.voik.agesandtheart.preview.SpansCheckKt",
    "Differential check of Spans interval algebra against a per-block reference.",
)

instrument(
    "depthcachecheck", "verification", "co.voik.agesandtheart.preview.DepthCacheCheckKt",
    "Checks BelowTerrain's column cache is actually hit (guards a silent indexing bug).",
)

instrument(
    "choosecheck", "verification", "co.voik.agesandtheart.preview.ChooseCheckKt",
    "Checks Choose/Chance draw reproducibly, honour their counts, weights and probabilities.",
)

instrument(
    "faultcheck", "verification", "co.voik.agesandtheart.preview.FaultCheckKt",
    "Checks a fault displaces rock exactly, and that a rift's band is the seam the territories draw.",
)

instrument(
    "codeccheck", "verification", "co.voik.agesandtheart.preview.CodecCheckKt",
    "Builds every registered codec, catching companion-initialisation order before a server boot does.",
)

instrument(
    "recipecheck", "verification", "co.voik.agesandtheart.preview.RecipeCheckKt",
    "Checks Age recipes round-trip through NBT, and that written generator kinds still resolve.",
)

instrument(
    "vocabularycheck", "verification", "co.voik.agesandtheart.preview.VocabularyCheckKt",
    "Checks every word of the Art is backed by something the world can be, and every preset askable for.",
)

instrument(
    "grammarcheck", "verification", "co.voik.agesandtheart.preview.GrammarCheckKt",
    "Checks the Art's grammar reads a book as designed, and that the parser stays behind its boundary.",
)

instrument(
    "resolvercheck", "verification", "co.voik.agesandtheart.preview.ResolverCheckKt",
    "Checks the resolver's promises: pure, order-blind, diagnosable, and vaguer sentences vary more.",
)

instrument(
    "terraindiff", "verification", "co.voik.agesandtheart.preview.TerrainDiffKt",
    "Compares two saved worlds Age by Age, block for block. Takes two world folders, " +
        "e.g. --args=\"before/world after/world\" (absolute paths — Gradle runs from this module).",
)

instrument(
    "regionsharecheck", "verification", "co.voik.agesandtheart.preview.RegionShareCheckKt",
    "Measures the ground each weighted territory actually covers, and reprints ClaimTilt's table.",
)

instrument(
    "skycheck", "verification", "co.voik.agesandtheart.preview.SkyCheckKt",
    "Checks an Age's sky reproduces from its seed, that a one-sun Age keeps vanilla's own orbit, " +
        "and that nothing drawn is degenerate.",
)
