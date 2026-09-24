package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.CanyonProfile
import co.voik.agesandtheart.worldgen.field.Drainage
import co.voik.agesandtheart.worldgen.field.FieldYield
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Weathered

/**
 * Rolling upland carved by a river system — the landform the other three could not be.
 *
 * A canyon has one axis and a mosaic's joins are peers; this has a **hierarchy**, so a stream you follow
 * downhill joins another and the pair cut a wider valley than either did alone. See [Drainage] for how a
 * network with that property is expressible one column at a time at all.
 *
 * **The waterline is what makes it read as rivers rather than as valleys.** It sits a little under the
 * mean land, so the trunks run wet and the headwaters run dry — which is exactly the distribution a
 * catchment has, and it falls out of the valleys deepening as they gather rather than being arranged.
 * Basins, where a node is lower than everything around it, become lakes by the same rule.
 */
object RiverlandsField {

    /** The ground, weathered — a light hand, unlike the canyon's. See [Weathering.RIVERLANDS]. */
    fun world(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField =
        Weathered.sculpting(network(salt, scale), Weathering.RIVERLANDS, SHELTER_REACH)

    /**
     * The water standing in the rivers, for `SeaFill.wet`.
     *
     * **The same network, asked the other question.** A waterline cannot fill a drainage system — it runs
     * downhill everywhere, so a plane wets the lowest trunks and leaves every headwater dry. Handing the
     * fill a field instead lets each reach carry its own surface.
     */
    fun water(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField =
        network(salt, scale).copy(describes = FieldYield.WATER)

    /**
     * The bare network, before the weather and without its water.
     *
     * [scale] is [SizeScale]'s factor, and it is the river system's reach: valleys further apart, wider and
     * more winding, at the same height over the same water — so the rivers run wet at every size and a
     * big system reads as a broad one rather than a deep one.
     */
    fun network(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): Drainage = Drainage(
        floorY = VerticalWindow.MIN_Y,
        landY = LAND_Y,
        relief = RELIEF,
        landStretch = LAND_STRETCH * scale,
        spacing = SPACING * scale,
        jitter = JITTER,
        seed = LAND_SEED xor salt,
        halfWidth = Drainage.DEFAULT_HALF_WIDTH * scale,
        meander = Drainage.DEFAULT_MEANDER * scale,
        // Fewer benches than a canyon's, and a wider bed: a river valley is a trough with a floodplain,
        // where a canyon is strata worn back at different rates.
        profile = CanyonProfile(benches = 2, riserShare = 0.45, floorShare = 0.22, gorgeShare = 0.45, gorgeRise = 0.4),
    )

    /** The mean height of the upland the rivers are cut into. */
    const val LAND_Y = 108

    /**
     * A little under [LAND_Y], so a valley has to have gathered a stream or two before it runs wet. Put it
     * at the mean and every headwater gully is a river; put it much lower and only the sea is.
     */
    const val WATERLINE = LAND_Y - 22

    /**
     * How far the land rolls. **The rivers' whole fall comes from this** — the network follows the land
     * downhill, so flat land drains nowhere in particular and everything reads as a puddle.
     */
    const val RELIEF = 46.0

    /** How far it is between one hill and the next. Several reaches, so a catchment holds a few streams. */
    private const val LAND_STRETCH = 26.0

    /** The length of one reach. Small against a hill, or every catchment is a single stream. */
    private const val SPACING = 120.0

    /** How far into the ground the weather works. Shallow: this is texture, not sculpture. */
    private const val SHELTER_REACH = 7

    /** Most of a cell, so the lattice never shows as a grid. */
    private const val JITTER = 0.7

    private const val LAND_SEED = 0x21_5EA5L
}
