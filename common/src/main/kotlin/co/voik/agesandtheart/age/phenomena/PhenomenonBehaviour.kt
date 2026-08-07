package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Phenomenon
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import com.google.gson.JsonParser
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager

/**
 * How fiercely a phenomenon happens — **datapack content** (`art/phenomenon/<key>.json`).
 *
 * Which processes exist is ours ([Phenomenon] is an enum, there being nothing behind a phenomenon in
 * vanilla to derive one from), but *how hard* one of them hits is content: a pack that wants a gentler
 * storm, or one that does not set the world alight, is a different pack rather than a differently-run
 * server (`notes/config-research.md`, "Which axis a knob belongs on").
 *
 * These were briefly config values, and that was the wrong axis (Jonah, 2026-08-07). The test is who is
 * meant to change it and whether changing it makes a different mod or the same mod run differently — and a
 * tempest that no longer burns is a different mod.
 */
data class PhenomenonBehaviour(
    /**
     * How many chunks a tempest looks at per tick, at an ordinary rung. Higher is a worse storm.
     *
     * Each visit is one of vanilla's own rolls, which is `1 in 100000` — so this many, twenty times a
     * second, is a strike somewhere near a player about every eight seconds, and `teeming` is four times
     * that. **The number is a count of rolls rather than a rate of our own**, so a tempest can never
     * strike anywhere vanilla would not have.
     *
     * Walked at 512, which read as *nearly* a tempest (Jonah, 2026-08-06): the ground was marked without
     * being worn away, which is the balance to keep, and it wanted a little more weather rather than a
     * different kind of it. Hence a quarter more.
     */
    val rolls: Int = DEFAULT_ROLLS,
    /** How far from a player it may reach, in chunks — inside a normal render distance. */
    val reach: Int = DEFAULT_REACH,
    /**
     * Creeper force, and a rung does not raise it.
     *
     * `Creeper.explosionRadius` is 3 and this is meant to read as one. A rung already multiplies how often
     * a tempest strikes; letting it multiply the crater as well would make `teeming` worse than twice over.
     */
    val blast: Float = DEFAULT_BLAST,
    /**
     * How many places around a strike catch fire. **Zero is a storm that only cracks**, which is a real way
     * to want to play and cheaper to say than a switch beside a count.
     *
     * Vanilla's `LightningBolt.spawnFire` is four tries in a 3×3×3 and only above Easy; a tempest reaches
     * further and does not ask the difficulty, because the Age it burns is one somebody chose to write.
     */
    val fireAttempts: Int = DEFAULT_FIRE_ATTEMPTS,
    /** How far out those places are tried, in blocks. */
    val fireReach: Int = DEFAULT_FIRE_REACH,
) {
    companion object {
        private const val DEFAULT_ROLLS = 640
        private const val DEFAULT_REACH = 8
        private const val DEFAULT_BLAST = 3.0f
        private const val DEFAULT_FIRE_ATTEMPTS = 24
        private const val DEFAULT_FIRE_REACH = 3

        /** What a phenomenon nobody wrote a file for does — the walked numbers, so absence changes nothing. */
        val ORDINARY = PhenomenonBehaviour()

        val CODEC: Codec<PhenomenonBehaviour> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.optionalFieldOf("rolls", DEFAULT_ROLLS).forGetter(PhenomenonBehaviour::rolls),
                Codec.INT.optionalFieldOf("reach", DEFAULT_REACH).forGetter(PhenomenonBehaviour::reach),
                Codec.FLOAT.optionalFieldOf("blast", DEFAULT_BLAST).forGetter(PhenomenonBehaviour::blast),
                Codec.INT.optionalFieldOf("fire_attempts", DEFAULT_FIRE_ATTEMPTS)
                    .forGetter(PhenomenonBehaviour::fireAttempts),
                Codec.INT.optionalFieldOf("fire_reach", DEFAULT_FIRE_REACH)
                    .forGetter(PhenomenonBehaviour::fireReach),
            ).apply(instance, ::PhenomenonBehaviour)
        }

        const val DIRECTORY = "art/phenomenon"

        /**
         * What this server currently says a phenomenon does.
         *
         * **Cached on the resource manager's identity**, exactly as `Vocabulary.of` is: `/reload` builds a
         * new one, so the cache invalidates itself and nothing has to remember to. Read per tick by
         * [Tempest], which is why it is cached at all.
         */
        fun of(server: MinecraftServer, phenomenon: Phenomenon): PhenomenonBehaviour {
            val resources = server.resourceManager
            loaded?.let { (from, known) -> if (from === resources) return known[phenomenon.key] ?: ORDINARY }
            val read = load(resources)
            loaded = resources to read
            return read[phenomenon.key] ?: ORDINARY
        }

        private var loaded: Pair<ResourceManager, Map<String, PhenomenonBehaviour>>? = null

        private fun load(resources: ResourceManager): Map<String, PhenomenonBehaviour> = buildMap {
            for ((file, resource) in resources.listResources(DIRECTORY) { it.path.endsWith(SUFFIX) }) {
                val key = file.path.removePrefix("$DIRECTORY/").removeSuffix(SUFFIX)
                val read = runCatching {
                    resource.open().use { stream ->
                        CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(stream.reader())).getOrThrow()
                    }
                }
                // A pack that writes nonsense gets the ordinary storm and a line in the log, rather than a
                // server that will not start over a number.
                read.onFailure { Constants.LOG.warn("Could not read '{}': {}", file, it.message) }
                read.getOrNull()?.let { put(key, it) }
            }
        }

        private const val SUFFIX = ".json"
    }
}
