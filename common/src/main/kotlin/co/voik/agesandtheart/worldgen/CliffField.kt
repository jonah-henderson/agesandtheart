package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Escarpment
import co.voik.agesandtheart.worldgen.field.Weathered
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.TerrainField

/**
 * A world cut in two: open ocean one way, a plateau the other, and one cliff between them running from
 * horizon to horizon.
 *
 * **The height is chosen against a render distance, not against a picture.** From the plateau's edge the
 * water below sits [DROP_TO_THE_SEA] blocks down — inside the fog at vanilla's twelve chunks, but not by
 * much, so the sea shows at the foot of the cliff and fades out before the far side of it does. Taller and
 * the ocean is a rumour; shorter and the cliff stops being the point of the world.
 *
 * The sea needs no arranging. [SeaFill] floods whatever the shape leaves empty below its level, and the
 * plateau stands well over that, so pouring one sea gives an ocean on one side and a dry tableland on the
 * other with nothing said about where the coast is.
 */
object CliffField {

    /**
     * The world, weathered — **not an option a writer takes**, for the same reason a canyon's is not. A
     * step function of distance is a ruled face, and a ruled face a hundred and seventy blocks tall does
     * not read as a plainer cliff; it reads as a wall someone poured.
     *
     * There is open sky over the plateau here, so no roof rule: what keeps the tableland walkable is the
     * profile in [Weathering.CLIFFS], which protects the top of the band and works the middle.
     */
    /**
     * The angle a line runs at when nobody said — north to south, which is where a zero bearing points.
     *
     * A number rather than a word: translating an axis into an angle is `age.aspect`'s, and reaching for
     * it here is what put the Art's vocabulary underneath the field toolkit.
     */
    private const val NORTH_TO_SOUTH = 0.0

    fun world(bearing: Double = NORTH_TO_SOUTH, salt: Long = 0L): TerrainField =
        Weathered.sculpting(bareWorld(bearing, salt), Weathering.CLIFFS, SHELTER_REACH)

    /** The face before the weather reaches it — the previewer's other half, and nothing else's. */
    fun bareWorld(bearing: Double = NORTH_TO_SOUTH, salt: Long = 0L): TerrainField = Escarpment(
        bearing = bearing,
        offset = 0.0,
        lowY = SEABED_Y,
        highY = PLATEAU_Y,
        floorY = WORLD_FLOOR,
        seed = COAST_SEED xor salt,
    )

    private const val WORLD_FLOOR = -64

    /** The convention every shape wanting a sea keeps to. */
    const val SEA_LEVEL = 63

    /** Deep enough to be an ocean rather than a flooded plain, with room for the relief to roll in it. */
    const val SEABED_Y = 32

    /**
     * How far the plateau stands over the sea.
     *
     * Sized against vanilla's default twelve-chunk render distance, which fades out somewhere under two
     * hundred blocks: the water directly below the edge is comfortably inside that, and water a hundred
     * blocks out is right at it. So you can see the ocean from the top, and only just.
     */
    const val DROP_TO_THE_SEA = 142

    /** The tableland's own level, [DROP_TO_THE_SEA] blocks over the water. */
    const val PLATEAU_Y = SEA_LEVEL + DROP_TO_THE_SEA

    /**
     * How far into the ground the weather works — **much shallower than a canyon's**, because here it is
     * working two flat tables and a few ledges rather than a benched wall. A deep reach on a tabletop does
     * not roughen it, it craters it: the reach is the most any one pit can be.
     */
    private const val SHELTER_REACH = 10

    private const val COAST_SEED = 0xC0A_57L
}
