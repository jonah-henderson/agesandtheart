import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("multiloader-common")
    alias(libs.plugins.moddev)
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
    // Ephemeris, which makes the dimensions an Age is.
    //
    // **`compileOnly`, and that is the whole of the relationship.** Ephemeris ships as its own mod, so at
    // runtime its classes arrive in its own jar; putting it on a runtime configuration here would compile a
    // second copy into ours and leave two of every object with the registries split between them.
    compileOnly(libs.ephemeris.common)
    // Annotations only, and only to compile `src/main/java`'s one Mixin. Each loader supplies the real
    // implementation at runtime, so this must never reach a runtime classpath. Nothing is generated here:
    // Minecraft is unobfuscated from 26.1, so there is no refmap for the annotation processor to write.
    compileOnly(libs.mixin)
    // The config spec, written once here rather than twice behind the SPI (`notes/config-research.md`).
    //
    // **The `-fabric` artifact, and that is not a mistake.** It is the one that carries
    // `net.neoforged.neoforge.common.ModConfigSpec` as classes; `-common` holds only the port's own
    // internals and none of the API. NeoForge supplies the same classes itself, so this is the API on
    // both loaders and the implementation on neither — hence `compileOnly`, or a second copy would reach
    // a runtime classpath.
    //
    // **Non-transitive, and that is load-bearing.** The artifact declares Fabric API, and letting that
    // through would put Fabric on `common`'s compile path — the one thing this module must never see.
    // What is wanted is the class files and nothing else.
    compileOnly(libs.forgeConfigApiPort.fabric) { isTransitive = false }
}

