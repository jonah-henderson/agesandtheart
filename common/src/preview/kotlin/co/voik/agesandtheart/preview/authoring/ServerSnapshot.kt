package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.server.LaunchSpec
import co.voik.agesandtheart.server.Rcon
import co.voik.agesandtheart.server.ServerLaunch
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * What a running server knows that an offline corpus cannot — **remembered, so it is never needed twice.**
 *
 * Two things are missing offline and only two: a tag `Vocabulary.tagsOnlyAServerGrants` names, which no
 * authored table and no derivation fact grants (`ore` is the case), and whatever content a modpack adds.
 * Everything else about a word — its reach, its price, what its query keeps, what it disagrees with — is
 * computed live from the real code and is exact without any of this.
 *
 * So a refresh is an errand rather than a dependency. Run it when the tag layer moves or the modpack does;
 * the tool reads the file on every later run and says how old it is rather than pretending it is now.
 */
data class ServerSnapshot(
    val taken: Instant,
    val loader: String,
    /**
     * The world this was read out of, or the server it was read off.
     *
     * **Which world matters as much as when.** A snapshot taken against a vanilla server and one taken
     * against a modpack say different things about the same tag, and a line saying only how old it is
     * cannot tell them apart — so the tool says where it came from and lets a reader notice.
     */
    val source: String = "",
    val words: Int,
    /** Per aspect page, per tag: what carries it at all, and what a restrictive word would keep. */
    val reach: Map<String, Map<String, Reach>>,
    /** The members carrying a tag nothing offline can grant — the whole point of asking a server. */
    val serverOnly: Map<String, List<String>>,
    /**
     * Every dimension the server has, ours and everyone else's.
     *
     * **Nothing reads this yet.** A base dimension is one of `AgeTemplate`'s three, named in code; but what
     * a base supplies is a chunk generator and a dimension type, which a modded dimension has as surely as
     * the nether does. Recording the list now means the data is here when something can use it.
     */
    val dimensions: List<String> = emptyList(),
) {

    data class Reach(val carriers: Int, val found: Int)

    /** What a server said this tag reaches in this aspect, or null where the snapshot never asked. */
    fun reachOf(aspect: Aspect, tag: String): Reach? = reach[aspect.page]?.get(tag)

    /** When it was read, to the minute, in whatever zone the reader is in. */
    val importedAt: String
        get() = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(taken)

    /** Where it came from and when, in the words every screen shows it in. */
    val provenance: String
        get() = "data imported from ${source.ifEmpty { "an unrecorded world" }} at $importedAt"

    /** Where [write] put it, for whatever wants to say so. */
    fun snapshotPath(): String = FILE.path

    fun write() {
        val json = JsonObject().apply {
            addProperty("taken", taken.toString())
            addProperty("source", source)
            addProperty("loader", loader)
            addProperty("words", words)
            add(
                "reach",
                JsonObject().apply {
                    reach.forEach { (page, tags) ->
                        add(
                            page,
                            JsonObject().apply {
                                tags.forEach { (tag, seen) ->
                                    add(
                                        tag,
                                        JsonObject().apply {
                                            addProperty("carriers", seen.carriers)
                                            addProperty("found", seen.found)
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
            add("dimensions", com.google.gson.JsonArray().apply { dimensions.forEach(::add) })
            add(
                "server_only",
                JsonObject().apply {
                    serverOnly.forEach { (tag, members) ->
                        add(tag, com.google.gson.JsonArray().apply { members.forEach(::add) })
                    }
                },
            )
        }
        FILE.parentFile.mkdirs()
        FILE.writeText(GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(json) + "\n")
    }

    companion object {
        /**
         * Beside the repository rather than under `build/`, deliberately: `./gradlew clean` would throw it
         * away, and the whole point of a snapshot is that it outlives the errand that made it. Git-ignored.
         */
        val FILE = File(".authoring/server-snapshot.json")

        private const val RCON_PASSWORD = "agesandtheart-authoring"
        private const val STARTUP_SECONDS = 240L
        private const val SHUTDOWN_SECONDS = 60L

        /**
         * The world the tool's own server writes to.
         *
         * **Kept between runs, unlike the checks' throwaway world.** Nothing here generates terrain — every
         * question is about registries and tags — so reusing one world means only the first refresh pays a
         * world's creation, and the tool deletes nothing at all.
         */
        private const val WORLD = "authoring-world"

        fun read(): ServerSnapshot? {
            if (!FILE.isFile) return null
            return runCatching {
                val json = JsonParser.parseString(FILE.readText()).asJsonObject
                ServerSnapshot(
                    taken = Instant.parse(json.get("taken").asString),
                    // Absent from a snapshot taken before the tool recorded it.
                    source = json.get("source")?.asString.orEmpty(),
                    loader = json.get("loader").asString,
                    words = json.get("words").asInt,
                    reach = json.getAsJsonObject("reach").entrySet().associate { (page, tags) ->
                        page to tags.asJsonObject.entrySet().associate { (tag, seen) ->
                            tag to Reach(
                                seen.asJsonObject.get("carriers").asInt,
                                seen.asJsonObject.get("found").asInt,
                            )
                        }
                    },
                    serverOnly = json.getAsJsonObject("server_only").entrySet().associate { (tag, members) ->
                        tag to members.asJsonArray.map { it.asString }
                    },
                    dimensions = json.getAsJsonArray("dimensions")?.map { it.asString }.orEmpty(),
                )
            }.getOrNull()
        }

        /**
         * A server asked what it carries, and the answer written down.
         *
         * [attach] points at one that is already up — `host:port:password`; without it the tool starts its
         * own from the spec `:fabric:exportServerLaunch` wrote, exactly as `DrivenServer` does and for the
         * same reason: a nested Gradle build would wait forever on the outer one's locks.
         *
         * [say] is how progress reaches whoever asked, since this is minutes on a first run.
         */
        fun refresh(attach: String?, serverOnlyTags: Set<String>, say: (String) -> Unit): ServerSnapshot =
            if (attach != null) {
                attached(attach, say).use { gather(it, "attached", sourceOf(attach), serverOnlyTags, say) }
            } else {
                booted(say) { rcon, loader, world -> gather(rcon, loader, world, serverOnlyTags, say) }
            }

        /** An attached server names itself by address; its world is not ours to know. */
        private fun sourceOf(attach: String) = attach.substringBeforeLast(':')

        private fun attached(where: String, say: (String) -> Unit): Rcon {
            val parts = where.split(':')
            require(parts.size == 3) { "--attach wants host:port:password, not '$where'" }
            say("attaching to ${parts[0]}:${parts[1]}")
            return Rcon(parts[0], parts[1].toInt(), parts[2])
        }

        /**
         * Starts a server, does [work] on it, and puts `server.properties` back however that goes.
         *
         * The world is left where it is. This tool has no business removing one, and keeping it is what
         * makes a second refresh quick.
         */
        private fun <T> booted(say: (String) -> Unit, work: (Rcon, String, String) -> T): T {
            val launch = LaunchSpec.read()
            val loader = System.getProperty(LaunchSpec.LOADER_PROPERTY, "fabric")
            val properties = launch.workingDirectory.resolve("server.properties")
            check(properties.isFile) {
                "no ${properties.path} yet — run ./gradlew :$loader:runServer once to accept the EULA and " +
                    "let the server write its defaults"
            }
            val original = properties.readText()
            val port = ServerLaunch.freePort()
            properties.writeText(
                ServerLaunch.overlaid(original, ServerLaunch.settingsFor(WORLD, port, RCON_PASSWORD)),
            )
            say("starting a $loader server on :$port — a minute or so the first time")
            val process = runCatching { launch.start() }
                .getOrElse { failure -> properties.writeText(original); throw failure }
            try {
                val rcon = runCatching {
                    ServerLaunch.awaitRcon(process, port, STARTUP_SECONDS, RCON_PASSWORD)
                }.getOrElse { failure -> process.destroyForcibly(); throw failure }
                val world = shortly(launch.workingDirectory.resolve(WORLD))
                return rcon.use { work(it, loader, world) }.also { say("stopping the server") }
            } finally {
                runCatching { Rcon("127.0.0.1", port, RCON_PASSWORD).use { it.run("stop") } }
                if (!process.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
                properties.writeText(original)
            }
        }

        /**
         * Everything the snapshot holds, asked through `/age tags` and `/age words` — the instruments the
         * tag pass already built, which is why this needed no command of its own.
         */
        /** A path under the directory the tool was started from, which is shorter and says as much. */
        private fun shortly(world: File): String =
            world.absoluteFile.relativeToOrNull(File("").absoluteFile)?.path ?: world.absolutePath

        private fun gather(
            rcon: Rcon,
            loader: String,
            source: String,
            serverOnlyTags: Set<String>,
            say: (String) -> Unit,
        ): ServerSnapshot {
            fun ask(command: String): JsonObject =
                JsonParser.parseString(rcon.run(command)).asJsonObject

            val corpus = ask("age words json")
            say("asking what dimensions it has")
            val dimensions = ask("age dimensions json all").getAsJsonArray("dimension")
                ?.map { it.asJsonObject.get("id").asString }.orEmpty()
            val reach = mutableMapOf<String, Map<String, Reach>>()
            val serverOnly = mutableMapOf<String, MutableList<String>>()
            for (aspect in Aspect.entries) {
                say("asking ${aspect.page} what it carries")
                val tags = ask("age tags json ${aspect.page}").getAsJsonArray("tags")
                    ?.map { it.asJsonObject.get("tag").asString }.orEmpty()
                val seen = mutableMapOf<String, Reach>()
                for (tag in tags) {
                    val answer = ask("age tags json ${aspect.page} $tag")
                    seen[tag] = Reach(
                        answer.get("carriers")?.asInt ?: 0,
                        answer.get("found")?.asInt ?: 0,
                    )
                    if (tag !in serverOnlyTags) continue
                    // Only these are listed by member: they are the ones an offline corpus cannot name at
                    // all, and every other tag's members are already sitting in the pack's own tables.
                    answer.getAsJsonArray("carrying")?.forEach { carrying ->
                        serverOnly.getOrPut(tag) { mutableListOf() } += carrying.asJsonObject.get("member").asString
                    }
                }
                if (seen.isNotEmpty()) reach[aspect.page] = seen
            }
            return ServerSnapshot(
                taken = Instant.now(),
                source = source,
                loader = loader,
                words = corpus.get("words")?.asInt ?: 0,
                reach = reach,
                serverOnly = serverOnly,
                dimensions = dimensions,
            )
        }
    }
}
