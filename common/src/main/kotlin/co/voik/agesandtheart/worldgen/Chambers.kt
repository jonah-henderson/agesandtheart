package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Ellipsoid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.Scatter
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import co.voik.agesandtheart.worldgen.field.Warped

/**
 * An underground of **great chambers, each with a lake in the bottom of it** — the vaults a city could be
 * built in, and the counterpart to [GreatHalls]: one is a thing somebody made, this is a thing that
 * happened.
 *
 * Nothing here is a new kind of node. A chamber starts as an [Ellipsoid], is bitten into by a very
 * coarse [Noise3D], has its plan wandered by [Warped], and stands on a [NoiseHeightmap] subtracted from
 * it; the lake is the part of what is left below the storey's [Storey.lakeLevel]. The floor is sampled in
 * **world** space rather than per chamber, so every chamber gets a different piece of it and the islands
 * that rise through the water are never twice the same.
 *
 * **The lens decides where and how big; the noise decides the boundary.** A bare ellipsoid reads as
 * geometry from anywhere inside it, and the fix is not a smaller ellipsoid but a broken one: the noise is
 * scaled so that one of its lobes is comparable to the whole chamber, which takes bays and alcoves out of
 * the wall rather than pocking it. Replacing the lens with the noise outright was the other way to do it
 * and gives up too much — the size a writer asked for, and a chamber where a player arrives.
 *
 * **The chambers sit in a band centred on the water rather than spread through the rock**, which is what
 * makes the lakes dependable: a chamber lifted clear of the level would be a dry vault and one sunk under
 * it a drowned one, so the lens' equator is *on* the bed and every chamber comes out with water in its
 * bottom and air over it.
 *
 * **The origin holds a chamber whatever the scatter does**, which is arrival rather than generosity, and
 * it is what buys the freedom for everything else to be genuinely random — see [originChamber].
 */
object Chambers {

    /**
     * The volume to take out of an Age's rock between [floorY] and [roofY], at the [size] asked for.
     *
     * Empty where the band cannot hold a chamber worth the name — the same answer [GreatHalls] gives a
     * landform with no room, and the reason a shallow underground is simply not chambered rather than
     * being chambered badly.
     */
    fun voidBetween(floorY: Int, roofY: Int, size: Double? = null, salt: Long = 0L): TerrainField {
        val plan = planFor(floorY, roofY, size) ?: return Union(emptyList())
        return Union(plan.storeys.map { hollow(plan, it, salt) })
    }

    /**
     * The water standing in those chambers: everything hollow below each storey's own lake level.
     *
     * **Bounded to the chambers rather than poured at a level**, because the level is the chambers' own and
     * not the Age's. A flat fill at this height would find every cave in the world as well, and an Age's
     * own waterline is somewhere else entirely — see `Terrain.Ground.wet`.
     */
    fun lakesIn(floorY: Int, roofY: Int, size: Double? = null, salt: Long = 0L): TerrainField {
        val plan = planFor(floorY, roofY, size) ?: return Union(emptyList())
        return Union(
            plan.storeys.map { storey ->
                Intersect(listOf(Slab(lowY = storey.floorY, highY = storey.lakeLevel), hollow(plan, storey, salt)))
            },
        )
    }

    /**
     * One storey's vaults, floored and **bounded to the band they were asked for**.
     *
     * The bound is not tidiness: a lens is far taller than the dome that survives its floor, and the floor
     * is a heightmap that reaches back only to its own flat bound — so under that bound the lens is
     * subtracted from nothing and hangs on as a slab of void beneath the bed it is supposed to stand on.
     * With storeys it does a second job, keeping each one out of the one below.
     */
    private fun hollow(plan: Plan, storey: Storey, salt: Long): TerrainField = Intersect(
        listOf(
            Slab(lowY = storey.floorY, highY = storey.roofY),
            Subtract(chambers(plan, storey, salt), floor(plan, storey, salt)),
        ),
    )

    /** The chambers themselves, before their floor is put back under them. */
    private fun chambers(plan: Plan, storey: Storey, salt: Long): TerrainField {
        val laid = Union(listOfNotNull(scattered(plan, storey, salt), originChamber(plan, storey)))
        // Cheapest first is [Intersect]'s own doing, and it matters here: the lenses are closed form, so
        // the noise is only walked down columns something actually reaches.
        val bitten = Intersect(listOf(laid, roughness(plan, storey, salt)))
        return Warped(
            base = Union(listOfNotNull(bitten, originCore(plan, storey))),
            seed = WARP_SEED xor salt xor storey.salt,
            firstOctave = WARP_OCTAVE,
            amplitudes = WARP_AMPLITUDES,
            // Long against a chamber, so a whole flank swings out rather than the wall fraying.
            scale = plan.radius * WARP_SCALE_IN_RADII,
            amount = plan.radius * WARP_SHARE_OF_A_RADIUS,
        )
    }

