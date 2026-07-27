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

tasks.register<JavaExec>("noiseprofile") {
    group = "documentation"
    description = "Reports what fraction each noise character leaves solid, per threshold."
    mainClass = "co.voik.agesandtheart.preview.NoiseProfileKt"
    classpath = preview.runtimeClasspath
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

tasks.register<JavaExec>("noisebench") {
    group = "verification"
    description = "Times NormalNoise offline, to price a 3D-noise field before building one."
    mainClass = "co.voik.agesandtheart.preview.NoiseBenchmarkKt"
    classpath = preview.runtimeClasspath
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

tasks.register<JavaExec>("preview") {
    group = "documentation"
    description = "Renders terrain-shape previews to PNG (build/preview) without launching Minecraft."
    mainClass = "co.voik.agesandtheart.preview.TerrainPreviewKt"
    classpath = preview.runtimeClasspath
    // Pass a preset name through, e.g. ./gradlew :common:preview --args=hills
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

tasks.register<JavaExec>("spanscheck") {
    group = "verification"
    description = "Differential check of Spans interval algebra against a per-block reference."
    mainClass = "co.voik.agesandtheart.preview.SpansCheckKt"
    classpath = preview.runtimeClasspath
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

tasks.register<JavaExec>("depthcachecheck") {
    group = "verification"
    description = "Checks BelowTerrain's column cache is actually hit (guards a silent indexing bug)."
    mainClass = "co.voik.agesandtheart.preview.DepthCacheCheckKt"
    classpath = preview.runtimeClasspath
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}
