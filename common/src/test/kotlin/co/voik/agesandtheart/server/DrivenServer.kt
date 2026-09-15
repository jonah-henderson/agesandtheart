package co.voik.agesandtheart.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
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
    private val bootLog: File,
) : AutoCloseable {

    /** [command] run on the server, and its output verbatim. `/age write …`, without the slash. */
    fun run(command: String): String = rcon.run(command)

    /**
     * Everything the server has said since it started.
     *
     * **Kept because a datapack error is not fatal.** A file the server cannot read is logged and the boot
     * carries on, so nothing a command can ask will ever reveal it — the recipe simply is not there, the
     * loot modifier simply never fires. This is the only place a check can see it. See `BootLogCheck`.
     */
    fun saidSoFar(): String = if (bootLog.isFile) bootLog.readText() else ""

    /**
     * Where an Age's saved chunks live, so a check can ask whether they are really there.
     *
     * The world itself stays private: what a check has business with is one dimension's folder, and handing
     * out the root would make it possible to assert against — or worse, reach into — the rest of the save.
     */
    fun savedChunksOf(age: String): File = world.resolve("dimensions/agesandtheart/$age")

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
     * The tidying [close] does, for a run that never reaches [close] — a cancelled Gradle task, a Ctrl-C, an
     * exception out of a spec's own setup.
     *
     * **Without it a killed run leaves three things behind**: a Minecraft server holding a world lock and
     * a port, a `server.properties` still carrying the harness's RCON settings, and a `checks-…` world.
     * `PreviewServer` has had this since it was written; this did not, and the servers it stranded were
     * found running for hours.
     *
     * Forcible rather than the polite stop, for the reason `PreviewServer` gives: this runs while the JVM
     * is going down, and a shutdown hook that waits a minute for a clean save is one somebody kills again.
     * The world is a throwaway either way.
     */
    private val tidyUpIfWeAreKilled = Thread {
        LaunchSpec.endTheTree(process)
        properties.writeText(originalProperties)
        discard(world)
    }

    init {
        Runtime.getRuntime().addShutdownHook(tidyUpIfWeAreKilled)
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
        runCatching { Runtime.getRuntime().removeShutdownHook(tidyUpIfWeAreKilled) }
        properties.writeText(originalProperties)
        discard(world)
    }


    companion object {
        private const val STARTUP_SECONDS = 240L
        private const val SHUTDOWN_SECONDS = 60L
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
        fun start(level: String): DrivenServer {
            val launch = LaunchSpec.read()
            val port = ServerLaunch.freePort()
            val started = ServerLaunch.start(
                launch,
                ServerLaunch.settingsFor(level, port, RCON_PASSWORD),
                port,
                RCON_PASSWORD,
                STARTUP_SECONDS,
            )
            return DrivenServer(
                started.process,
                started.rcon,
                started.properties,
                started.originalProperties,
                launch.workingDirectory.resolve(level),
                launch.outputFile,
            )
        }

        /**
         * The one server every check shares, started when the first of them asks and stopped when the JVM
         * ends.
         *
         * Starting one per spec is the obvious shape and costs a minute a spec; there is nothing to isolate
         * between them, because each names its own Ages. `serverTest` runs specs one at a time for the same
         * reason — two servers would fight over one `server.properties`.
         */
        val shared: DrivenServer by lazy { start("checks-${System.currentTimeMillis()}") }
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

/**
 * Marks a server spec that is expensive **even among server specs** — dropped by `-Pfast`.
 *
 * Measured 2026-08-10, and the shape of it is why this tag exists rather than a general belief that server
 * checks are slow: of 167 seconds of server suite, `TempestCheck` was 87 and `DeletionCheck` 62. The other
 * eight specs came to **two seconds between them**. So dropping two specs turns four minutes into about
 * twenty seconds while still running eight tenths of the suite, which is a different instrument from the
 * full one rather than a worse version of it.
 *
 * Both earn their cost honestly — one waits out real weather, the other builds and destroys whole worlds —
 * so this is about *when* they run, not whether. They run on every unfiltered `serverTest`, which is what a
 * merge should use.
 */
const val NEEDS_TIME = "NeedsTime"
