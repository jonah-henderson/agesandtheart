package co.voik.agesandtheart.server

import java.io.File

/**
 * How to start the server, as `:fabric:exportServerLaunch` wrote it down.
 *
 * **In `preview` rather than `test` because two things start a server this way** — `DrivenServer`, and the
 * word-authoring tool refreshing what only a server knows. Neither may start one through Gradle: the
 * checks run inside a Gradle-launched JVM and would wait on the outer build's locks, and the tool wants a
 * terminal Gradle will not give it.
 */
class LaunchSpec(
    val workingDirectory: File,
    private val mainClass: String,
    private val jvmArguments: List<String>,
    private val arguments: List<String>,
    /** Which launch this is — it names the log, so a client's output is not filed under the checks'. */
    private val which: String = "server",
) {
    /**
     * Started with its output **kept**, into [outputFile].
     *
     * It used to be discarded, on the reasoning that the server writes its own log. That was true and it
     * cost us: a datapack file the server cannot read is *logged* and not fatal, so two broken data files
     * sat in the NeoForge build being reported on every boot and caught by nothing — the checks could not
     * see the log, and a person only sees it if they happen to run the client. See `BootLogCheck`.
     */
    fun start(extra: List<String> = emptyList()): Process {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val watched = listOf("-D$LAUNCHED_BY=${ProcessHandle.current().pid()}")
        val process = ProcessBuilder(listOf(java) + watched + jvmArguments + mainClass + arguments + extra)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(outputFile))
            .start()
        Runtime.getRuntime().addShutdownHook(Thread { endTheTree(process) })
        return process
    }

    /** Beside the launch spec, so it is found where the thing that produced it lives. */
    val outputFile: File = File(workingDirectory, "$which-boot.log")

    companion object {
        /**
         * **Three ways a launched server is stopped, because one is never enough.**
         *
         * A caller closing tidily is the first and the only one that saves anything — `DrivenServer.close`
         * and `PreviewServer.close` both stop the server over RCON and wait for it. The other two are here
         * because a tool is not always closed tidily, and a Minecraft server left running holds a world
         * lock, a port, and a core.
         *
         * - **A shutdown hook**, registered by [start] for every process it makes. Covers an exception, a
         *   `System.exit`, a Ctrl-C and a `SIGTERM` — which between them are how a killed Gradle run, a
         *   cancelled test task and an interrupted tool all end.
         * - **A watchdog inside the launched server**, told our own process id through [LAUNCHED_BY].
         *   Nothing in this JVM runs when it is `SIGKILL`ed, so that case can only be answered from the
         *   other end: the server notices its launcher is gone and halts itself. See `LauncherWatch`.
         *
         * The tree rather than the process: a launch may go through a wrapper, and killing the wrapper
         * leaves the server it started.
         */
        fun endTheTree(process: Process) {
            if (!process.isAlive) return
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(FORCIBLE_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        }

        /** What a launched server is told its launcher's process id under — read by `LauncherWatch`. */
        const val LAUNCHED_BY = "agesandtheart.launchedBy"

        private const val FORCIBLE_SECONDS = 10L

        /**
         * The launch [which] names — `server`, or `client` for the age workshop's preview game.
         *
         * One reader for both because the file is the same shape and the two mistakes are the same two:
         * the spec has not been written yet, or it was written for the other loader.
         */
        fun read(which: String = "server"): LaunchSpec {
            val spec = candidatePaths(which).firstOrNull { it.isFile }
                ?: error(
                    "no $which launch spec for ${loader()} — run " +
                        "./gradlew :${loader()}:export${which.replaceFirstChar(Char::titlecase)}Launch first",
                )
            val fields = spec.readLines().filter { it.isNotBlank() }
                .map { it.substringBefore('\t') to it.substringAfter('\t') }
            fun all(key: String) = fields.filter { it.first == key }.map { it.second }
            fun one(key: String) = all(key).singleOrNull() ?: error("$spec names no single $key")
            return LaunchSpec(File(one("workingDir")), one("mainClass"), all("jvmArg"), all("arg"), which)
        }

        /**
         * Which loader's server is started — Fabric unless told otherwise.
         *
         * `-Pchecks.loader=neoforge` on `:common:serverTest` points the whole suite at the other side. The
         * checks themselves know nothing about it and must not: what they assert is the *mod's* behaviour,
         * and a check that passed on one loader and not the other would be saying something worth hearing
         * rather than something worth special-casing.
         */
        private fun loader(): String = System.getProperty(LOADER_PROPERTY, "fabric")

        const val LOADER_PROPERTY = "agesandtheart.checks.loader"

        // Gradle runs the test task from the module directory, but a run from the repository root is the
        // thing anyone tries first — so both are looked at rather than one being the wrong guess.
        private fun candidatePaths(which: String) = listOf(
            File("../${loader()}/build/$which-launch.txt"),
            File("${loader()}/build/$which-launch.txt"),
        )
    }
}
