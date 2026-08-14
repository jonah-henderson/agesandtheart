package co.voik.agesandtheart.age.aspect

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier

/**
 * **How a creature the world never offered arrives in one** — `art/spawning.json`.
 *
 * A biome's spawn list is what may be *tried*, not what will appear: every attempt goes through
 * `SpawnPlacements.isSpawnPositionOk` and `checkSpawnRules`, so a cod named into a desert is refused by
 * vanilla and never seen. That is what makes adding safe, and it is why this file is small — for almost
 * every creature the only question is how often to try.
 *
 * **The exceptions are the creatures vanilla never spawns at all.** An entity type with no registered
 * placement gets `NO_RESTRICTIONS` and a predicate that says yes, so a dragon, a wither or a golem would
 * arrive inside a mountain as readily as on a field. Those are the ones [Arrival.needsOpenSky] is for: it
 * is the rule vanilla would have written if it had ever meant them to be found.
 */
data class Spawning(
    private val byType: Map<String, Arrival> = emptyMap(),
    /** What a creature nobody wrote a line for arrives as. */
    val ordinary: Arrival = Arrival(),
) {
    fun of(id: Identifier): Arrival = byType[id.toString()] ?: ordinary

    /**
     * Every creature named here, which is what makes one **writable at all** where vanilla would never
     * have grown it: `DerivedWords.spawns` gives no word to a `MobCategory.MISC` entity — an arrow and a
     * boat are not creatures — and the golems are misc because they are built rather than born.
     */
    val writable: Set<Identifier> get() = byType.keys.mapNotNull(Identifier::tryParse).toSet()

    /** This file with [later] laid over it, entry by entry, so a pack may retune one creature. */
    fun mergedWith(later: Spawning): Spawning = Spawning(byType + later.byType, later.ordinary)

    companion object {
        /** Where a pack puts it. One file: it is a short list of exceptions, not a table. */
        const val FILE = "art/spawning.json"

        val CODEC: Codec<Spawning> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Codec.STRING, Arrival.CODEC).optionalFieldOf("creatures", emptyMap())
                    .forGetter { it.byType },
                Arrival.CODEC.optionalFieldOf("ordinary", Arrival()).forGetter(Spawning::ordinary),
            ).apply(instance, ::Spawning)
        }
    }
}

/**
 * How often a creature is tried and how many arrive at once — vanilla's own `SpawnerData`, plus the one
 * thing vanilla has no way to say about a creature it never spawns.
 */
data class Arrival(
    /**
     * How often it is *tried*, against everything else the biome offers. Vanilla's monsters sit near a
     * hundred each, so single digits here is a creature you come across rather than one you wade through.
     */
    val weight: Int = 8,
    val least: Int = 1,
    val most: Int = 2,
    /**
     * Whether it may only be tried where the sky is open above the ground.
     *
     * **For the creatures vanilla never spawns**, which have no placement rules and would otherwise be
     * tried inside solid rock. A dragon in a cave is not a surprise, it is a bug with wings.
     */
    val needsOpenSky: Boolean = false,
    /**
     * How far apart attempts at this creature are held, in blocks — nothing for the ordinary ones.
     *
     * **A grid rather than a count, and for the same reason the star fissure uses one**: a spawn attempt
     * knows its position and nothing else, so a rule about *how many* is not answerable there where a rule
     * about *where* is. A dragon may only be tried in one window per cell, so an Age of dragons has them
     * spread across the country instead of seventy in one valley — which is the difference between the
     * sentence being worth writing and the sentence being unplayable.
     */
    val apartBy: Int = 0,
) {
    /**
     * Whether an attempt at ([x], [z]) is one of the few places this creature may be tried at all.
     *
     * The window is one chunk of each cell, so the thinning is `(16 / apartBy)²` and a dragon held 256
     * blocks apart is tried in a four-hundredth of the places a zombie is.
     */
    fun mayBeTriedAt(x: Int, z: Int): Boolean {
        if (apartBy <= 0) return true
        return Math.floorMod(x, apartBy) < WINDOW_BLOCKS && Math.floorMod(z, apartBy) < WINDOW_BLOCKS
    }
    companion object {
        /** One chunk of each cell, which is what makes the thinning a ratio rather than a knife edge. */
        private const val WINDOW_BLOCKS = 16

        val CODEC: Codec<Arrival> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.optionalFieldOf("weight", 8).forGetter(Arrival::weight),
                Codec.INT.optionalFieldOf("least", 1).forGetter(Arrival::least),
                Codec.INT.optionalFieldOf("most", 2).forGetter(Arrival::most),
                Codec.BOOL.optionalFieldOf("needs_open_sky", false).forGetter(Arrival::needsOpenSky),
                Codec.INT.optionalFieldOf("apart_by", 0).forGetter(Arrival::apartBy),
            ).apply(instance, ::Arrival)
        }
    }
}
