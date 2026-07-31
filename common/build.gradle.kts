import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("multiloader-common")
    alias(libs.plugins.moddev)
    // Gradle's own ANTLR plugin, so generating the parser needs no third-party Gradle plugin.
    antlr
    alias(libs.plugins.powerAssert)
}

/**
 * `check` and `require` become diagrammed assertions — **in the tests only**.
 *
 * This is what let the checks move onto Kotest without rewriting their assertions. Every one of them was
 * already written as `check(condition) { "a sentence saying what went wrong" }`, and those sentences are
 * the point: the KDoc beside them explains what each is *for*. Power-Assert keeps the sentence and appends
 * a rendering of every subexpression in the condition, so the diagnostics got better without a rewrite.
 *
 * Deliberately not applied to `main`: there `check` is a cheap guard on a hot path, not a report.
 */
@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions = listOf("kotlin.check", "kotlin.require")
    includedSourceSets = listOf("test")
}

neoForge {
    neoFormVersion = libs.versions.neoForm.get()
    // Automatically enable AccessTransformers if the file exists
    val at = file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) {
        accessTransformers.from(at.absolutePath)
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

    // Annotations only, and only to compile `src/main/java`'s one Mixin. Each loader supplies the real
    // implementation at runtime, so this must never reach a runtime classpath. Nothing is generated here:
    // Minecraft is unobfuscated from 26.1, so there is no refmap for the annotation processor to write.
    compileOnly(libs.mixin)
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
for (readsTheSources in listOf("compileKotlin", "dokkaGenerateModuleJavadoc", "sourcesJar")) {
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
val preview: SourceSet = sourceSets.create("preview")

val main: SourceSet = sourceSets.main.get()
// Minecraft arrives compile-only under ModDevGradle, so it has to be forced onto the runtime side too.
preview.compileClasspath += main.compileClasspath + main.output
preview.runtimeClasspath += main.compileClasspath + main.runtimeClasspath + main.output

// ---------------------------------------------------------------------------------------------------
// The tests. One task, tagged — see `MinecraftRegistries.kt` for what the tag means and why.
// ---------------------------------------------------------------------------------------------------

/**
 * Kotest brings its own JUnit Platform engine, and its own version of it.
 *
 * Deliberately no `junit-bom` here. The platform's version line went to 6 alongside JUnit 6 while Kotest
 * is still built against the 1.x line, so pinning both from one BOM makes them disagree. Letting Kotest
 * choose is the whole of the compatibility story.
 */
dependencies {
    testImplementation(libs.kotestRunner)
    testImplementation(libs.kotestAssertions)
    testImplementation(libs.kotestProperty)
}

/** Must match `NEEDS_REGISTRIES` in `common/src/test/kotlin/.../MinecraftRegistries.kt`. */
val NEEDS_REGISTRIES_TAG = "NeedsRegistries"

/** Must match `NEEDS_SERVER` in `common/src/test/kotlin/.../server/DrivenServer.kt`. */
val NEEDS_SERVER_TAG = "NeedsServer"

val test: SourceSet = sourceSets.test.get()
// Minecraft arrives compile-only under ModDevGradle, exactly as for `preview`. The *runtime* half is not
// optional and is not obvious: `Bootstrap.bootStrap()` reads `en_us.json` off the classpath, and without
// it the bootstrap throws — then silently returns early on every later call, because it sets its own
// done-flag before doing the work. Tests then pass against a half-built registry. See NeedsRegistries.
test.compileClasspath += main.compileClasspath + main.output
test.runtimeClasspath += main.compileClasspath + main.runtimeClasspath + main.output

// The `antlr` plugin adds a grammar task per source set; the same undeclared-output trap as `preview`.
tasks.named("compileTestKotlin") { dependsOn(tasks.named("generateTestGrammarSource")) }

tasks.named<Test>("test") {
    useJUnitPlatform()

    // Kotest runs specs concurrently on this many threads. The default of one left fifteen cores idle
    // while the slowest check ran alone; the checks are independent, so this is nearly free wall-clock.
    systemProperty("kotest.framework.parallelism", Runtime.getRuntime().availableProcessors().toString())

    // The server checks start a real server, which is minutes. They are their own task; this is the loop
    // anyone runs a hundred times a day and it stays at seconds.
    // `-Pfast` drops the registries too, which is the only slow thing left in what remains.
    val excluded = listOfNotNull(NEEDS_SERVER_TAG, NEEDS_REGISTRIES_TAG.takeIf { project.hasProperty("fast") })
    systemProperty("kotest.tags", excluded.joinToString(" & ") { "!$it" })

    // Measured, not guessed: RegionShare samples four million columns and the registries are not small.
    // At the Gradle default of 512m this task dies; 2g leaves headroom without crowding the daemon.
    maxHeapSize = "2g"

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        // Power-Assert's diagram is the failure message, and it is many lines. Truncating it would throw
        // away the thing that makes a failure diagnosable from the console alone.
        showStackTraces = true
    }
}

/**
 * The checks that need a real server — `./gradlew :common:serverTest`.
 *
 * Same source set and same Kotest suite as `test`, selected by tag rather than split into a source set of
 * its own: they are the same kind of thing written the same way, and only what they *cost* differs.
 *
 * It depends on `:fabric:exportServerLaunch` because [co.voik.agesandtheart.server.DrivenServer] starts the
 * server itself rather than through Gradle — a nested build would wait forever on this one's locks.
 */
tasks.register<Test>("serverTest") {
    group = "verification"
    description = "Runs the checks that drive a real dedicated server over RCON."
    useJUnitPlatform()

    testClassesDirs = test.output.classesDirs
    classpath = test.runtimeClasspath
    dependsOn(":fabric:exportServerLaunch")

    systemProperty("kotest.tags", NEEDS_SERVER_TAG)
    // One server, driven in sequence. Specs sharing a JVM would otherwise each start one and fight over
    // the same `server.properties` and the same world directory.
    systemProperty("kotest.framework.parallelism", "1")
    maxHeapSize = "2g"

    testLogging {
        events("failed", "passed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}

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
    "terraindiff", "verification", "co.voik.agesandtheart.preview.TerrainDiffKt",
    "Compares two saved worlds Age by Age, block for block. Takes two world folders, " +
        "e.g. --args=\"before/world after/world\" (absolute paths — Gradle runs from this module).",
)

instrument(
    "claimprofile", "documentation", "co.voik.agesandtheart.preview.ClaimProfileKt",
    "Reports the claim-value distribution and reprints ClaimTilt's table, ready to paste.",
)

