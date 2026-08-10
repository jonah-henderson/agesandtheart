import org.gradle.internal.extensions.stdlib.capitalized

plugins {
    id("multiloader-loader")
    alias(libs.plugins.moddev)
}

val modId = project.property("modId") as String

/**
 * Ephemeris is a second mod in the dev launch, and **two separate things have to be true of it**: FML builds
 * its mod list from `fml.modFolders`, which MDG writes from the `mods` block below, and the classes have to
 * be on the run classpath, which MDG assembles from `additionalRuntimeClasspath` rather than from
 * `runtimeOnly`. Neither implies the other, and with only one of them the server starts and quietly has no
 * Ephemeris in it.
 *
 * Reading another project's source sets needs it configured first.
 */
evaluationDependsOn(":ephemeris:neoforge")

neoForge {
    version = libs.versions.neoforge.get()
    // Automatically enable neoforge AccessTransformers if the file exists
    val at = project(":common").file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) {
        accessTransformers.from(at.absolutePath)
    }
    val projectPath = project.path
    runs {
        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
            ideName = "NeoForge ${name.capitalized()} ($projectPath)" // Unify the run config names with fabric
        }
        register("client") {
            client()
        }
        register("data") {
            data()
        }
        register("server") {
            server()
        }
    }
    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
        register("ephemeris") {
            // One source set: `:ephemeris:common` is compiled *into* the loader project, exactly as this
            // mod's `common` is, so its classes are already here.
            sourceSet(project(":ephemeris:neoforge").sourceSets.main.get())
        }
    }
}

sourceSets.main.get().resources { srcDir("src/generated/resources") }

dependencies {
    // Ephemeris, as a mod: on the compile path so shared code resolves, and on the runtime one so a dev
    // launch actually loads it. A published build takes it as a declared dependency instead.
    compileOnly(project(":ephemeris:common")) {
        capabilities { requireCapability("co.voik.ephemeris:ephemeris") }
    }
    // The capability has to be named: declaring any capability on a project drops Gradle's implicit
    // `group:name`, so a plain project dependency matches nothing and falls through to whatever
    // variants still carry it — which is Dokka's, and the error names neither cause nor cure.
    runtimeOnly(project(":ephemeris:neoforge")) {
        capabilities { requireCapability("co.voik.ephemeris:ephemeris") }
    }

    implementation(libs.kff)
    // No runtime-dimension backend on NeoForge yet — see NeoForgeAgeBackend (unsupported stub).
}
/**
 * Let the console be driven from a pipe.
 *
 * MDG's run tasks are `JavaExec`, which defaults `standardInput` to an empty stream — so a server started
 * this way ignores anything piped at it and can only be stopped by killing Gradle. Forwarding stdin is what
 * makes `printf '/age list\nstop\n' | ./gradlew :neoforge:runServer` behave the way the Fabric checks do,
 * and it is the only way to exercise a NeoForge dedicated server here at all: there is no NeoForge
 * equivalent of `:fabric:exportServerLaunch`, so `DrivenServer` and its RCON harness cannot reach this side.
 */
tasks.withType<JavaExec>().configureEach {
    standardInput = System.`in`
}

/**
 * The NeoForge half of `:fabric:exportServerLaunch` — see there for the shape and why it exists.
 *
 * **MDG hands this over almost whole.** `prepareServerRun` writes three argfiles (classpath, JVM arguments,
 * program arguments) and `runServer` is a `JavaExec` pointing at them, so unlike Loom's there is nothing to
 * reconstruct and nothing to guess: the pieces are read straight off the task. `createServerLaunchScript`
 * assembles the same pieces into a shell one-liner, which is the proof that reading them is the sanctioned
 * route rather than a liberty.
 *
 * **The working directory is the *game* directory, not the task's.** MDG leaves `workingDir` at the project
 * and `cd`s into `run` as it launches, so a server started from the task's own value would look for its
 * world, its EULA and its `server.properties` in the wrong place and quietly make a second world.
 */
val exportServerLaunch = tasks.register("exportServerLaunch") {
    group = "verification"
    description = "Records the dedicated server's launch command for the server checks to use."

    dependsOn("prepareServerRun")

    val launchFile = layout.buildDirectory.file("server-launch.txt")
    val gameDirectory = layout.projectDirectory.dir("run")
    outputs.file(launchFile)

    doLast {
        val runServer = tasks.getByName<JavaExec>("runServer")
        val lines = buildList {
            add("workingDir\t${gameDirectory.asFile.absolutePath}")
            add("mainClass\t${runServer.mainClass.get()}")
            // **The classpath argfile is not on the task and has to be named.** MDG adds it as the JVM's
            // first argument inside `runServer`'s own exec action, so it appears in neither `jvmArgs` nor
            // `allJvmArgs` nor `classpath` — which reads as a launch that works until the JVM starts with
            // nothing on its path. `createServerLaunchScript` names the same file from the same directory,
            // which is what makes reading it here the sanctioned route rather than a guess.
            add("jvmArg\t@${layout.buildDirectory.file("moddev/serverRunClasspath.txt").get().asFile.absolutePath}")
            val declaredDirectly = runServer.jvmArgs.orEmpty()
            // The mod folders ride in on a provider, and without them FML finds no mod to load at all.
            val fromProviders = runServer.jvmArgumentProviders.flatMap { it.asArguments() }
            (declaredDirectly + fromProviders).forEach { add("jvmArg\t$it") }
            runServer.args.orEmpty().forEach { add("arg\t$it") }
            runServer.classpath.files.forEach { add("classpath\t${it.absolutePath}") }
        }
        launchFile.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
