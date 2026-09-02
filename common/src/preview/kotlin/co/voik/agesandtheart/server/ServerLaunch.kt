package co.voik.agesandtheart.server

import java.net.ConnectException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * The awkward parts of starting a dedicated server and getting RCON to answer — shared by the checks and
 * by the word-authoring tool, which start one for different reasons and get these wrong the same way.
 *
 * Policy stays with each caller: which world, whether to remove it afterwards, how long to wait. What is
 * here is only what was learned the hard way and should not be learned twice.
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
    )

    /** A port nobody is on. Racy in principle; in practice this is one process on a developer's machine. */
    fun freePort(): Int = ServerSocket(0).use { it.localPort }

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