    /**
     * Where the chambers fall — **[Scatter] rather than a lattice**, which is the whole of why they stop
     * reading as a grid.
     *
     * However hard a [co.voik.agesandtheart.worldgen.field.Grid] is jittered it still places about one per
     * cell, so the positions look random close up while the *count* keeps a rhythm at any distance. Here
     * the count varies, so there are stretches of unbroken rock and knots of two together. It costs the
     * origin guarantee, which is why [originChamber] exists — and having paid for that separately, there
     * is nothing left for the lattice to buy.
     */
    private fun scattered(plan: Plan, storey: Storey, salt: Long): TerrainField = Instanced(
        templates = listOf(lens(plan, storey)),
        placement = Scatter(
            cellSize = plan.spacing,
            leastPerCell = FEWEST_IN_A_CELL,
            mostPerCell = MOST_IN_A_CELL,
            density = Density.uniform(),
        ),
        // **Scale alone, and the pivot is the bed.** A lifted copy would carry its own floor up clear of
        // the noise one and come out a dry lens with rock under it; pivoting on the bed instead keeps
        // every chamber's lower half buried however big it is drawn, so the ground you stand on is always
        // the same noise surface and the lake in it is always where it says.
        variation = Variation(
            yawSteps = 1,
            minScale = SMALLEST_AGAINST_ITS_KIND,
            maxScale = 1.0,
            scaleSteps = DISTINCT_SIZES,
            pivotY = storey.bedY,
        ),
        seed = CHAMBER_SEED xor salt xor storey.salt,
    )

    /**
     * A chamber on the world origin, on the lowest storey and nowhere else.
     *
     * **A player arrives at (0, 0) and cannot be given a spawn point afterwards** — a runtime level gets
     * `DerivedLevelData`, whose `setSpawn` does nothing — so an Age that wants somewhere to stand has to
     * put it there during generation. It is a full-sized lens rather than a courtesy hollow, because the
     * canonical D'ni environment is this chamber: the city goes on the island in its lake.
     *
     * It is bitten and wandered with all the others, so nothing about it reads as placed.
     */
    private fun originChamber(plan: Plan, storey: Storey): TerrainField? =
        if (storey.holdsTheOrigin) lens(plan, storey) else null

    /**
     * And the one piece of it nothing is allowed to take away.
     *
     * The noise that breaks up every other wall could as easily break up this one, and a bite out of the
     * middle of the origin chamber is a player standing in rock. This is small — a third of the radius —
     * and sits inside both the warp's reach and the lens around it, so what it guarantees is that the
     * column at the origin is open, not that any particular shape is.
     */
    private fun originCore(plan: Plan, storey: Storey): TerrainField? =
        if (!storey.holdsTheOrigin) null
        else Ellipsoid(
            centerX = 0,
            centerY = storey.bedY,
            centerZ = 0,
            radiusXZ = plan.radius * CORE_SHARE_OF_A_RADIUS,
            radiusY = plan.height * CORE_SHARE_OF_A_RADIUS,
        )

    private fun lens(plan: Plan, storey: Storey): TerrainField = Ellipsoid(
        centerX = 0,
        centerY = storey.bedY,
        centerZ = 0,
        radiusXZ = plan.radius,
        radiusY = plan.height,
    )

    /**
     * What turns a lens into a cavern: **noise coarse enough that one lobe of it is most of a chamber**.
     *
     * Read in world space rather than per copy, so neighbouring chambers are cut from different parts of
     * one field and two that meet agree where they touch. At a finer scale this would be a sponge; at this
     * one it takes a bay out of one wall and leaves a headland on the other, which is what a cave looks
     * like. [THRESHOLD] is how much it takes — nearer zero eats more.
     */
    private fun roughness(plan: Plan, storey: Storey, salt: Long): TerrainField = Noise3D(
        seed = ROUGHNESS_SEED xor salt xor storey.salt,
        firstOctave = ROUGHNESS_OCTAVE,
        amplitudes = ROUGHNESS_AMPLITUDES,
        scaleX = plan.radius * ROUGHNESS_IN_RADII,
        scaleY = plan.height * ROUGHNESS_IN_HEIGHTS,
        scaleZ = plan.radius * ROUGHNESS_IN_RADII,
        character = NoiseCharacter.PLAIN,
        threshold = ROUGHNESS_THRESHOLD,
        lowY = storey.floorY,
        highY = storey.roofY,
    )

