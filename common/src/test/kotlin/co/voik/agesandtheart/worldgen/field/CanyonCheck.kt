package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import co.voik.agesandtheart.worldgen.CanyonField
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.math.PI
import kotlin.math.ceil

/**
 * Properties of the one node whose output is what it *removes*.
 *
 * A cut is harder to eyeball than a shape: the preview draws what survives, so a canyon that reaches a
 * hundred blocks past its own rim, or that takes a bite out of the bedrock under its floor, looks
 * perfectly ordinary in a render. Every check here is one of those — a bound the picture cannot show.
 *
 * The look is not asked about at all. That is `./gradlew :common:preview --args=canyon`, and reading the
 * slice across the bearing is the whole of it.
 */
@Tags(NEEDS_LANDFORMS)
class CanyonCheck : FunSpec({

    fun canyonAt(bearing: Double = 0.0, offset: Double = 0.0) =
        CanyonField.canyon(bearing, salt = 0L, offset = offset)

    /**
     * **Nothing is cut below the bed.** The wall fray is signed and is added to a distance, so a column
     * near the axis can be pushed to a negative one — which, before it was clamped, drove the profile far
     * below [Canyon.floorY] and opened a shaft under the river. The bed's own relief is the one thing
     * allowed under that floor, and only by its stated amount.
     */
    test("the cut stays between the bed and the rim") {
        val canyon = canyonAt()
        val deepest = canyon.floorY - ceil(canyon.bedRelief).toInt()
        for (worldX in -800..800 step 3) {
            for (worldZ in -800..800 step 97) {
                val cut = canyon.columnSpans(worldX, worldZ).ranges.firstOrNull() ?: continue
                check(cut.first >= deepest) { "at ($worldX, $worldZ) the cut reached down to ${cut.first}" }
                check(cut.first <= canyon.rimY) { "at ($worldX, $worldZ) the cut began above the rim at ${cut.first}" }
            }
        }
    }

    /**
     * **The bed is a river bed rather than a poured slab.** The profile is a function of distance alone, so
     * without relief every column across the valley floor sits at exactly one height — and the river comes
     * out an even sheet with no pools to swim and no bars to stand on.
     */
    test("the river bed rises and falls about its mean") {
        val canyon = canyonAt()
        val bedWidth = (canyon.halfWidth * canyon.profile.floorShare).toInt()
        fun floorAt(worldX: Int, worldZ: Int) = canyon.columnSpans(worldX, worldZ).ranges.firstOrNull()?.first

        // The axis is found rather than assumed: the canyon meanders by more than the bed is wide, so a
        // window fixed on the origin samples wall for most of its length.
        val beds = (-800..800 step 11).flatMap { worldZ ->
            val axis = (-700..700 step 4).minBy { worldX -> floorAt(worldX, worldZ) ?: Int.MAX_VALUE }
            (axis - bedWidth..axis + bedWidth step 2).mapNotNull { worldX -> floorAt(worldX, worldZ) }
        }
        check(beds.isNotEmpty()) { "no bed columns were found at all" }
        check(beds.min() < canyon.floorY) { "the bed never dipped under its mean of ${canyon.floorY}" }
        check(beds.max() > canyon.floorY) { "the bed never stood over its mean of ${canyon.floorY}" }

        val standsDry = beds.count { it >= CanyonField.RIVER_LEVEL }
        check(standsDry > 0) { "no part of the bed stood clear of the river, so there are no bars" }
        check(standsDry < beds.size / 2) { "$standsDry of ${beds.size} bed columns were dry, so there is no river" }
    }

    /**
     * A canyon claims a band and no more. Its reach is unbounded — a canyon crosses the whole world — so
     * nothing but this says how wide it actually is, and the flank variation makes that wider than
     * [Canyon.halfWidth] by design.
     */
    test("nothing is cut far outside the canyon's own width") {
        val canyon = canyonAt()
        val furthestItMayReach = canyon.halfWidth * (1.0 + canyon.flankVariation) * WIDEST_ALLOWED_FLANK +
            canyon.meanderReach + canyon.roughness
        for (worldZ in -800..800 step 41) {
            for (worldX in -900..900 step 3) {
                val cuts = canyon.columnSpans(worldX, worldZ).ranges.isNotEmpty()
                if (!cuts) continue
                check(kotlin.math.abs(worldX) <= furthestItMayReach) {
                    "a north-south canyon cut at x=$worldX, which is past everything it could reach"
                }
            }
        }
    }

    /** The bearing is the direction it *runs*, so an east-west canyon must not vary along x. */
    test("a bearing turns the canyon rather than the world") {
        val northSouth = canyonAt(bearing = 0.0)
        val eastWest = canyonAt(bearing = PI / 2)
        for (step in -500..500 step 7) {
            val alongOne = northSouth.columnSpans(0, step)
            val alongOther = eastWest.columnSpans(step, 0)
            check(alongOne.ranges.isNotEmpty()) { "the north-south canyon missed its own axis at z=$step" }
            check(alongOther.ranges.isNotEmpty()) { "the east-west canyon missed its own axis at x=$step" }
        }
    }

    /**
     * **The reuse the node exists for**: a land criss-crossed by many canyons is this one resized, so a
     * resize has to scale what is a length and leave alone what is a proportion.
     */
    test("resizing scales the lengths and keeps the profile") {
        val full = canyonAt()
        val small = full.resized(HALF, pivotY = full.rimY)
        check(small.halfWidth == full.halfWidth * HALF) { "the width came out ${small.halfWidth}" }
        check(small.meanderReach == full.meanderReach * HALF) { "the meander came out ${small.meanderReach}" }
        check(small.profile == full.profile) { "the profile changed to ${small.profile}" }
        check(small.bearing == full.bearing) { "the bearing turned to ${small.bearing}" }
        // Scaled about the rim, so the rim stays put and the canyon becomes shallower by the same factor.
        check(small.rimY == full.rimY) { "the rim moved to ${small.rimY}" }
        val depth = full.rimY - full.floorY
        check(small.rimY - small.floorY == (depth * HALF).toInt()) { "the depth came out ${small.rimY - small.floorY}" }
    }

    /** Where two cross, the deeper cut wins — which is what a confluence is. */
    test("crossing canyons cut to the deeper of the two") {
        val alongZ = canyonAt(bearing = 0.0)
        val alongX = canyonAt(bearing = PI / 2)
        val crossed = Canyon.cut(CanyonField.ground(), listOf(alongZ, alongX))
        val here = crossed.columnSpans(0, 0).highestSolidY ?: error("the crossing cut the world away entirely")
        val eitherAlone = listOf(alongZ, alongX).map { canyon ->
            Canyon.cut(CanyonField.ground(), listOf(canyon)).columnSpans(0, 0).highestSolidY
                ?: error("one canyon alone cut the world away entirely")
        }
        check(here == eitherAlone.min()) { "the crossing left the ground at $here, against $eitherAlone alone" }
    }

    /** Cutting nothing is not a cut. A canyon of no width must leave the shape it was handed identical. */
    test("a canyon with no width leaves the ground alone") {
        val ground = CanyonField.ground()
        check(Canyon.cut(ground, emptyList()) === ground) { "an empty list still wrapped the ground" }
        val nothing = canyonAt().copy(halfWidth = 0.0)
        check(Canyon.cut(ground, listOf(nothing)) === ground) { "a canyon of no width still wrapped the ground" }
    }

    /**
     * The world is what the canyon is not. This holds **after** the weather as well as before it, which is
     * the roof rule doing its job: rock reaching the world's ceiling has no surface to be worn back from,
     * so the plateau stands whole and its height is the build limit — a live consequence for structures
     * and features, not an accident.
     */
    test("the plateau stands solid to the ceiling") {
        for (world in listOf(CanyonField.world(NORTH_SOUTH), CanyonField.bareWorld(NORTH_SOUTH))) {
            for (worldZ in -400..400 step 53) {
                val far = world.columnSpans(FAR_FROM_THE_AXIS, worldZ)
                check(far.highestSolidY == CanyonField.WORLD_CEILING) {
                    "at ($FAR_FROM_THE_AXIS, $worldZ) the plateau topped out at ${far.highestSolidY}"
                }
            }
        }
    }

    /**
     * **The weather has to actually reach the walls**, or the canyon is a set of ruled contours and the
     * whole profile reads as machined. The bare cut and the weathered one must differ where the wall is.
     */
    test("the weather works the canyon walls") {
        val weathered = CanyonField.world(NORTH_SOUTH)
        val bare = CanyonField.bareWorld(NORTH_SOUTH)
        var worn = 0
        var walls = 0
        for (worldZ in -400..400 step 17) {
            for (worldX in -400..400 step 3) {
                val standing = bare.columnSpans(worldX, worldZ).highestSolidY ?: continue
                if (standing >= CanyonField.WORLD_CEILING) continue
                walls++
                if (weathered.columnSpans(worldX, worldZ).highestSolidY != standing) worn++
            }
        }
        check(walls > 0) { "no wall columns were found at all" }
        val share = worn.toDouble() / walls
        check(share in LEAST_WORN..MOST_WORN) { "the weather changed $worn of $walls wall columns" }
    }

    /**
     * And it must leave the rock under the river, which is the room the floor sits sixteen blocks over the
     * world's own bottom to buy.
     */
    test("the rock under the river is left whole") {
        val world = CanyonField.world(NORTH_SOUTH)
        for (worldZ in -500..500 step 23) {
            for (worldX in -500..500 step 7) {
                val lowest = world.columnSpans(worldX, worldZ).ranges.firstOrNull()?.first ?: continue
                check(lowest == CanyonField.WORLD_FLOOR) {
                    "at ($worldX, $worldZ) the rock began at $lowest rather than the world's floor"
                }
            }
        }
    }

    /** And there is a river to be had: the gorge floor has to fall below the level the sea is poured to. */
    test("the gorge floor lies under the river level") {
        val world = CanyonField.world(bearing = NORTH_SOUTH)
        val floors = (-400..400 step 7).mapNotNull { worldZ ->
            (-300..300 step 3).mapNotNull { worldX -> world.columnSpans(worldX, worldZ).highestSolidY }.min()
        }
        val dry = floors.count { it >= CanyonField.RIVER_LEVEL }
        check(dry == 0) { "$dry of ${floors.size} cross-sections had no ground below the river level" }
    }

    /**
     * The tree is the Age recipe, so the whole world has to survive being written down and read back —
     * **including which weathering it wears**, which is what the profile stopped being implicit for.
     */
    test("the weathered world round-trips through its codec") {
        val written = CanyonField.world(DIAGONAL)
        val encoded = TerrainField.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, written)
            .getOrThrow { failure -> error("the canyon would not encode: $failure") }
        val read = TerrainField.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded)
            .getOrThrow { failure -> error("the canyon would not read back: $failure") }
        check(read == written) { "read back as a different canyon: $read" }
    }
}) {
    private companion object {
        /** How far past its stated width a flank may stand, against the noise running past ±1. */
        const val WIDEST_ALLOWED_FLANK = 1.5

        const val HALF = 0.5

        /** Well past anything the canyon can reach, so this column is plateau in every cross-section. */
        const val FAR_FROM_THE_AXIS = 700

        /**
         * How much of a canyon wall the weather is expected to change. Wide bounds on purpose: this asks
         * whether erosion is happening and staying erosion, not what it looks like — that is the preview's
         * `canyon` against `canyon-nowind`.
         */
        const val LEAST_WORN = 0.2
        const val MOST_WORN = 0.95
    }
}