artifacts {
    // One Java source directory again, now that the parser is Kotlin like everything else and no task
    // has to run before a loader can compile against it.
    add("commonJava", sourceSets.main.get().java.sourceDirectories.singleFile)
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

dependencies {
    // Mordant draws the word-authoring tool: raw-mode key events, and widgets to lay an answer out in.
    //
    // **The one place a UI library is allowed.** `ui-libraries-research.md` rules against a Kotlin UI
    // library because KFF supplies the stdlib as a *mod* on NeoForge and a bundled Kotlin library cannot
    // see it — which is a fact about the shipped jar. Nothing in `preview` reaches one.
    "previewImplementation"(libs.mordant)
}

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

/**
 * Which specs a feature name selects — `./gradlew :common:test -Pon=sky`.
 *
 * **Derived from where the specs already live**, because the packages already mirror the code they check,
 * so this is a name for a directory rather than a second taxonomy to keep in step. A spec that moves house
 * moves feature with it; one that lands somewhere new is simply not selected by anything, which is a
 * visible gap rather than a silent misfile.
 *
 * The same name works on every test task, so `-Pon=sky` narrows the offline suite and the server suite to
 * the same subject. Where a feature has nothing in a given task, that task runs nothing rather than failing.
 */
val SPECS_BY_FEATURE: Map<String, List<String>> = mapOf(
    "sky" to listOf(
        "co.voik.agesandtheart.sky.*",
        "co.voik.agesandtheart.server.SkyClockCheck",
        "co.voik.agesandtheart.server.SkyParametersCheck",
        "co.voik.agesandtheart.server.RainbowCommandCheck",
        "co.voik.agesandtheart.server.AppearanceCheck",
    ),
    "words" to listOf(
        "co.voik.agesandtheart.age.word.*",
        "co.voik.agesandtheart.server.WritingCheck",
        "co.voik.agesandtheart.server.VocabularyOnServerCheck",
    ),
    "terrain" to listOf(
        "co.voik.agesandtheart.worldgen.*",
        "co.voik.agesandtheart.server.BiomeFootingCheck",
        "co.voik.agesandtheart.server.GenerationCheck",
        "co.voik.agesandtheart.server.StructureDensityCheck",
    ),
    "aspects" to listOf("co.voik.agesandtheart.age.aspect.*"),
    "consequence" to listOf("co.voik.agesandtheart.age.consequence.*"),
    "phenomena" to listOf(
        "co.voik.agesandtheart.age.phenomena.*",
        "co.voik.agesandtheart.server.TempestCheck",
    ),
    "desk" to listOf("co.voik.agesandtheart.desk.*"),
    "rewards" to listOf(
        "co.voik.agesandtheart.age.reward.*",
        "co.voik.agesandtheart.content.ProtectiveSuitCheck",
        "co.voik.agesandtheart.content.ToolboxCheck",
        "co.voik.agesandtheart.content.OreClustersCheck",
        "co.voik.agesandtheart.content.BreakingApartCheck",
        "co.voik.agesandtheart.content.ChargedBandsCheck",
    ),
    "levels" to listOf(
        "co.voik.agesandtheart.age.RecipeCheck",
        "co.voik.agesandtheart.age.BespokeRecipeCheck",
        "co.voik.agesandtheart.age.CodecCheck",
        "co.voik.agesandtheart.age.DimensionTypeCheck",
        "co.voik.agesandtheart.server.DeletionCheck",
    ),
)

/**
 * Narrows a task to one feature, when `-Pon=` names one.
 *
 * **The property is declared as an input**, or a filtered run and a whole one are indistinguishable to
 * up-to-date checking — so `-Pon=sky` would pass, and then the unfiltered run after it would report
 * UP-TO-DATE and say nothing at all. That is the same trap `-Pchecks.loader` fell into.
 */
fun Test.narrowedToFeature() {
    val feature = project.findProperty("on") as String?
    inputs.property("feature", feature ?: "")
    if (feature == null) return
    val patterns = SPECS_BY_FEATURE[feature]
        ?: throw GradleException(
            "No feature named '$feature'. Try: ${SPECS_BY_FEATURE.keys.sorted().joinToString(" ")}",
        )
    filter {
        patterns.forEach { includeTestsMatching(it) }
        // A feature with nothing in this task should run nothing, not fail — `-Pon=desk` is a fair thing to
        // say to `serverTest`, and the answer is "no desk checks need a server".
        isFailOnNoMatchingTests = false
    }
}

/** Must match `NEEDS_REGISTRIES` in `common/src/test/kotlin/.../NeedsRegistries.kt`. */
val NEEDS_REGISTRIES_TAG = "NeedsRegistries"

/** Must match `NEEDS_SERVER` in `common/src/test/kotlin/.../server/DrivenServer.kt`. */
val NEEDS_SERVER_TAG = "NeedsServer"

/** Must match `NEEDS_LANDFORMS` in `common/src/test/kotlin/.../worldgen/Landforms.kt`, which says why. */
val NEEDS_LANDFORMS_TAG = "NeedsLandforms"

/** Must match `NEEDS_TIME` in `common/src/test/kotlin/.../server/DrivenServer.kt`, which says why. */
val NEEDS_TIME_TAG = "NeedsTime"

val test: SourceSet = sourceSets.test.get()
// Minecraft arrives compile-only under ModDevGradle, exactly as for `preview`. The *runtime* half is not
// optional and is not obvious: `Bootstrap.bootStrap()` reads `en_us.json` off the classpath, and without
// it the bootstrap throws — then silently returns early on every later call, because it sets its own
// done-flag before doing the work. Tests then pass against a half-built registry. See NeedsRegistries.
test.compileClasspath += main.compileClasspath + main.output
test.runtimeClasspath += main.compileClasspath + main.runtimeClasspath + main.output
// **The checks see `preview`, and not the other way round.** `MinecraftRegistries`, `Rcon` and
// `LaunchSpec` are wanted by both the suite and the authoring tool, and a tool that read them out of the
// test source set would be a tool depending on tests. One direction only, so a check can never become
// something the tool needs.
test.compileClasspath += preview.output
test.runtimeClasspath += preview.output
// **And what `preview` compiles against**, or a spec touching one of its screens dies on a missing class
// rather than failing an assertion. `Palette` is a Mordant style, so the whole of the drawing layer —
// column widths, gauges, the cells they are cut to — was unreachable from a check that never draws.
test.compileClasspath += configurations.named("previewCompileClasspath").get()
test.runtimeClasspath += configurations.named("previewRuntimeClasspath").get()

tasks.named<Test>("test") {
    useJUnitPlatform()
    narrowedToFeature()

    // **Deliberately nothing here about concurrency, and it is worth saying why.** A
    // `kotest.framework.parallelism` property sat here for a long time doing nothing: it is Kotest 5's, and
    // 6 reads no such property — 6 wants a `ProjectConfig` naming a `SpecExecutionMode`. Wiring that
    // properly and measuring it moved the suite by nothing at all (66s either way), because the specs are
    // CPU-bound and already saturate the machine: the field checks sample millions of terrain columns
    // apiece, so how they are *scheduled* cannot matter and only sampling less can. Concurrent specs also
    // cost the per-spec timings in the XML — every spec reports zero and the root holds the total — which
    // is what found the hot spec in the first place. Do not re-add it without a measurement.

    // The server checks start a real server, which is minutes. They are their own task; this is the loop
    // anyone runs a hundred times a day and it stays at seconds.
    // `-Pfast` drops the registries too, which is the only slow thing left in what remains.
    // The landform checks are their own task and their own bargain — `worldgen/Landforms.kt` argues it.
    // They were three quarters of the cycle, paid on every commit by everyone, most of whom moved no rock.
    val excluded = listOfNotNull(
        NEEDS_SERVER_TAG,
        NEEDS_LANDFORMS_TAG,
        NEEDS_REGISTRIES_TAG.takeIf { project.hasProperty("fast") },
    )
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
 * It depends on the loader's `exportServerLaunch` because [co.voik.agesandtheart.server.DrivenServer]
 * starts the server itself rather than through Gradle — a nested build would wait forever on this one's
 * locks.
 *
 * **`-Pchecks.loader=neoforge` drives the other side with the same checks.** The mod is meant to behave
 * identically on both, so the suite that says whether it does should be the same suite rather than a second
 * one that could drift. Fabric stays the default because it is the loader everything else already assumes.
 */
tasks.register<Test>("serverTest") {
    group = "verification"
    description = "Runs the checks that drive a real dedicated server over RCON. -Pchecks.loader=neoforge for the other side."
    useJUnitPlatform()

    val loader = (project.findProperty("checks.loader") as String?) ?: "fabric"

    testClassesDirs = test.output.classesDirs
    classpath = test.runtimeClasspath
    dependsOn(":$loader:exportServerLaunch")
    systemProperty("agesandtheart.checks.loader", loader)
    // **An input, or the second loader never runs.** A system property is invisible to up-to-date checking,
    // so a suite that has passed on Fabric is reported UP-TO-DATE for NeoForge and says nothing at all —
    // which looks exactly like passing.
    inputs.property("checksLoader", loader)

    // `-Pfast` drops the two specs that *are* the runtime — see NEEDS_TIME, which carries the measurement.
    val excluded = listOfNotNull(
        NEEDS_LANDFORMS_TAG,
        NEEDS_TIME_TAG.takeIf { project.hasProperty("fast") },
    )
    systemProperty("kotest.tags", (listOf(NEEDS_SERVER_TAG) + excluded.map { "!$it" }).joinToString(" & "))
    narrowedToFeature()
    // One server, driven in sequence — so this task deliberately does *not* name `ConcurrentSpecs`.
    // Specs running together would each start a server and fight over one `server.properties` and one world.
    maxHeapSize = "2g"

    testLogging {
        events("failed", "passed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}

/**
 * The checks that read the shape of a landform — `./gradlew :common:landformTest`.
 *
 * **These warn rather than fail**, and `worldgen/Landforms.kt` carries the argument for why. What it costs
 * is that nothing here can stop a bad build, so the task ends by naming every failure and counting them:
 * a run that went red has to be something you scroll *to*, not something you scroll past.
 *
 * It boots a server, because one of them drives `/age compare` — the same tag spans both halves of the
 * suite, since what makes these expensive and fiddly is the same on either side of a server.
 */
tasks.register<Test>("landformTest") {
    group = "verification"
    description = "Runs the landform shape checks. Reports failures without failing the build."
    useJUnitPlatform()

    testClassesDirs = test.output.classesDirs
    classpath = test.runtimeClasspath
    dependsOn(":fabric:exportServerLaunch")

    systemProperty("kotest.tags", NEEDS_LANDFORMS_TAG)
    narrowedToFeature()
    maxHeapSize = "2g"
    ignoreFailures = true

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }

    val slipped = mutableListOf<String>()
    afterTest(
        KotlinClosure2({ what: TestDescriptor, outcome: TestResult ->
            if (outcome.resultType == TestResult.ResultType.FAILURE) slipped += "${what.className?.substringAfterLast('.')} — ${what.name}"
        }),
    )
    doLast {
        if (slipped.isEmpty()) return@doLast
        logger.lifecycle("")
        logger.lifecycle("=".repeat(78))
        logger.lifecycle("  ${slipped.size} LANDFORM CHECK(S) FAILED — the build is green anyway, on purpose.")
        logger.lifecycle("=".repeat(78))
        slipped.forEach { logger.lifecycle("  $it") }
        logger.lifecycle("  Full diagrams above, and in build/reports/tests/landformTest/index.html")
        logger.lifecycle("=".repeat(78))
        logger.lifecycle("")
    }
}

/**
 * Where the word-authoring tool's launch command is written down — `scripts/scrivener.sh` reads it.
 *
 * **A file rather than a `JavaExec` task, because the tool wants a terminal.** Mordant needs raw mode on a
 * real TTY, and Gradle gives a `JavaExec` neither: it owns stdin and strips the control characters a
 * redraw is made of. So the build's only job is to say how to start the JVM, and the script starts it.
 * `:fabric:exportServerLaunch` established the pattern and `LaunchSpec` reads the same tab-separated
 * shape.
 *
 * The working directory is the **repository root**, which is where `MinecraftRegistries.shippedData()`
 * finds `common/src/main/resources` and where the tool writes a word back.
 */
tasks.register("exportAuthoringLaunch") {
    group = "build"
    description = "Records how to start the word-authoring tool. scripts/scrivener.sh runs it."

    val classpath = objects.fileCollection().from(preview.runtimeClasspath)
    val launchFile = layout.buildDirectory.file("authoring-launch.txt")
    val root = rootProject.projectDir
    // **Which JVM, written down rather than left to the script.** A shell has only whatever `java` is on
    // PATH, which on this machine is an SDKMAN default several releases behind the toolchain — and the
    // mismatch does not degrade, it refuses: 26.3's `--sun-misc-unsafe-memory-access` is unrecognised by
    // 21, so the JVM will not start at all. Gradle already knows the right one, so it says so.
    val launcher = javaToolchains.launcherFor(java.toolchain)

    inputs.files(classpath)
    outputs.file(launchFile)

    doLast {
        val lines = listOf(
            "workingDir\t${root.absolutePath}",
            "java\t${launcher.get().executablePath.asFile.absolutePath}",
            "mainClass\tco.voik.agesandtheart.preview.authoring.WordAuthoringKt",
            // Measured against the checks, which need the same registries and die at Gradle's 512m default.
            "jvmArg\t-Xmx2g",
            // **The game's logging has to be off, or it writes over the editor's frame.** Minecraft
            // configures log4j to swallow System.out and reprint it with a timestamp; the tool takes the
            // real stream back after the bootstrap, and this stops the logger arriving from the other
            // side. The file is in the `preview` source set and reaches no shipped jar.
            "jvmArg\t-Dlog4j.configurationFile=authoring-log4j2.xml",
            // Minecraft and Mordant both reach for native access, and the JVM's warnings about it would
            // be the first four lines on the screen. Granted rather than silenced: they are the calls the
            // game and the terminal genuinely make.
            "jvmArg\t--enable-native-access=ALL-UNNAMED",
            "jvmArg\t--sun-misc-unsafe-memory-access=allow",
            "jvmArg\t-classpath",
            "jvmArg\t${classpath.files.joinToString(File.pathSeparator) { it.absolutePath }}",
        )
        launchFile.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

/**
 * Registers one of the offline developer instruments — a `main()` in the `preview` source set, run
 * without launching Minecraft.
 *
 * They divide into three kinds, which is what the Gradle group records: a **verification** instrument
 * asserts something and fails the build when it is wrong; a **documentation** instrument only reports,
 * and is read rather than trusted; a **build** instrument writes a file the repository keeps, and is run
 * by hand with a check standing behind it rather than wired into the build — see `grammars` for why.
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
    "grammars", "build", "co.voik.agesandtheart.preview.GrammarSourcesKt",
    "Writes art/generation/*.json from the authored src/main/generation/*.gen. " +
        "Use --args=import to go the other way.",
)

instrument(
    "noiseprofile", "documentation", "co.voik.agesandtheart.preview.NoiseProfileKt",
    "Reports what fraction each noise character leaves solid, per threshold.",
)

instrument(
    "preview", "documentation", "co.voik.agesandtheart.preview.TerrainPreviewKt",
    "Renders terrain-shape previews to PNG (build/preview). Takes a preset, e.g. --args=hills",
)

instrument(
    "tagreachspike", "documentation", "co.voik.agesandtheart.preview.TagReachSpikeKt",
    "THROWAWAY: when a vague word reaches a weighted set, how many members does it lift? Read it, don't build on it.",
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

instrument(
    "oceandepth", "documentation", "co.voik.agesandtheart.preview.OceanDepthSurveyKt",
    "How deep vanilla's oceans get, from vanilla's own generator — the number DeepWater's threshold clears.",
)
