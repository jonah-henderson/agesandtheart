package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Canyon
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Weathered

/**
 * Mesa country: a tableland under open sky, cut to pieces by canyons running three ways at once.
 *
 * **Not the [CanyonField] world scaled down** — the same *node*, but a different world. That one is solid
 * to the height limit so the canyon is the only space in it; this one has a plateau you can stand on and
 * a sky over it, because the point is what is left standing rather than what was taken.
 *
 * Three **families** rather than a list of canyons. A list is a finite set of lines through one part of
 * the world, so the land between them grows without bound as you walk away — walk far enough and you are
 * on an endless plain. A family repeats every [SPACING] blocks, so the network is as dense at the edge of
 * the world as it is at the origin. See [Canyon.spacing].
 *
 * The bearings are a third of a turn apart, which leaves rough triangles rather than the rectangles two
 * families at right angles would. Rivers need no arranging: every family shares one floor, so one
 * waterline puts water in the bottom of all of them.
 */
object CanyonlandsField {

    fun world(salt: Long = 0L): TerrainField =
        Weathered.sculpting(bareWorld(salt), Weathering.CANYONLANDS, SHELTER_REACH)

    /** The network before the weather reaches it — the previewer's other half, and nothing else's. */
    fun bareWorld(salt: Long = 0L): TerrainField = Canyon.cut(ground(), families(salt))

    /** The tableland the canyons are cut out of. */
    fun ground(): TerrainField = Slab(lowY = VerticalWindow.MIN_Y, highY = PLATEAU_Y)

    /** Where one family of canyons sits: which way it runs, how far apart its members are, and where it starts. */
    private data class Family(val bearing: Double, val spacing: Double, val offset: Double)

    /**
     * Three families, deliberately **at neither exact thirds of a turn nor one spacing**.
     *
     * The first version used both, and tiled the plane into a flawless triangular lattice — it read as a
     * pattern someone drew rather than as country. Irregular angles and three different spacings, with a
     * meander wide enough to swing a canyon most of the way to its neighbour, is what makes the mesas
     * between them differ in size and shape instead of repeating.
     */
    private val FAMILIES = listOf(
        Family(bearing = 0.0, spacing = 760.0, offset = 0.0),
        Family(bearing = 1.14, spacing = 980.0, offset = 260.0),
        Family(bearing = 2.05, spacing = 660.0, offset = 470.0),
    )

    /** One repeating family of canyons per bearing. */
    fun families(salt: Long = 0L): List<Canyon> = FAMILIES.mapIndexed { index, family ->
        Canyon(
            bearing = family.bearing,
            offset = family.offset,
            halfWidth = HALF_WIDTH,
            floorY = FLOOR_Y,
            rimY = PLATEAU_Y + 1,
            seed = (FAMILY_SEED + index) xor salt,
            spacing = family.spacing,
            // Well inside half the narrowest spacing, or a canyon wanders into its own neighbour's half
            // and is clipped along the join.
            meanderReach = MEANDER_REACH,
            meanderStretch = MEANDER_STRETCH,
            bedRelief = BED_RELIEF,
        )
    }

    /** The tableland's surface, with sky over it — the whole difference from [CanyonField]. */
    const val PLATEAU_Y = 185

    /** The mean bed the canyons cut down to, shared by every family so one waterline serves them all. */
    const val FLOOR_Y = 40

    /** A few blocks over [FLOOR_Y], the same relation [CanyonField] uses. */
    const val RIVER_LEVEL = FLOOR_Y + 6

    /**
     * Half a canyon's width — 140 across against a 145-block drop, so **steeper than the grand canyon
     * deliberately**. Three families of anything as wide as that one is proportionally would leave no
     * mesa standing between them; a network's canyons have to be slots or there is nothing left to cut.
     */
    const val HALF_WIDTH = 70.0

    /**
     * How far a canyon wanders, and how long one bend runs.
     *
     * **Large against [HALF_WIDTH] on purpose** — nearly three times it. A family is regularly spaced by
     * construction, so the meander is the only thing that can make one gap between two of its canyons
     * differ from the next, and a timid one leaves the lattice showing through. Bounded above by half the
     * narrowest spacing less [HALF_WIDTH], which is 260.
     */
    private const val MEANDER_REACH = 200.0
    private const val MEANDER_STRETCH = 50.0

    /** Shallower than the grand canyon's, the river being smaller. */
    private const val BED_RELIEF = 5.0

    /**
     * How far into a mesa wall the weather works. Shallower than a canyon's — the walls are shorter.
     *
     * Not private, because `ShatteredField` wears `Weathering.CANYONLANDS` and so must wear its reach:
     * one profile, one reach, rather than the same number written down in two files where a retune would
     * find only one of them.
     */
    const val SHELTER_REACH = 24

    private const val FAMILY_SEED = 0xE5A_1A0DL
}
