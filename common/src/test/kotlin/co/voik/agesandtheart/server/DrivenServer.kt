package co.voik.agesandtheart.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.ConnectException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * A dedicated server, started and driven from a check — **a tool, and nothing more**.
 *
 * It boots a server, sends commands, hands back what each one said, and stops. It asserts nothing: whether
 * an answer is right is Kotest's business, which is what lets a failure carry a real diagnostic instead of
 * "nothing matched /at-least 10000/".
 *
 * **Gradle starts nothing here.** These checks run inside a Gradle-launched JVM, and a nested `./gradlew`
 * would sit waiting on the outer build's locks, so the launch is read from the spec `:fabric:exportServerLaunch`
 * writes and the JVM is started directly.
 *
 * **It writes to a throwaway world and puts `server.properties` back.** A check must never be able to touch
 * a world somebody cares about, and the file is the server's, not ours — see [close].
 */
class DrivenServer private constructor(
    private val process: Process,
    private val rcon: Rcon,
    private val properties: File,
    private val originalProperties: String,
    private val world: File,
) : AutoCloseable {

    /** [command] run on the server, and its output verbatim. `/age write …`, without the slash. */
    fun run(command: String): String = rcon.run(command)

    /**
     * [command] run in structured form, parsed. The `json` literal goes straight after the subcommand
     * because `/age write`'s sentence is greedy — see `AgeCommand.reporting`.
     */
    fun ask(subcommand: String, rest: String = ""): JsonObject {
        val said = run("age $subcommand json $rest".trim())
        val parsed = runCatching { JsonParser.parseString(said) }
            .getOrElse { failure -> error("'$subcommand $rest' did not answer with JSON: '$said' ($failure)") }
        check(parsed.isJsonObject) { "'$subcommand $rest' answered with '$said', which is not an object" }
        return parsed.asJsonObject
    }

    /**
     * Stops the server the way a console would, puts `server.properties` back however that goes, and
     * removes the world this run made.
     *
     * **The only thing here that deletes anything, and it is fenced four ways** — the directory must be one
     * this run created, must still carry the name we generated, must sit directly in the server's run
     * directory, and must not be a link. A check harness that can reach a world somebody plays is not worth
     * the disk it saves, so each of those is checked rather than assumed.
     *
     * The deletion comes last, after the process has exited: a running server holds the region files open
     * and would write them out again underneath us.
     */
    override fun close() {
        runCatching { rcon.run("stop") }
        rcon.close()
        if (!process.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) process.destroy()
        if (!process.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        properties.writeText(originalProperties)
        discard(world)
    }

    companion object {
        private const val STARTUP_SECONDS = 240L
        private const val SHUTDOWN_SECONDS = 60L
        private const val POLL_MILLIS = 500L
        private const val RCON_PASSWORD = "agesandtheart-checks"

        /**
         * What a world this harness made is called. **Load-bearing rather than cosmetic**: [discard] will
         * remove nothing whose name does not start with it, so a world a person named can never be reached
         * however the level ends up configured.
         */
        private const val CHECKS_WORLD_PREFIX = "checks-"

        /**
         * Removes a world **only** if it is one of ours, sitting where we put it, and not a link. Anything
         * else is left exactly alone and said out loud rather than silently skipped.
         */
        private fun discard(world: File) {
            val runDirectory = world.parentFile ?: return
            val isOurs = world.name.startsWith(CHECKS_WORLD_PREFIX)
            val isWhereWePutIt = runDirectory.resolve(world.name).canonicalFile == world.canonicalFile
            val isARealDirectory = world.isDirectory && world.canonicalPath == world.absolutePath
            if (!isOurs || !isWhereWePutIt || !isARealDirectory) {
                println("drive: leaving '${world.absolutePath}' alone — it is not a world this run created")
                return
            }
            check(world.deleteRecursively()) { "could not remove the check world at ${world.absolutePath}" }
        }

        /**
         * A server, up and answering. The caller closes it — Kotest's `autoClose` or a `use` block.
         *
         * [level] is the world it writes to, and **a run must name one nobody has used**: an Age cannot be
         * written twice, so a check that reuses a world sees "Age 'x' already exists" and — worse — a later
         * assertion reading `/age list` finds *last* run's Age and passes on it. Found exactly that way.
         *
         * A fresh world every run would fill a disk, so [close] removes it again. It must therefore be
         * named with [CHECKS_WORLD_PREFIX], which is what makes it safe to remove.
         */
        fun start(level: String = "checks-world"): DrivenServer {
            val launch = LaunchSpec.read()
            val properties = launch.workingDirectory.resolve("server.properties")
            check(properties.isFile) {
                "no ${properties.path} yet — run ./gradlew :fabric:runServer once to accept the EULA and " +
                    "let the server write its defaults"
            }
            val originalProperties = properties.readText()
            val port = freePort()
            properties.writeText(
                settingsFor(originalProperties, level, port),
            )

            val process = runCatching { launch.start() }
                .getOrElse { failure ->
                    properties.writeText(originalProperties)
                    throw failure
                }
            val rcon = runCatching { awaitRcon(process, port) }
                .getOrElse { failure ->
                    process.destroyForcibly()
                    properties.writeText(originalProperties)
                    throw failure
                }
            return DrivenServer(process, rcon, properties, originalProperties, launch.workingDirectory.resolve(level))
        }

        /**
         * The server's own settings with ours laid over them, key by key, so anything a person configured
         * that we say nothing about survives the run.
         */
        private fun settingsFor(original: String, level: String, port: Int): String {
            val ours = mapOf(
                "level-name" to level,
                "enable-rcon" to "true",
                "rcon.password" to RCON_PASSWORD,
                "rcon.port" to port.toString(),
                // Nothing here needs a world to be interesting, and generating one costs the whole startup.
                "sync-chunk-writes" to "false",
                // **The watchdog has to go, or the harness kills its own server.** RCON runs a command on
                // the server thread and waits for it, so `/age compare` generating two Ages block for block
                // happens *inside one tick* — and vanilla treats a tick past `max-tick-time` as a crash and
                // forcibly shuts down. A check that is merely slow would then fail as a broken pipe.
                "max-tick-time" to "-1",
            )
            val rewritten = original.lines().map { line ->
                val key = line.substringBefore('=')
                if (line.startsWith('#') || key !in ours) line else "$key=${ours.getValue(key)}"
            }
            val added = ours.filterKeys { key -> original.lines().none { it.substringBefore('=') == key } }
            return (rewritten + added.map { (key, value) -> "$key=$value" }).joinToString("\n", postfix = "\n")
        }

        /**
         * Waits for RCON to answer, and **gives up the moment the server dies** rather than at the timeout:
         * a server that failed to start is the common case when something else is wrong, and four minutes of
         * silence is a bad way to be told.
         */
        private fun awaitRcon(process: Process, port: Int): Rcon {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_SECONDS)
            while (System.nanoTime() < deadline) {
                check(process.isAlive) { "the server exited before RCON came up (code ${process.exitValue()})" }
                val connected = runCatching { Rcon("127.0.0.1", port, RCON_PASSWORD) }
                connected.getOrNull()?.let { return it }
                if (connected.exceptionOrNull() !is ConnectException) Thread.sleep(POLL_MILLIS)
                Thread.sleep(POLL_MILLIS)
            }
            error("the server did not open RCON on $port within ${STARTUP_SECONDS}s")
        }

        /** A port nobody is on. Racy in principle; in practice this is one process on a developer's machine. */
        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /**
         * The one server every check shares, started when the first of them asks and stopped when the JVM
         * ends.
         *
         * Starting one per spec is the obvious shape and costs a minute a spec; there is nothing to isolate
         * between them, because each names its own Ages. `serverTest` runs specs one at a time for the same
         * reason — two servers would fight over one `server.properties`.
         *
         * The shutdown hook is what puts that file back, so a check that dies mid-run still leaves the
         * server's settings as it found them.
         */
        val shared: DrivenServer by lazy {
            start("checks-${System.currentTimeMillis()}").also { server ->
                Runtime.getRuntime().addShutdownHook(Thread { runCatching { server.close() } })
            }
        }
    }
}

