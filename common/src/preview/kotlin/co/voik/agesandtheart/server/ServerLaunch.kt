package co.voik.agesandtheart.server

import java.io.File
import java.net.ConnectException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * The awkward parts of starting a dedicated server and getting RCON to answer — shared by the checks and
 * by the word-authoring tool, which start one for different reasons and get these wrong the same way.
 *
 * Policy stays with each caller: which world, and whether to remove it afterwards. What is here is only what
 * was learned the hard way and should not be learned twice — starting one, stopping one, and the fence a
 * removal has to clear.
 */
object ServerLaunch {

    private const val POLL_MILLIS = 500L

    /**
     * The server's own settings with [ours] laid over them, key by key, so anything a person configured
     * that we say nothing about survives the run.
     */
    fun overlaid(original: String, ours: Map<String, String>): String {
        val rewritten = original.lines().map { line ->
            val key = line.substringBefore('=')
            if (line.startsWith('#') || key !in ours) line else "$key=${ours.getValue(key)}"
        }
        val added = ours.filterKeys { key -> original.lines().none { it.substringBefore('=') == key } }
        return (rewritten + added.map { (key, value) -> "$key=$value" }).joinToString("\n", postfix = "\n")
    }

    /**
     * **The watchdog has to go, or whatever started the server kills it.** RCON runs a command on the
     * server thread and waits for it, so `/age compare` generating two Ages block for block happens
     * *inside one tick* — and vanilla treats a tick past `max-tick-time` as a crash and forcibly shuts
     * down. Something merely slow would then fail as a broken pipe.
     */
    fun settingsFor(level: String, port: Int, password: String) = mapOf(
        "level-name" to level,
        "enable-rcon" to "true",
        "rcon.password" to password,
        "rcon.port" to port.toString(),
        // **A port of its own, never whatever the file happened to hold.** Nothing connects to a driven
        // server — it is spoken to over RCON — so binding the default meant colliding with any Minecraft
        // already running, including the age workshop's, and failing with a clean exit code and a line
        // buried in a log nobody was reading.
        "server-port" to freePort().toString(),
        // Nothing here needs a world to be interesting, and generating one costs the whole startup.
        "sync-chunk-writes" to "false",
        "max-tick-time" to "-1",
        // Nobody ever joins, so a server that pauses when empty stops generating forced chunks a minute in.
        "pause-when-empty-seconds" to "0",
    )

    /** A port nobody is on. Racy in principle; in practice this is one process on a developer's machine. */
    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Long enough for a cold first boot, which generates a world and warms every registry. */
    const val STARTUP_SECONDS = 240L

    /** How long each of a stop's two waits gives a server to save and exit before it is pushed harder. */
    const val SHUTDOWN_SECONDS = 60L

    /** A server [start] brought up, which puts `server.properties` back however it is stopped. */
    class Started(val process: Process, val rcon: Rcon, val properties: File, val originalProperties: String) {

        private var shutdownHook: Thread? = null

        private var stopped = false

        /**
         * The tidying [stop] does, for a JVM that goes down before reaching it — a cancelled Gradle task, a
         * Ctrl-C, an exception out of a caller's own setup: the server's process tree ended,
         * `server.properties` put back, and then [alsoWhenKilled].
         *
         * **Forcible rather than polite**: this runs while the JVM is going down, and a shutdown hook that
         * waits a minute for a clean save is one somebody kills again. `SIGKILL` reaches none of this, which
         * is what `LauncherWatch` is for.
         */
        fun tidyUpIfKilled(alsoWhenKilled: () -> Unit = {}) {
            val hook = Thread {
                LaunchSpec.endTheTree(process)
                properties.writeText(originalProperties)
                alsoWhenKilled()
            }
            Runtime.getRuntime().addShutdownHook(hook)
            shutdownHook = hook
        }

        /**
         * Stops the server the way a console would, and puts `server.properties` back however that goes.
         *
         * `stop` over RCON, then [shutdownSeconds] for it to save and exit, then `destroy`, then the same wait
         * and `destroyForcibly`. The shutdown hook is let go only once the process has gone, so a JVM killed
         * during the wait still tidies up. Does nothing the second time.
         */
        fun stop(shutdownSeconds: Long = SHUTDOWN_SECONDS) {
            if (stopped) return
            stopped = true
            runCatching { rcon.run("stop") }
            rcon.close()
            if (!process.waitFor(shutdownSeconds, TimeUnit.SECONDS)) process.destroy()
            if (!process.waitFor(shutdownSeconds, TimeUnit.SECONDS)) process.destroyForcibly()
            shutdownHook?.let { hook -> runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
            properties.writeText(originalProperties)
        }
    }

    /**
     * Whether [world] is a throwaway world that may be removed — **the only fence between a deletion and a
     * world somebody plays**, stated once for every caller that removes one.
     *
     * Three fences, and each is a way a real save could otherwise be reached: it must carry the [prefix]
     * the caller generated its name with, sit directly in the run directory where it was put, and be a real
     * directory rather than a link into one.
     */
    fun isOursToRemove(world: File, prefix: String): Boolean {
        val runDirectory = world.parentFile ?: return false
        val isOurs = world.name.startsWith(prefix)
        val isWhereWePutIt = runDirectory.resolve(world.name).canonicalFile == world.canonicalFile
        val isARealDirectory = world.isDirectory && world.canonicalPath == world.absolutePath
        return isOurs && isWhereWePutIt && isARealDirectory
    }

    /**
     * A server started from [launch] with [settings] laid over its `server.properties`, once RCON on
     * [rconPort] answers.
     *
     * Stopping it is the caller's, through [Started.stop]. A start that fails here puts the file back
     * itself, and kills the process if there is one.
     */
    fun start(
        launch: LaunchSpec,
        settings: Map<String, String>,
        rconPort: Int,
        password: String,
        startupSeconds: Long = STARTUP_SECONDS,
    ): Started {
        val properties = launch.workingDirectory.resolve("server.properties")
        check(properties.isFile) {
            "no ${properties.path} yet — run ./gradlew :${LaunchSpec.loader()}:runServer once to accept the EULA " +
                "and let the server write its defaults"
        }
        val original = properties.readText()
        properties.writeText(overlaid(original, settings))
        val process = runCatching { launch.start() }
            .getOrElse { failure -> properties.writeText(original); throw failure }
        val rcon = runCatching { awaitRcon(process, rconPort, startupSeconds, password) }
            .getOrElse { failure ->
                process.destroyForcibly()
                properties.writeText(original)
                throw failure
            }
        return Started(process, rcon, properties, original)
    }

    /**
     * Waits for RCON to answer, and **gives up the moment the server dies** rather than at the timeout: a
     * server that failed to start is the common case when something else is wrong, and four minutes of
     * silence is a bad way to be told.
     */
    fun awaitRcon(process: Process, port: Int, seconds: Long, password: String): Rcon {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            check(process.isAlive) { "the server exited before RCON came up (code ${process.exitValue()})" }
            val connected = runCatching { Rcon("127.0.0.1", port, password) }
            connected.getOrNull()?.let { return it }
            if (connected.exceptionOrNull() !is ConnectException) Thread.sleep(POLL_MILLIS)
            Thread.sleep(POLL_MILLIS)
        }
        error("the server did not open RCON on $port within ${seconds}s")
    }
}
