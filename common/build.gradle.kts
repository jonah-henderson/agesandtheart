plugins {
    id("multiloader-common")
    alias(libs.plugins.moddev)
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

artifacts {
    add("commonJava", sourceSets.main.get().java.sourceDirectories.singleFile)
    add("commonKotlin", sourceSets.main.get().kotlin.sourceDirectories.filter { !it.name.endsWith("java") }.singleFile)
    add("commonResources", sourceSets.main.get().resources.sourceDirectories.singleFile)
}

// A developer tool, deliberately in its own source set: the loaders only ever pull `main`'s directories
// (see the artifacts above), so nothing here can end up in a shipped jar.
val preview: SourceSet by sourceSets.creating

val main: SourceSet = sourceSets.main.get()
// Minecraft arrives compile-only under ModDevGradle, so it has to be forced onto the runtime side too.
preview.compileClasspath += main.compileClasspath + main.output
preview.runtimeClasspath += main.compileClasspath + main.runtimeClasspath + main.output

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
    "codeccheck", "verification", "co.voik.agesandtheart.preview.CodecCheckKt",
    "Builds every registered codec, catching companion-initialisation order before a server boot does.",
)

instrument(
    "recipecheck", "verification", "co.voik.agesandtheart.preview.RecipeCheckKt",
    "Checks Age recipes round-trip through NBT, and that written generator kinds still resolve.",
)

instrument(
    "terraindiff", "verification", "co.voik.agesandtheart.preview.TerrainDiffKt",
    "Compares two saved worlds Age by Age, block for block. Takes two world folders, " +
        "e.g. --args=\"before/world after/world\" (absolute paths — Gradle runs from this module).",
)
