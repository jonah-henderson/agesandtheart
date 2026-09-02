package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.server.LaunchSpec
import co.voik.agesandtheart.server.Rcon
import co.voik.agesandtheart.server.ServerLaunch
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * A Minecraft server the workshop can put an Age into and a person can walk around in.
 *
 * **A whole server rather than a preview of one**, because the question the workshop cannot answer offline
 * is the only one that matters at the end: what it is like to stand in. Resolution is exact here already -
 * the composition, the cost, the flaws - and none of that says whether the sky is worth looking at.
 *
 * The world is thrown away. Stood up from nothing every time and removed on the way out, which is what
 * makes it safe to write a hundred half-finished Ages into: nothing accumulates, and there is no save
 * anybody could come to care about. The overworld is generated **flat** for the same reason - it is a
 * lobby you link out of, and generating a real one is most of the startup.
 *
 * It can also [attach] to a server already running, in which case it owns nothing: no world is removed,
 * the process is left alone, and only the Age it wrote is taken back out.
 */
class PreviewServer private constructor(
    private val rcon: Rcon,
    /** The port a person types into Minecraft. */
    val port: Int,
    /** Null where we attached to somebody else's server, which we may not stop. */
    private val process: Process?,
    private val properties: File?,
    private val originalProperties: String?,
    private val world: File?,
) : AutoCloseable {

    /** Ages this session wrote, so leaving takes them out of a server we did not make. */
    private val written = mutableSetOf<String>()

    /**
     * The server killed if the tool is, rather than left running with a world nobody will clear up.
     *
     * Forcible rather than the polite stop [close] does: this runs while the JVM is going down, and a
     * shutdown hook that waits a minute for a clean save is a shutdown hook somebody kills again.
     */
    private val stopIfWeAreKilled = Thread {
        process?.destroyForcibly()?.waitFor()
        properties?.writeText(originalProperties.orEmpty())
        world?.let { if (isOursToRemove(it)) it.deleteRecursively() }
    }

    init {
        Runtime.getRuntime().addShutdownHook(stopIfWeAreKilled)
    }

    val isOurs: Boolean get() = process != null

    /**
     * [draft] written into the server, replacing whatever stood under that name.
     *
     * Deleted first rather than written under a new name each time: an Age cannot be written twice, and a
     * workshop whose whole point is trying the same book again with one page changed would otherwise leave
     * a trail of `foo-1`, `foo-2` for somebody to sort out.
     */
    /**
     * The corpus re-read, on a server that is already running.
     *
     * `Vocabulary.of` caches against the resource manager's identity and `/reload` builds a fresh one, so
     * this is all it takes for a word written a minute ago to become sayable — no restart, which is what
     * keeps the loop worth having.
     */
    fun rereadTheCorpus(): String = rcon.run("reload")

    fun write(draft: AgeDraft): String {
        rcon.run("age delete ${draft.name}")
        val said = rcon.run("age write ${draft.name} ${draft.seed} ${draft.sentence}")
        written += draft.name
        return said
    }

    /** Who is logged in, read off vanilla's own `list`. */
    fun playersOnline(): List<String> {
        val said = rcon.run("list")
        val named = said.substringAfter(':', "").trim()
        if (named.isEmpty()) return emptyList()
        return named.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Everyone online opped, put in creative and sent to [age].
     *
     * Through `execute as @a` because `/age tp` asks for a player and RCON is the console. Opping is what
     * lets a person then run the `/age` instruments themselves, which is most of what previewing is for.
     */
    fun sendEveryoneTo(age: String): Int {
        val here = playersOnline()
        if (here.isEmpty()) return 0
        rcon.run("op @a")
        rcon.run("gamemode creative @a")
        rcon.run("execute as @a run age tp $age")
        return here.size
    }

    fun run(command: String): String = rcon.run(command)

    /**
     * Stops what we started and leaves alone what we did not.
     *
     * The world is removed **only** when this session made it, and only when it is still where we put it,
     * still carries the name we generated, and is a real directory rather than a link. The deletion comes
     * after the process has exited, because a running server holds its region files open and would write
     * them straight back out.
     */
    override fun close() {
        runCatching { Runtime.getRuntime().removeShutdownHook(stopIfWeAreKilled) }
        if (process == null) {
            // Somebody else's server: take back the Ages we wrote and nothing else.
            written.forEach { runCatching { rcon.run("age delete $it") } }
            rcon.close()
            return
        }
        runCatching { rcon.run("stop") }
        rcon.close()
        if (!process.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) process.destroy()
        if (!process.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        properties?.writeText(originalProperties.orEmpty())
        world?.let(::discard)
    }

    companion object {
        private const val STARTUP_SECONDS = 240L

        /** Long enough for a cold daemon, short enough that a hung build does not hold the tool. */
        private const val STAGING_SECONDS = 180L
        private const val SHUTDOWN_SECONDS = 60L
        private const val RCON_PASSWORD = "agesandtheart-workshop"

        /** The port Minecraft offers by default, so "localhost" is the whole address when it is free. */
        private const val USUAL_PORT = 25565

        /**
         * What a world this tool made is called. **Load-bearing rather than cosmetic**: [discard] removes
         * nothing whose name does not start with it, so a world somebody plays can never be reached
         * however the level ends up configured.
         */
        private const val WORKSHOP_WORLD_PREFIX = "workshop-"

        /**
         * A server of our own, up and answering.
         *
         * The settings are the check harness's plus what a person needs to walk in: no authentication,
         * because a development client is not signed in against a local server; creative and flight,
         * because previewing an Age is looking at it; and a flat overworld, because it is a lobby.
         *
         * **A port of its own rather than 25565.** It used to prefer the usual one so that "localhost"
         * was the whole address, which stopped being worth anything once the client is launched pointed
         * straight at it — and cost a collision with any other Minecraft the machine was running,
         * the check harness included.
         */
        fun boot(say: (String) -> Unit): PreviewServer {
            val launch = LaunchSpec.read()
            val loader = System.getProperty(LaunchSpec.LOADER_PROPERTY, "fabric")
            val properties = launch.workingDirectory.resolve("server.properties")
            check(properties.isFile) {
                "no ${properties.path} yet - run ./gradlew :$loader:runServer once to accept the EULA and " +
                    "let the server write its defaults"
            }
            val original = properties.readText()
            val world = "$WORKSHOP_WORLD_PREFIX${System.nanoTime().toString(RADIX)}"
            val rconPort = ServerLaunch.freePort()
            val gamePort = freeOrUsualPort()
            properties.writeText(
                ServerLaunch.overlaid(
                    original,
                    ServerLaunch.settingsFor(world, rconPort, RCON_PASSWORD) + mapOf(
                        "server-port" to gamePort.toString(),
                        "online-mode" to "false",
                        "gamemode" to "creative",
                        "allow-flight" to "true",
                        "spawn-protection" to "0",
                        "level-type" to "minecraft:flat",
                        "motd" to "the age workshop",
                    ),
                ),
            )
            say("starting a $loader server on :$gamePort - a minute or so the first time")
            val process = runCatching { launch.start() }
                .getOrElse { failure -> properties.writeText(original); throw failure }
            val rcon = runCatching {
                ServerLaunch.awaitRcon(process, rconPort, STARTUP_SECONDS, RCON_PASSWORD)
            }.getOrElse { failure ->
                process.destroyForcibly()
                properties.writeText(original)
                throw failure
            }
            return PreviewServer(
                rcon = rcon,
                port = gamePort,
                process = process,
                properties = properties,
                originalProperties = original,
                world = launch.workingDirectory.resolve(world),
            )
        }

        /**
         * The words on disk **staged where the game will read them**, and how long it took.
         *
         * The forge writes a word to `common/src/main/resources`; the server reads its corpus off the
         * classpath, which in a development run is `<loader>/build/resources/main`. Nothing carries the
         * one to the other, so a word written and then previewed was a word the game had never heard of —
         * it simply was not in the pack, and an Age written with it came out as though the page had been
         * left blank.
         *
         * Gradle rather than a hand-rolled copy: `processResources` is what decides what a resource *is*,
         * templates and all, and a second answer to that question is a second thing to keep in step.
         */
        fun stageTheCorpus(say: (String) -> Unit): Boolean {
            val loader = System.getProperty(LaunchSpec.LOADER_PROPERTY, "fabric")
            say("staging the corpus for $loader")
            val ran = runCatching {
                ProcessBuilder("./gradlew", "--console=plain", "-q", ":$loader:processResources")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .also { it.outputStream.close() }
                    .waitFor(STAGING_SECONDS, TimeUnit.SECONDS)
            }
            return ran.fold(
                onSuccess = { finished ->
                    if (!finished) say("gradle did not finish — the game may have an older corpus")
                    finished
                },
                onFailure = { failure ->
                    say("could not stage the corpus (${failure.message}) — the game keeps the one it has")
                    false
                },
            )
        }

        /** A server somebody already has running, spelled `host:port:password` as `--attach` spells it. */
        fun attach(where: String): PreviewServer {
            val parts = where.split(':')
            require(parts.size == 3) { "attaching wants host:port:password, not '$where'" }
            return PreviewServer(
                rcon = Rcon(parts[0], parts[1].toInt(), parts[2]),
                port = USUAL_PORT,
                process = null,
                properties = null,
                originalProperties = null,
                world = null,
            )
        }

        /** 25565 where nothing holds it, so the address is just "localhost". */
        private fun freeOrUsualPort(): Int =
            runCatching { ServerSocket(USUAL_PORT).use { USUAL_PORT } }.getOrElse { ServerLaunch.freePort() }

        private const val RADIX = 36

        /**
         * Whether this directory is one of ours to remove — **named here so a check can ask it.**
         *
         * A tool that stands up a world every time somebody glances at a half-written book will make a
         * hundred of them in an afternoon, so the removal is not tidiness but the reason the feature is
         * safe at all. Three fences, and each is a way a world somebody plays could otherwise be reached:
         * it must carry the name this tool generates, sit where this tool put it, and be a real directory
         * rather than a link into one.
         */
        fun isOursToRemove(world: File): Boolean {
            val runDirectory = world.parentFile ?: return false
            val isOurs = world.name.startsWith(WORKSHOP_WORLD_PREFIX)
            val isWhereWePutIt = runDirectory.resolve(world.name).canonicalFile == world.canonicalFile
            val isARealDirectory = world.isDirectory && world.canonicalPath == world.absolutePath
            return isOurs && isWhereWePutIt && isARealDirectory
        }

        private fun discard(world: File) {
            if (!isOursToRemove(world)) return
            world.deleteRecursively()
        }
    }
}
