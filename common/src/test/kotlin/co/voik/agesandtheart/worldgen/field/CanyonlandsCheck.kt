package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.CanyonlandsField
import io.kotest.core.spec.style.FunSpec

/**
 * Properties of a canyon *network*, which is a different thing from a canyon.
 *
 * The one that matters is coverage far from the origin. A handful of canyons unioned together are lines
 * through one part of the world, and the ground between them grows without bound — so a world that looks
 * like mesa country at spawn is an endless plain twenty thousand blocks out, and no render of the origin
 * would ever say so. Everything here is asked **near the origin and far from it**, and compared.
 */
class CanyonlandsCheck : FunSpec({

    val world = CanyonlandsField.world()

    fun cutShareAround(originX: Int, originZ: Int): Double {
        var columns = 0
        var cut = 0
        for (worldZ in originZ - REACH..originZ + REACH step STRIDE) {
            for (worldX in originX - REACH..originX + REACH step STRIDE) {
                val top = world.columnSpans(worldX, worldZ).highestSolidY ?: continue
                columns++
                if (top < CanyonlandsField.PLATEAU_Y - RIM_MARGIN) cut++
            }
        }
        return cut.toDouble() / columns
    }

    /**
     * **The property the whole family mechanism exists for.** A repeating family is as dense at the edge
     * of the world as at its middle; a list of canyons is not, and the difference only shows up where
     * nobody looks.
     */
    test("the network is as dense far from the origin as at it") {
        val here = cutShareAround(0, 0)
        val farAway = cutShareAround(FAR, -FAR)
        check(here in LEAST_CUT..MOST_CUT) { "at the origin the canyons took ${share(here)} of the ground" }
        check(farAway in LEAST_CUT..MOST_CUT) { "twenty thousand out they took ${share(farAway)}" }
    }

    /** And there must be mesa left between them, or the network has eaten the world it was cut into. */
    test("mesas are left standing between the canyons") {
        val standing = 1.0 - cutShareAround(0, 0)
        check(standing > LEAST_STANDING) { "only ${share(standing)} of the ground was left at plateau height" }
    }

    /**
     * Three families, and none of them the same canyon twice. A family reads its meander on a plane of its
     * own per repeat, so two neighbouring canyons of one family must not run in lockstep — without that
     * the land between them comes out corrugated rather than shaped.
     */
    test("neighbouring canyons of a family do not meander in lockstep") {
        val family = CanyonlandsField.families().first()
        fun axisNear(offset: Double, worldZ: Int): Int? =
            (-400..400 step 2).map { it + offset.toInt() }
                .filter { family.columnSpans(it, worldZ).ranges.isNotEmpty() }
                .minByOrNull { family.columnSpans(it, worldZ).ranges.first().first }

        var agreeing = 0
        var compared = 0
        for (worldZ in -600..600 step 37) {
            val here = axisNear(0.0, worldZ) ?: continue
            val next = axisNear(family.spacing, worldZ) ?: continue
            compared++
            // Both measured against their own nominal axis: in lockstep the two offsets would match.
            if (kotlin.math.abs((next - family.spacing) - here) < LOCKSTEP_BLOCKS) agreeing++
        }
        check(compared > 10) { "only $compared cross-sections had both canyons in them" }
        check(agreeing < compared / 2) { "$agreeing of $compared cross-sections had the two wandering alike" }
    }

    /** Every family shares one floor, which is what lets one waterline put a river in all of them. */
    test("all the families cut to the same floor") {
        val floors = CanyonlandsField.families().map { it.floorY }.distinct()
        check(floors == listOf(CanyonlandsField.FLOOR_Y)) { "the families cut to $floors" }
        check(CanyonlandsField.RIVER_LEVEL > CanyonlandsField.FLOOR_Y) { "the river is under the bed" }
    }

    test("the world round-trips through its codec") {
        val written = CanyonlandsField.world()
        val encoded = TerrainField.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, written)
            .getOrThrow { failure -> error("canyonlands would not encode: $failure") }
        val read = TerrainField.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded)
            .getOrThrow { failure -> error("canyonlands would not read back: $failure") }
        check(read == written) { "read back as a different world" }
    }
}) {
    private companion object {
        /** Wide enough to hold several of the widest spacing, so a sample is of the network not one mesa. */
        const val REACH = 1400
        const val STRIDE = 11

        /** Far enough out that a list of canyons through the origin would have thinned to nothing. */
        const val FAR = 20_000

        /** How far under the plateau a column has to be to count as cut rather than as surface roughness. */
        const val RIM_MARGIN = 24

        const val LEAST_CUT = 0.15
        const val MOST_CUT = 0.7
        const val LEAST_STANDING = 0.3

        /** How close two canyons' wanderings have to be before they count as agreeing. */
        const val LOCKSTEP_BLOCKS = 12

        fun share(of: Double) = "%.0f%%".format(of * 100)
    }
}
