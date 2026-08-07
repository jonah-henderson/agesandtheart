plugins {
    id("multiloader-loader")
    alias(libs.plugins.loom)
}

val modId = project.property("modId") as String

/** Where the dedicated server runs, shared by Loom's run config and the launch spec the checks read. */
val SERVER_RUN_DIR = "runs/server"

dependencies {
    minecraft(libs.minecraft)
    // NO MAPPINGS AT ALL. Mojang stopped obfuscating Java Edition at 26.1, so there are no official
    // mappings published for this version and nothing for them to have mapped — Loom reports exactly that
    // if you ask for them ("Failed to find official mojang mappings for 26.1.2").
    implementation(libs.fabricLoader)
    implementation(libs.fabricApi)

    implementation(libs.flk)

    // Fantasy: runtime dimension creation (the Fabric-only backend for Ages).
    implementation(libs.fantasy)

    // The Art's parser runtime. Loom nests it and synthesises a fabric.mod.json for the non-mod jar
    // itself, so Fabric's half of bundling really is two lines.
    //
    // `modImplementation` is gone along with remapping: from 26.1 Minecraft and mods alike are
    // unobfuscated, so the plain configurations are the only ones there are.
}

/**
 * Writes down how to start the dedicated server, so a test can start one **without Gradle**.
 *
 * The server checks run inside a Gradle-launched JVM, and a second `./gradlew` from in there would sit
 * waiting on the first one's locks — so the driver launches the server itself. Everything it needs is read
 * off `runServer` rather than reconstructed: Loom configures devlaunchinjector through the task's JVM
 * arguments (`fabric.dli.*`), and guessing at those is how this rots the next time Loom moves.
 *
 * Declaring the run classpath as an **input** is what makes the jars exist: a Gradle file collection
 * carries the tasks that build it, so depending on the collection depends on all of them.
 */
val exportServerLaunch = tasks.register("exportServerLaunch") {
    group = "verification"
    description = "Records the dedicated server's launch command for the server checks to use."

    /**
     * **Never `runServer.map { … }`.** Mapping a `TaskProvider` makes that task the *producer* of the
     * value, so declaring the result as an input makes this task depend on `runServer` **running** — which
     * it did, for seventeen minutes, before anyone noticed the build was not stuck but hosting a server.
     *
     * A plain provider reads the same configuration without claiming the task produced it. The jars still
     * get built, because the classpath collection carries its own producers and those are what an input
     * declaration follows.
     */
    val runClasspath = objects.fileCollection().from(provider { tasks.getByName<JavaExec>("runServer").classpath })
    val launchFile = layout.buildDirectory.file("server-launch.txt")

    inputs.files(runClasspath)
    outputs.file(launchFile)

    /**
     * **`configureLaunch` is what makes the JVM arguments readable at all.** Loom supplies them through a
     * `jvmArgumentProvider` that throws "Cannot get MinecraftProvider before it has been setup" until the
     * game is configured, and an unprimed provider silently contributed *nothing* — which wrote a launch
     * with no `-Dfabric.dli.*` at all, so devlaunchinjector had no config and the server died on start
     * before it could even open a log.
     */
    dependsOn("configureLaunch")

    val runDirectory = file(SERVER_RUN_DIR)

    doLast {
        val runServer = tasks.getByName<JavaExec>("runServer")
        val lines = buildList {
            // Loom only points the task at its run directory as it starts, so the task's own `workingDir`
            // is the project directory here. The declaration below is the same string the run uses.
            add("workingDir\t${runDirectory.absolutePath}")
            add("mainClass\t${runServer.mainClass.get()}")
            // The first of these is an `@argfile` holding `-classpath …`, so the classpath rides along with
            // the JVM arguments and needs no `-cp` of its own.
            val declaredDirectly = runServer.jvmArgs.orEmpty()
            val fromLoomsProvider = runServer.jvmArgumentProviders.flatMap { it.asArguments() }
            (declaredDirectly + fromLoomsProvider).forEach { add("jvmArg\t$it") }
            // Headless. Loom adds this as the run starts, the same way it does the working directory, and a
            // server that opens a window is a server no check can drive.
            add("arg\tnogui")
            runClasspath.files.forEach { add("classpath\t${it.absolutePath}") }
        }
        launchFile.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

loom {
    val aw = project(":common").file("src/main/resources/${modId}.accesswidener")
    if (aw.exists()) {
        accessWidenerPath.set(aw)
    }
    runs {
        named("client") {
            client()
            setConfigName("Fabric Client")
            ideConfigGenerated(true)
            runDir("runs/client")
        }
        named("server") {
            server()
            setConfigName("Fabric Server")
            ideConfigGenerated(true)
            runDir(SERVER_RUN_DIR)
        }
    }
}