    /**
     * The ground inside them — **the bottom of every chamber**, one noise surface across the whole storey,
     * so each vault's bed is its own and the islands standing out of the water are never twice the same.
     *
     * It cuts the lower half of each lens away, which is why a chamber's *height* is the dome over this
     * rather than the lens' own diameter. [Storey.lakeLevel] then sits a little way up the bed's swing, so
     * where it rides high the rock breaks the water and where it does not the water is simply deeper.
     */
    private fun floor(plan: Plan, storey: Storey, salt: Long): TerrainField = NoiseHeightmap(
        seed = FLOOR_SEED xor salt xor storey.salt,
        // Islands a couple of hundred blocks across, with shape on their flanks — the scale a city sits on.
        firstOctave = -8,
        amplitudes = listOf(1.0, 0.5, 0.25),
        scaleX = 1.0,
        scaleZ = 1.0,
        baseY = storey.bedY,
        relief = plan.bedRelief,
        flatY = storey.floorY,
    )

    /**
     * One level of chambers: where its rock starts, where its water stands, and its own seed.
     *
     * The salt is what stops the storeys being the same world twice — without it a scatter would place the
     * same chambers at every level and they would read as a single shape stamped through the rock.
     */
    private class Storey(
        val floorY: Int,
        val roofY: Int,
        val bedY: Int,
        val lakeLevel: Int,
        val salt: Long,
        val holdsTheOrigin: Boolean,
    )

    /**
     * Every number the shape is built from, worked out once from the band it has to fit in.
     *
     * The band rather than the size alone, because a chamber has to *fit*: an underground eighty blocks
     * deep cannot hold a colossal vault, and the honest answer there is a smaller one rather than a vault
     * with its roof cut off square by the rock above it. What the band buys beyond that is **storeys** —
     * whatever is left over after one chamber goes in is enough for another, and small chambers stack
     * several deep in a band a colossal one fills on its own.
     */
    private class Plan(val floorY: Int, val roofY: Int, size: Double?) {
        val radius = betweenTheEnds(SMALLEST_RADIUS, LARGEST_RADIUS, size)
        val spacing = betweenTheEnds(CLOSEST_SPACING, WIDEST_SPACING, size)

        private val band = roofY - floorY
        private val wanted = betweenTheEnds(SHALLOWEST_CHAMBER, DEEPEST_CHAMBER, size)

        /** How far the dome stands over its bed — the headroom, and what a chamber's height means here. */
        val height = wanted
            .coerceAtMost(band - 2.0 * ROCK_AROUND_A_CHAMBER - BED_SWING * wanted)
            .coerceAtLeast(0.0)

        /** How far the bed swings either way about its own mean. */
        val bedRelief = height * BED_SWING

        /** Rock, the bed's swing, the headroom over it, and rock again — one storey, floor to floor. */
        private val storeyHeight = 2.0 * ROCK_AROUND_A_CHAMBER + bedRelief + height

        val storeys: List<Storey> = layOutStoreys()

        private fun layOutStoreys(): List<Storey> {
            if (height < SHALLOWEST_CHAMBER || storeyHeight <= 0.0) return emptyList()
            val levels = (band / storeyHeight).toInt().coerceAtMost(MOST_STOREYS)
            // **The leftover is shared above and below rather than left overhead**, which for a colossal
            // vault is the difference between forty blocks of rock under the lake and a hundred and ten.
            // One storey of two hundred does not fit twice in three hundred and fifty, so a chamber stacked
            // up from the floor sat on the bedrock with half the world's height of dead rock over it.
            val spare = ((band - levels * storeyHeight) / 2.0).toInt()
            return (0..<levels).map { level ->
                val base = floorY + spare + (level * storeyHeight).toInt()
                val bedY = base + ROCK_AROUND_A_CHAMBER + bedRelief.toInt()
                Storey(
                    floorY = base,
                    roofY = base + storeyHeight.toInt(),
                    bedY = bedY,
                    // **A little way up the bed's swing rather than at its mean**, so rather more of the
                    // floor is under water than out of it and what does stand clear reads as an island in
                    // a lake instead of a lake in a plain.
                    lakeLevel = bedY + (bedRelief * LAKE_ABOVE_THE_BED).toInt(),
                    salt = level * STOREY_APART,
                    // The lowest, because the canonical vault is the deepest and its lake is the one the
                    // city stands in. Arrival walks down from the ceiling and finds whatever is first.
                    holdsTheOrigin = level == 0,
                )
            }
        }
    }

