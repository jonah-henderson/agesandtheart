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
 * belong is [groundOf], and what light they come in is [lightOf].
 */
data class Spawning(
    private val byType: Map<String, Arrival> = emptyMap(),
    /** What a creature nobody wrote a line for arrives as. */
    val ordinary: Arrival = Arrival(),
    /** Creatures that belong out under the sky — see [Ground]. */
    private val onTheSurface: Set<String> = emptySet(),
    /** And ones that belong down in the rock. */
    private val underTheGround: Set<String> = emptySet(),
    /** And ones that belong well above it. */
    private val inTheAir: Set<String> = emptySet(),
    /** Creatures that come where it is lit — see [lightOf]. */
    private val inTheLight: Set<String> = emptySet(),
    /** And ones that come where it is not. */
    private val inTheDark: Set<String> = emptySet(),
    /**
     * **Creatures vanilla's own spawner will not place, whatever it is asked**, which an Age therefore
     * places itself — see [co.voik.agesandtheart.age.aspect.AgeSpawner].
     *
     * Two reasons and both are vanilla's. `NaturalSpawner.isValidSpawnPostitionForType` refuses a
     * `MobCategory.MISC` entity outright, so the golems can never arrive through it however they are
     * weighted or whatever pass they are offered in. And it validates every creature against a box it must
     * clear *where it stands*, which for a dragon is sixteen blocks by eight, at a height vanilla never
     * draws above the surface.
     *
     * A creature named here is taken **out of** the list `getMobsAt` builds — asking for one there is
     * asking for a draw that can only be lost — and is placed by our own spawner at whatever [Ground] it
     * belongs to.
     */
    private val placedByTheAge: Set<String> = emptySet(),
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
        if (id.toString() in inTheAir) return Ground.IN_THE_AIR
        val surface = id.toString() in onTheSurface
        val underground = id.toString() in underTheGround
        if (surface == underground) return Ground.ANYWHERE
        return if (surface) Ground.SURFACE else Ground.UNDERGROUND
    }

    /**
     * **What light [id] comes in**, which is the axis vanilla *does* have — and the reason this exists
     * anyway is that not every creature is subject to it.
     *
     * A monster's own `checkSpawnRules` tests the light and a golem's does not, so a built creature
     * arrives in a floodlit courtyard as readily as a dark one and nothing vanilla holds says otherwise.
     * Where a book's creature is placed by the Age rather than offered to the spawner, no light rule runs
     * at all. This is the judgement for both.
     */
    fun lightOf(id: Identifier): Lit {
        val light = id.toString() in inTheLight
        val dark = id.toString() in inTheDark
        if (light == dark) return Lit.ANY
        return if (light) Lit.IN_THE_LIGHT else Lit.IN_THE_DARK
    }

    /** Whether the Age has to put [id] there itself — see [placedByTheAge]. */
    fun isPlacedByTheAge(id: Identifier): Boolean = id.toString() in placedByTheAge

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
        inTheAir + later.inTheAir,
        inTheLight + later.inTheLight,
        inTheDark + later.inTheDark,
        placedByTheAge + later.placedByTheAge,
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
                Codec.STRING.listOf().optionalFieldOf("in_the_air", emptyList())
                    .forGetter { it.inTheAir.toList() },
                Codec.STRING.listOf().optionalFieldOf("in_the_light", emptyList())
                    .forGetter { it.inTheLight.toList() },
                Codec.STRING.listOf().optionalFieldOf("in_the_dark", emptyList())
                    .forGetter { it.inTheDark.toList() },
                Codec.STRING.listOf().optionalFieldOf("placed_by_the_age", emptyList())
                    .forGetter { it.placedByTheAge.toList() },
            ).apply(instance) { creatures, ordinary, surface, underground, air, lit, dark, placed ->
                Spawning(
                    creatures, ordinary,
                    surface.toSet(), underground.toSet(), air.toSet(),
                    lit.toSet(), dark.toSet(), placed.toSet(),
                )
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
     * **For a creature the Age places, and only for one.** A window is one chunk of each cell, so a
     * creature held 192 blocks apart is absent from a hundred and forty-three cells out of a hundred and
     * forty-four and is most of what arrives inside the one. Where [co.voik.agesandtheart.age.aspect.AgeSpawner]
     * places it that is exactly right, because it also counts what is already nearby and stops — the two
     * numbers describe one arrangement. In a *biome list* nothing counts, so the same window walks as "they
     * do not exist" and then "they are everywhere" (Jonah, 2026-08-26, walked, hunting wardens in caves).
     *
     * A naturally-spawned creature is thinned by its weight alone, which scatters it.
     */
    val apartBy: Int = 0,
) {
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
     * So a weight in `art/spawning.json` is the share of the world a creature holds, not the share of one
     * draw.
     */
    fun thinningAt(spacing: Int): Double {
        if (spacing <= NOWHERE_IN_PARTICULAR) return 1.0
        val share = spacing.toDouble() / WINDOW_BLOCKS
        return share * share
    }

    /**
     * Whether an attempt at ([x], [z]) is one of the few places this creature may be tried at all.
     *
     * The window is one chunk of each cell, so the thinning is `(16 / apartBy)²` and a dragon held 256
     * blocks apart is tried in a four-hundredth of the places a zombie is.
     */
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

    /** Aloft, well clear of the ground — the only ground vanilla's spawner cannot reach at all. */
    IN_THE_AIR,

    /** Both, which is most of them, and what a creature nobody made a judgement about is taken as. */
    ANYWHERE,
    ;

    /** Whether an attempt may be made here, given whether the sky is open over it. */
    fun admits(skyIsOpen: Boolean): Boolean = when (this) {
        SURFACE -> skyIsOpen
        UNDERGROUND -> !skyIsOpen
        // Vanilla draws its attempt height no higher than one above the surface, so there is no attempt
        // it could ever make that this would admit. `AgeSpawner` places these itself.
        IN_THE_AIR -> false
        ANYWHERE -> true
    }
}

/**
 * **What light a creature comes in** — lit, unlit, or whatever the world offers.
 *
 * The axis vanilla does have, and the reason this exists anyway is that not everything is subject to it: a
 * monster's `checkSpawnRules` tests the light and a golem's does not, and a creature the Age places rather
 * than offers has no rule run over it at all. [ANY] leaves whatever vanilla would have decided.
 *
 * **There is no day-and-night axis beside it, deliberately** (Jonah, 2026-08-27). One was built and taken
 * out the same week: "night" is a fact about where the suns are, and an Age may have four of them or none,
 * so the word means nothing the moment the sky stops being vanilla's. What a writer means by it is *dark*,
 * which is a fact about a place and is this. It also removed a contradiction nobody had noticed — a monster
 * judged to the day was asking for light and darkness at once, and simply never arrived.
 */
enum class Lit {
    IN_THE_LIGHT,
    IN_THE_DARK,

    /** Whatever the world offers, which is what a creature nobody made a judgement about is taken as. */
    ANY,
    ;

    /** Whether this admits a place lit to [brightness], on vanilla's own `0..15`. */
    fun admits(brightness: Int): Boolean = when (this) {
        IN_THE_LIGHT -> brightness > DARK_ENOUGH_TO_SPAWN
        IN_THE_DARK -> brightness <= DARK_ENOUGH_TO_SPAWN
        ANY -> true
    }

    private companion object {
        /** Vanilla's own long-standing line between somewhere lit and somewhere a monster will come. */
        const val DARK_ENOUGH_TO_SPAWN = 7
    }
}
