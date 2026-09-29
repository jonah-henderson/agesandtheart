package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import co.voik.agesandtheart.worldgen.ShatteredField
import co.voik.agesandtheart.worldgen.SizeScale
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.math.ceil

/**
 * Properties of a plate broken into fault blocks.
 *
 * The mosaic is noise, so its density is uniform by construction rather than by care — what these ask is
 * mostly that the cut keeps to its bounds and that the plate is cracked rather than eaten. The look is
 * `./gradlew :common:preview --args=shattered`, where the plan view is the whole picture: a cracked plate
 * has no cross-section worth drawing, every transect being one canyon or none.
 */
@Tags(NEEDS_LANDFORMS)
class ShatteredCheck : FunSpec({

    val world = ShatteredField.world(scale = SizeScale.COLOSSAL)
    val cells = ShatteredField.cells(scale = SizeScale.COLOSSAL)

    fun columnsAround(originX: Int, originZ: Int): List<Pair<Int, Int>> =
        (originZ - REACH..originZ + REACH step STRIDE).flatMap { worldZ ->
            (originX - REACH..originX + REACH step STRIDE).map { worldX -> worldX to worldZ }
        }

    /** Whether a gorge runs through this column — asked of the cut, since the blocks stand at many heights. */
    fun isCracked(column: Pair<Int, Int>) = cells.columnSpans(column.first, column.second).ranges.isNotEmpty()

    fun crackedShare(originX: Int, originZ: Int): Double {
        val columns = columnsAround(originX, originZ)
        return columns.count(::isCracked).toDouble() / columns.size
    }

    /** **Nothing is cut below the bed**, the bed's own relief being the one thing allowed under the floor. */
    test("the cut stays between the bed and the rim") {
        val deepest = cells.floorY - ceil(cells.bedRelief).toInt()
        for (worldZ in -900..900 step 13) {
            for (worldX in -900..900 step 7) {
                val cut = cells.columnSpans(worldX, worldZ).ranges.firstOrNull() ?: continue
                check(cut.first >= deepest) { "at ($worldX, $worldZ) the cut reached down to ${cut.first}" }
                check(cut.first <= cells.rimY) { "at ($worldX, $worldZ) the cut began over the rim at ${cut.first}" }
            }
        }
    }

    /** The plate is cracked, not eaten: most of the ground has to survive as cell. */
    test("cells are left standing between the joins") {
        val standing = 1.0 - crackedShare(0, 0)
        check(standing > LEAST_STANDING) { "only ${"%.0f%%".format(standing * 100)} of the ground was cell" }
        check(standing < MOST_STANDING) { "${"%.0f%%".format(standing * 100)} was cell, so it is barely cracked" }
    }

    /**
     * **The blocks stand at different heights**, which is the whole of what tells this from canyonlands on
     * the ground: a slot between two tables at one height reads the same whichever plan it was cut to.
     */
    test("the blocks stand at several heights") {
        val tops = columnsAround(0, 0).filterNot(::isCracked)
            .mapNotNull { (worldX, worldZ) -> world.columnSpans(worldX, worldZ).highestSolidY }
        val levels = tops.groupingBy { it / LEVEL_BAND }.eachCount().filterValues { it >= tops.size / LEVEL_SHARE }
        check(levels.size >= FEWEST_LEVELS) { "the blocks stood at only ${levels.size} heights: ${levels.keys.map { it * LEVEL_BAND }}" }
    }

    /** And a mosaic is a mosaic everywhere, not only where the render happened to look. */
    test("the plate is as cracked far from the origin as at it") {
        val here = crackedShare(0, 0)
        val farAway = crackedShare(FAR, -FAR)
        check(here in LEAST_CRACKED..MOST_CRACKED) { "at the origin the joins took ${"%.0f%%".format(here * 100)}" }
        check(farAway in LEAST_CRACKED..MOST_CRACKED) { "far out they took ${"%.0f%%".format(farAway * 100)}" }
    }

    /** A map with one member has no join, so there is nothing to open a canyon along. */
    test("a mosaic of one cell cracks nothing") {
        val ground = ShatteredField.ground(SizeScale.COLOSSAL)
        val alone = cells.copy(map = cells.map.copy(members = 1))
        check(CellCanyon.cut(ground, alone) === ground) { "a one-cell mosaic still wrapped the ground" }
    }

    /**
     * The cross-section is **the same object a [Canyon] uses**, which is what the pair of nodes is for: a
     * cell canyon differs from a canyon in its plan and in nothing else.
     */
    test("the cells carry a canyon's own profile") {
        check(cells.profile == CanyonProfile.DEFAULT) { "the profile drifted to ${cells.profile}" }
        val small = cells.resized(HALF, pivotY = cells.rimY)
        check(small.profile == cells.profile) { "resizing changed the profile to ${small.profile}" }
        check(small.halfWidth == cells.halfWidth * HALF) { "the width came out ${small.halfWidth}" }
        check(small.map.scale == cells.map.scale * HALF) { "the cells came out ${small.map.scale} across" }
    }

    test("the world round-trips through its codec") {
        val written = ShatteredField.world(scale = SizeScale.COLOSSAL)
        val encoded = TerrainField.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, written)
            .getOrThrow { failure -> error("the plate would not encode: $failure") }
        val read = TerrainField.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded)
            .getOrThrow { failure -> error("the plate would not read back: $failure") }
        check(read == written) { "read back as a different world" }
    }
}) {
    private companion object {
        /** Wide enough to hold several cells, so a sample is of the mosaic rather than of one of them. */
        const val REACH = 1200
        const val STRIDE = 13

        /** Far enough out that anything anchored to the origin would have thinned to nothing. */
        const val FAR = 20_000

        /** Heights within this many blocks count as one level, which is less than the step between two. */
        const val LEVEL_BAND = 16

        /** A level must hold at least one column in this many to count, so weathering's scraps do not. */
        const val LEVEL_SHARE = 20

        const val FEWEST_LEVELS = 4
        const val LEAST_STANDING = 0.3
        const val MOST_STANDING = 0.9
        const val LEAST_CRACKED = 0.1
        const val MOST_CRACKED = 0.7
        const val HALF = 0.5
    }
}
