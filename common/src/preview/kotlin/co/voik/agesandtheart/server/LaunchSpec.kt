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
        return ProcessBuilder(listOf(java) + jvmArguments + mainClass + arguments + extra)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(outputFile))
            .start()
    }

    /** Beside the launch spec, so it is found where the thing that produced it lives. */
    val outputFile: File = File(workingDirectory, "$which-boot.log")

    companion object {
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
