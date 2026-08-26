package co.voik.agesandtheart.age.aspect

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

/**
 * **How a creature the world never offered arrives in one** — `art/spawning.json`.
 *
 * A biome's spawn list is what may be *tried*, not what will appear: every attempt goes through
 * `SpawnPlacements.isSpawnPositionOk` and `checkSpawnRules`, so a cod named into a desert is refused by
 * vanilla and never seen. That is what makes adding safe, and it is why this file is small — for almost
 * every creature the only question is how often to try.
 *
 * **The exceptions are the creatures vanilla never spawns at all**, which is why this file exists: they
 * have no weight anywhere and no notion of how many arrive together, so somebody has to say. *Where* they
 * belong is not here — that is [SpawnGround], a pair of tags, because it is a judgement about a creature
 * rather than a number for one and a pack should be able to make it over any mob in the game.
 */
data class Spawning(
    private val byType: Map<String, Arrival> = emptyMap(),
    /** What a creature nobody wrote a line for arrives as. */
    val ordinary: Arrival = Arrival(),
    /** Creatures that belong out under the sky — see [Ground]. */
    private val onTheSurface: Set<String> = emptySet(),
    /** And ones that belong down in the rock. */
    private val underTheGround: Set<String> = emptySet(),
) {
    fun of(id: Identifier): Arrival = byType[id.toString()] ?: ordinary

    /**
     * **Where [id] belongs**, which is a judgement about a creature rather than a number for one.
     *
     * Two lists rather than three, and neither is required: a creature in **only** one belongs to that
     * ground alone, and one in both or in neither belongs anywhere. So the file carries the judgements and
     * nothing else — the hundred ordinary mobs need no line, and a creature a pack adds gets vanilla's own
     * behaviour until somebody says otherwise. Being in both is not the same statement as being in
     * neither, though they resolve alike: one is a reader saying "yes, really both" and the other is
     * nobody having looked.
     *
     * **Here rather than in entity-type tags**, which was the first attempt. A tag is bound by a datapack
     * reload and nothing binds one offline, so every check of the gate passed vacuously; binding our pack's
     * alone *unbinds vanilla's*, which took the whole corpus down with it. Read this way it is ordinary
     * corpus content — merged, overridable and checkable — and a gate that stops applying is a gate a
     * check can see stop applying (Jonah, 2026-08-26).
     */
    fun groundOf(id: Identifier): Ground {
        val surface = id.toString() in onTheSurface
        val underground = id.toString() in underTheGround
        if (surface == underground) return Ground.ANYWHERE
        return if (surface) Ground.SURFACE else Ground.UNDERGROUND
    }

    /**
     * Every creature named here, which is what makes one **writable at all** where vanilla would never
     * have grown it: `DerivedWords.spawns` gives no word to a `MobCategory.MISC` entity — an arrow and a
     * boat are not creatures — and the golems are misc because they are built rather than born.
     */
    val writable: Set<Identifier> get() = byType.keys.mapNotNull(Identifier::tryParse).toSet()

    /** This file with [later] laid over it, entry by entry, so a pack may retune one creature. */
    fun mergedWith(later: Spawning): Spawning = Spawning(
        byType + later.byType,
        later.ordinary,
        onTheSurface + later.onTheSurface,
        underTheGround + later.underTheGround,
    )

    companion object {
        /** Where a pack puts it. One file: it is a short list of exceptions, not a table. */
        const val FILE = "art/spawning.json"

        val CODEC: Codec<Spawning> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Codec.STRING, Arrival.CODEC).optionalFieldOf("creatures", emptyMap())
                    .forGetter { it.byType },
                Arrival.CODEC.optionalFieldOf("ordinary", Arrival()).forGetter(Spawning::ordinary),
                Codec.STRING.listOf().optionalFieldOf("on_the_surface", emptyList())
                    .forGetter { it.onTheSurface.toList() },
                Codec.STRING.listOf().optionalFieldOf("under_the_ground", emptyList())
                    .forGetter { it.underTheGround.toList() },
            ).apply(instance) { creatures, ordinary, surface, underground ->
                Spawning(creatures, ordinary, surface.toSet(), underground.toSet())
            }
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
    /**
     * **How far apart this creature stands when a rung asked for [density] of it.**
     *
     * A rung on a creature held apart moves the *spacing*, and cannot move the weight: once something is
     * most of what arrives inside its own window, asking for more of it has nowhere left to go, and
     * `teeming ender_dragon` and a plain one came out identical. More of them means more windows.
     *
     * As the square root, so the population is what scales: a creature twice as thick stands
     * `1/√2` as far apart and covers half the ground each. Never closer than one window, which is where
     * the cells meet and the grid stops being one.
     */
    fun spacedAt(density: Double): Int {
        if (apartBy <= 0) return NOWHERE_IN_PARTICULAR
        val closer = apartBy / Math.sqrt(density.coerceAtLeast(THINNEST))
        return closer.roundToInt().coerceAtLeast(WINDOW_BLOCKS)
    }

    /**
     * **How much of the map a spacing takes away**, which [Spawns] hands back as weight — one over the
     * window's share, so a creature held 320 blocks apart is four hundred times as likely inside its own
     * window as it would otherwise be, and no likelier over the world.
     *
     * The KDoc above always said the weight could then be a real share. It could not, until this existed.
     */
    fun thinningAt(spacing: Int): Double {
        if (spacing <= NOWHERE_IN_PARTICULAR) return 1.0
        val share = spacing.toDouble() / WINDOW_BLOCKS
        return share * share
    }

    fun mayBeTriedAt(x: Int, z: Int, spacing: Int): Boolean {
        if (spacing <= NOWHERE_IN_PARTICULAR) return true
        return Math.floorMod(x, spacing) < WINDOW_BLOCKS && Math.floorMod(z, spacing) < WINDOW_BLOCKS
    }
    companion object {
        /** One chunk of each cell, which is what makes the thinning a ratio rather than a knife edge. */
        private const val WINDOW_BLOCKS = 16

        /** A creature held apart from nothing, which is almost all of them. */
        private const val NOWHERE_IN_PARTICULAR = 0

        /** However thin a rung asks for, a spacing is a real distance and cannot be divided by nothing. */
        private const val THINNEST = 0.01

        val CODEC: Codec<Arrival> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.optionalFieldOf("weight", 8).forGetter(Arrival::weight),
                Codec.INT.optionalFieldOf("least", 1).forGetter(Arrival::least),
                Codec.INT.optionalFieldOf("most", 2).forGetter(Arrival::most),
                Codec.INT.optionalFieldOf("apart_by", 0).forGetter(Arrival::apartBy),
            ).apply(instance, ::Arrival)
        }
    }
}

/**
 * **Where a creature belongs** — out under the sky, down in the rock, or anywhere it can stand.
 *
 * The thing vanilla has no way to say. `SpawnPlacements` decides whether a *position* will hold a creature
 * — solid ground under it, room above, dark enough — and says nothing about whether it ought to be found
 * there. For everything vanilla spawns that hardly matters, its own biome lists having already put it where
 * it belongs. For a creature written into a world that never had one it is the whole question: an entity
 * type with no registered placement gets `NO_RESTRICTIONS` and a predicate that answers yes, so a dragon
 * arrives inside a mountain as readily as on a field.
 */
enum class Ground {
    /** Out under the sky. A golem is built in a village square, not found in a cave. */
    SURFACE,

    /** Down in the rock, and not out in the open — the warden's own answer. */
    UNDERGROUND,

    /** Both, which is most of them, and what a creature nobody made a judgement about is taken as. */
    ANYWHERE,
    ;

    /** Whether an attempt may be made here, given whether the sky is open over it. */
    fun admits(skyIsOpen: Boolean): Boolean = when (this) {
        SURFACE -> skyIsOpen
        UNDERGROUND -> !skyIsOpen
        ANYWHERE -> true
    }
}
