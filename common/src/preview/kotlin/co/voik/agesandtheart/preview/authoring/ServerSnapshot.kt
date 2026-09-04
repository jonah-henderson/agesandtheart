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
     * What each derivation rule alone caught, by [DerivationRules.Rule.id].
     *
     * The other half of the same point. 84 of the 142 rules key on a registry tag, which a running game
     * binds and an offline corpus does not, so those rules catch nothing here and the screen that shows
     * them had nothing to show. A server runs each rule alone and this is the answer, remembered.
     */
    val caught: Map<String, List<String>> = emptyMap(),
    /**
     * Every dimension the server has, ours and everyone else's.
     *
     * **Nothing reads this yet.** A base dimension is one of `AgeTemplate`'s three, named in code; but what
     * a base supplies is a chunk generator and a dimension type, which a modded dimension has as surely as
     * the nether does. Recording the list now means the data is here when something can use it.
     */
    val dimensions: List<String> = emptyList(),
    /**
     * What a registry holds, by registry id — the placed features, for now.
     *
     * **A pack's own are datapack content**, so `agesandtheart:obelisks` exists nowhere until a server has
     * loaded its packs: a word minting one could only ever be typed at, never chosen from a list.
     */
    val holdings: Map<String, List<String>> = emptyMap(),
    /**
     * A registry's tags and how many carry each, by registry id — the block tags, for now.
     *
     * By count rather than by member, which is the whole difference between a hundred kilobytes and two:
     * a picker wants the names and something to say beside each, and what is actually *in* a tag is a
     * question for the Age being generated rather than for the word being written.
     */
    val tagged: Map<String, Map<String, Int>> = emptyMap(),
) {

    data class Reach(val carriers: Int, val found: Int)

    /**
     * How far a refresh has got — [done] of [total] questions asked, and what it is asking about.
     *
     * Counted rather than guessed at, which needs the tag lists first: the questions are three about the
     * corpus, one per aspect to learn its tags, and one per tag after that. So the aspects are asked what
     * they carry up front and the total is known before the long half begins.
     */
    data class Progress(val done: Int, val total: Int, val what: String) {
        val share: Double get() = if (total <= 0) 0.0 else done.toDouble() / total
    }

    /** Every placed feature a server had, ours among them — empty where none was ever asked. */
    val placedFeatures: List<String> get() = holdings[PLACED_FEATURE_REGISTRY].orEmpty()

    /** Every block tag a server had, with how many blocks carry it. */
    val blockTags: Map<String, Int> get() = tagged[BLOCK_REGISTRY].orEmpty()

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
                "caught",
                JsonObject().apply {
                    caught.forEach { (rule, members) ->
                        add(rule, com.google.gson.JsonArray().apply { members.forEach(::add) })
                    }
                },
            )
            add(
                "holdings",
                JsonObject().apply {
                    holdings.forEach { (registry, ids) ->
                        add(registry, com.google.gson.JsonArray().apply { ids.forEach(::add) })
                    }
                },
            )
            add(
                "tagged",
                JsonObject().apply {
                    tagged.forEach { (registry, tags) ->
                        add(
                            registry,
                            JsonObject().apply { tags.forEach { (tag, carriers) -> addProperty(tag, carriers) } },
                        )
                    }
                },
            )
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

        /** The two registries the tool asks about, spelled once so the ask and the reading agree. */
        const val PLACED_FEATURE_REGISTRY = "minecraft:worldgen/placed_feature"
        const val BLOCK_REGISTRY = "minecraft:block"

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
                    // Absent in a snapshot taken before rules were asked about, which reads as "nothing
                    // remembered" rather than as a broken file — a refresh is what fills it.
                    caught = json.getAsJsonObject("caught")?.entrySet()?.associate { (rule, members) ->
                        rule to members.asJsonArray.map { it.asString }
                    }.orEmpty(),
                    // Both absent from a snapshot taken before the tool offered a list of either, which
                    // reads as "nothing remembered" — a refresh is what fills them.
                    holdings = json.getAsJsonObject("holdings")?.entrySet()?.associate { (registry, ids) ->
                        registry to ids.asJsonArray.map { it.asString }
                    }.orEmpty(),
                    tagged = json.getAsJsonObject("tagged")?.entrySet()?.associate { (registry, tags) ->
                        registry to tags.asJsonObject.entrySet().associate { (tag, carriers) ->
                            tag to carriers.asInt
                        }
                    }.orEmpty(),
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
        fun refresh(attach: String?, serverOnlyTags: Set<String>, say: (Progress) -> Unit): ServerSnapshot =
            if (attach != null) {
                attached(attach, say).use { gather(it, "attached", sourceOf(attach), serverOnlyTags, say) }
            } else {
                booted(say) { rcon, loader, world -> gather(rcon, loader, world, serverOnlyTags, say) }
            }

        /** An attached server names itself by address; its world is not ours to know. */
        private fun sourceOf(attach: String) = attach.substringBeforeLast(':')

        private fun attached(where: String, say: (Progress) -> Unit): Rcon {
            val parts = where.split(':')
            require(parts.size == 3) { "--attach wants host:port:password, not '$where'" }
            say(Progress(0, 0, "attaching to ${parts[0]}:${parts[1]}"))
            return Rcon(parts[0], parts[1].toInt(), parts[2])
        }

        /**
         * Starts a server, does [work] on it, and puts `server.properties` back however that goes.
         *
         * The world is left where it is. This tool has no business removing one, and keeping it is what
         * makes a second refresh quick.
         */
        private fun <T> booted(say: (Progress) -> Unit, work: (Rcon, String, String) -> T): T {
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
            say(Progress(0, 0, "starting a $loader server on :$port — a minute or so the first time"))
            val process = runCatching { launch.start() }
                .getOrElse { failure -> properties.writeText(original); throw failure }
            try {
                val rcon = runCatching {
                    ServerLaunch.awaitRcon(process, port, STARTUP_SECONDS, RCON_PASSWORD)
                }.getOrElse { failure -> process.destroyForcibly(); throw failure }
                val world = shortly(launch.workingDirectory.resolve(WORLD))
                return rcon.use { work(it, loader, world) }.also { say(Progress(0, 0, "stopping the server")) }
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
            say: (Progress) -> Unit,
        ): ServerSnapshot {
            fun ask(command: String): JsonObject =
                JsonParser.parseString(rcon.run(command)).asJsonObject

            val corpus = ask("age words json")
            say(Progress(0, 0, "asking what each tagging rule catches"))
            val caught = ask("age rules json all").getAsJsonArray("rule")?.associate { entry ->
                val rule = entry.asJsonObject
                rule.get("id").asString to
                    rule.getAsJsonArray("caught")?.map { it.asString }.orEmpty()
            }.orEmpty()
            say(Progress(0, 0, "asking what features it can place"))
            val holdings = mapOf(
                PLACED_FEATURE_REGISTRY to (
                    ask("age holdings json $PLACED_FEATURE_REGISTRY").getAsJsonArray("holding")
                        ?.map { it.asJsonObject.get("id").asString }.orEmpty()
                    ),
            )
            say(Progress(0, 0, "asking what its block tags are"))
            val tagged = mapOf(
                BLOCK_REGISTRY to (
                    ask("age holdings json $BLOCK_REGISTRY tags").getAsJsonArray("tag")
                        ?.associate { it.asJsonObject.get("id").asString to it.asJsonObject.get("carriers").asInt }
                        .orEmpty()
                    ),
            )
            say(Progress(0, 0, "asking what dimensions it has"))
            val dimensions = ask("age dimensions json all").getAsJsonArray("dimension")
                ?.map { it.asJsonObject.get("id").asString }.orEmpty()
            // **The tag lists first, so the long half can be counted.** Everything after this is one
            // question per tag, and a bar that cannot say how many there are is a spinner with a number
            // on it.
            val carrying = Aspect.entries.associateWith { aspect ->
                say(Progress(0, 0, "asking ${aspect.page} what it carries"))
                ask("age tags json ${aspect.page}").getAsJsonArray("tags")
                    ?.map { it.asJsonObject.get("tag").asString }.orEmpty()
            }
            val total = carrying.values.sumOf { it.size }
            var asked = 0
            val reach = mutableMapOf<String, Map<String, Reach>>()
            val serverOnly = mutableMapOf<String, MutableList<String>>()
            for ((aspect, tags) in carrying) {
                val seen = mutableMapOf<String, Reach>()
                for (tag in tags) {
                    asked++
                    say(Progress(asked, total, "${aspect.page} $tag"))
                    val answer = ask("age tags json ${aspect.page} $tag")
                    seen[tag] = Reach(
                        answer.get("carriers")?.asInt ?: 0,
                        answer.get("found")?.asInt ?: 0,
                    )
                    if (tag !in serverOnlyTags) continue
                    // Only these are listed by member: they are the ones an offline corpus cannot name at
                    // all, and every other tag's members are already sitting in the pack's own tables.
                    answer.getAsJsonArray("carrying")?.forEach { member ->
                        serverOnly.getOrPut(tag) { mutableListOf() } += member.asJsonObject.get("member").asString
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
                caught = caught,
                dimensions = dimensions,
                holdings = holdings,
                tagged = tagged,
            )
        }
    }
}