/** How to start the server, as `:fabric:exportServerLaunch` wrote it down. */
private class LaunchSpec(
    val workingDirectory: File,
    private val mainClass: String,
    private val jvmArguments: List<String>,
    private val arguments: List<String>,
) {
    fun start(): Process {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        return ProcessBuilder(listOf(java) + jvmArguments + mainClass + arguments)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            // The server's own log is the one worth reading, and it writes one; this would only duplicate it.
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    companion object {
        fun read(): LaunchSpec {
            val spec = candidatePaths().firstOrNull { it.isFile }
                ?: error(
                    "no server launch spec — run ./gradlew :fabric:exportServerLaunch first (the " +
                        "serverTest task does it for you)",
                )
            val fields = spec.readLines().filter { it.isNotBlank() }
                .map { it.substringBefore('\t') to it.substringAfter('\t') }
            fun all(key: String) = fields.filter { it.first == key }.map { it.second }
            fun one(key: String) = all(key).singleOrNull() ?: error("$spec names no single $key")
            return LaunchSpec(File(one("workingDir")), one("mainClass"), all("jvmArg"), all("arg"))
        }

        // Gradle runs the test task from the module directory, but a run from the repository root is the
        // thing anyone tries first — so both are looked at rather than one being the wrong guess.
        private fun candidatePaths() = listOf(
            File("../fabric/build/server-launch.txt"),
            File("fabric/build/server-launch.txt"),
        )
    }
}

/**
 * Marks a spec that starts a real server — minutes rather than seconds, so it is **excluded from
 * `:common:test`** and run by `./gradlew :common:serverTest`.
 *
 * The offline suite is the loop anyone runs a hundred times a day and it is worth keeping at seconds. This
 * is the other kind: what only a server can answer, because the vocabulary derives from its registries and
 * the worlds have to be generated.
 */
const val NEEDS_SERVER = "NeedsServer"