    private fun planFor(floorY: Int, roofY: Int, size: Double?): Plan? =
        Plan(floorY, roofY, size).takeIf { it.storeys.isNotEmpty() }

    /**
     * [size] read as a fraction of the way from the smallest chamber to the largest — the one place the
     * shared axis becomes this shape's own units, and null, the axis nobody spoke about, is [ORDINARY].
     */
    private fun betweenTheEnds(smallest: Double, largest: Double, size: Double?): Double {
        val fraction = size?.let(Span.NATURAL::fractionOf) ?: ORDINARY
        return smallest + fraction * (largest - smallest)
    }

    /**
     * How wide a chamber is. **The top is where a city fits**: a jigsaw structure reaches 128 blocks from
     * its start, so the island in the middle wants a couple of hundred across and the water round it more
     * again — which is what `colossal` buys and what nothing below it does.
     */
    private const val SMALLEST_RADIUS = 90.0
    private const val LARGEST_RADIUS = 380.0

    /** And how deep, floor to crown. Read with [LAKE_ABOVE_THE_BED]: most of it is air. */
    private const val SHALLOWEST_CHAMBER = 44.0
    private const val DEEPEST_CHAMBER = 150.0

    /** How far apart they lie. Wider as they grow, so a bigger chamber is not a more crowded world. */
    private const val CLOSEST_SPACING = 520.0
    private const val WIDEST_SPACING = 1400.0

    /** Where a chamber sits when nothing in the book spoke about its size. */
    private const val ORDINARY = 0.4

    /** Rock over a chamber's crown and under the lowest dip of its bed, so neither opens into the band. */
    private const val ROCK_AROUND_A_CHAMBER = 10

    /** How far the bed swings either way, against the headroom over it. */
    private const val BED_SWING = 0.22

    /**
     * Where the water stands in that swing. **Above the mean**, so most of the bed is lake and the rest is
     * island — below it and a chamber reads as a plain with puddles.
     */
    private const val LAKE_ABOVE_THE_BED = 0.4

    /**
     * How many levels of chambers may stand over one another, however much room there is.
     *
     * A cap rather than a shape: the band is what decides in practice, and a colossal vault fills the whole
     * of a solid Age's on its own. This is here so a shallow chamber in a tall Age does not come out as a
     * dozen floors of honeycomb.
     */
    private const val MOST_STOREYS = 4

    /** How many chambers one cell of the scatter draws. A least of nothing is what buys empty rock. */
    private const val FEWEST_IN_A_CELL = 0
    private const val MOST_IN_A_CELL = 2

    /** The smallest a chamber is drawn against its kind, and how many sizes are drawn between. */
    private const val SMALLEST_AGAINST_ITS_KIND = 0.7
    private const val DISTINCT_SIZES = 3

    /** The share of a chamber the origin one keeps whatever the noise does — see [originCore]. */
    private const val CORE_SHARE_OF_A_RADIUS = 0.35

    /** How coarse the biting noise is, against the chamber it is biting. Around one is one lobe per wall. */
    private const val ROUGHNESS_IN_RADII = 0.55
    private const val ROUGHNESS_IN_HEIGHTS = 0.7
    private const val ROUGHNESS_OCTAVE = 0
    private val ROUGHNESS_AMPLITUDES = listOf(1.0, 0.5)

    /**
     * How much of a chamber the noise takes. **Well below the middle of the range**, because this erodes a
     * wall rather than deciding where rock is: nearer zero and the chambers come apart into caves.
     */
    private const val ROUGHNESS_THRESHOLD = -0.15

    /** How far the plan wanders, and over what distance. Long and gentle: lobes, not fraying. */
    private const val WARP_SHARE_OF_A_RADIUS = 0.25
    private const val WARP_SCALE_IN_RADII = 1.5
    private const val WARP_OCTAVE = 0
    private val WARP_AMPLITUDES = listOf(1.0, 0.5)

    /** What one storey's seeds are moved by, so no two levels are the same world. */
    private const val STOREY_APART = 0x51_0E_1EL

    private const val CHAMBER_SEED = 0xC4A_9BE45L
    private const val FLOOR_SEED = 0x5E_ABED_5L
    private const val ROUGHNESS_SEED = 0x120_6B17E5L
    private const val WARP_SEED = 0xA9_4A_9DE5L
}
