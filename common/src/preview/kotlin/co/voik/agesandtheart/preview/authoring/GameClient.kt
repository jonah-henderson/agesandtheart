package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.server.LaunchSpec
import java.util.concurrent.TimeUnit

/**
 * A Minecraft client, started and pointed at the workshop's server.
 *
 * **Telling somebody an address is not opening an Age.** The workshop's whole promise is a key that puts
 * you in the world you are writing, and a line saying "connect to localhost:25565" leaves the last and
 * most tedious step to the person who pressed the key.
 *
 * Started from a launch spec rather than through Gradle, the same way the server is — but for a different
 * reason. The server could not use Gradle because the checks run *inside* a Gradle build; the tool does
 * not, so `./gradlew :fabric:runClient` would work and simply be slow. A recorded launch starts the game
 * in seconds where a fresh Gradle build is most of a minute.
 */
object GameClient {

    /** How long to wait for a client that will not start before saying so. */
    private const val SETTLE_MILLIS = 2000L

    /**
     * A client, opened straight onto [address].
     *
     * `--quickPlayMultiplayer` is vanilla's own "join this server on launch", which is what makes the key
     * press worth having. A client that does not understand it opens on the title screen instead, so the
     * address is always said out loud as well.
     */
    fun start(address: String, say: (String) -> Unit): Process? {
        val launch = runCatching { LaunchSpec.read("client") }.getOrElse { failure ->
            say("no client launch recorded: ${failure.message}")
            return null
        }
        say("starting Minecraft and joining $address")
        val process = runCatching { launch.start(listOf("--quickPlayMultiplayer", address)) }
            .getOrElse { failure ->
                say("could not start the client: ${failure.message}")
                return null
            }
        // A client that dies on startup dies quickly; anything still alive after a moment is loading.
        if (process.waitFor(SETTLE_MILLIS, TimeUnit.MILLISECONDS)) {
            say("the client exited straight away — see ${launch.outputFile.path}")
            return null
        }
        return process
    }

    /** Closed the way a window close would, and killed if it will not go. */
    fun stop(process: Process) {
        process.destroy()
        if (!process.waitFor(SETTLE_MILLIS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }
}
