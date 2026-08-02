package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.RiverlandsField
import io.kotest.core.spec.style.FunSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * Properties of a drainage network — the one field here with a structure rather than a shape.
 *
 * Two of these are about things a render cannot show. A valley that quietly *raised* the ground would look
 * like a ridge and read as terrain; and a field that remembered anything between calls would answer
 * correctly every time the preview asked, because the preview asks from one thread, and wrongly in a game
 * that does not. Both are cheap to assert and neither is visible in a picture.
 *
 * Whether it looks like rivers is `./gradlew :common:preview --args=riverlands`, and the plan view is the
 * whole of it: a slice shows one arbitrary valley and says nothing about the branching.
 */
class DrainageCheck : FunSpec({

    // The network itself, before the weather wraps it — these ask about the drainage, not the erosion.
    val world = RiverlandsField.network()

    /** The land alone, with nothing cut into it — the same field with its valleys taken away. */
    val bareLand = world.copy(valleyDepth = 0.0, halfWidth = 0.0)

    /** The water the same network carries, which is what `SeaFill.wet` is handed. */
    val water = RiverlandsField.water() as Drainage

    fun topAt(field: TerrainField, worldX: Int, worldZ: Int) = field.columnSpans(worldX, worldZ).highestSolidY

    /**
     * **A river only ever takes ground away.** The cut blends from the channel back to the land at the
     * valley's rim, and an error in that blend would push the ground *up* somewhere — which would read as a
     * perfectly plausible ridge in every render, and be a river running along the top of an embankment.
     */
    test("the network only ever lowers the land") {
        for (worldZ in -600..600 step 7) {
            for (worldX in -600..600 step 3) {
                val carved = topAt(world, worldX, worldZ) ?: continue
                val land = topAt(bareLand, worldX, worldZ) ?: continue
                check(carved <= land) { "at ($worldX, $worldZ) the valley raised the ground from $land to $carved" }
            }
        }
    }

    /**
     * **The hierarchy is the whole point.** Depth grows with each stream that joins, so a network that
     * worked would cut some ground far deeper than one headwater ever could — and one where the flow rule
     * had gone wrong would cut everything to the same depth and still look like valleys from above.
     */
    test("some valleys are far deeper than a headwater cuts") {
        val cuts = (-600..600 step 7).flatMap { worldZ ->
            (-600..600 step 3).mapNotNull { worldX ->
                val carved = topAt(world, worldX, worldZ) ?: return@mapNotNull null
                val land = topAt(bareLand, worldX, worldZ) ?: return@mapNotNull null
                land - carved
            }
        }
        val headwater = world.valleyDepth
        check(cuts.any { it > headwater * DEEPER_THAN_A_HEADWATER }) {
            "the deepest cut anywhere was ${cuts.max()}, against a headwater's $headwater"
        }
        // And the shallow end has to survive too, or every reach has become a trunk.
        val shallow = cuts.count { it in 1..headwater.toInt() }
        check(shallow > 0) { "no column was cut by a headwater's depth or less, so nothing is a headwater" }
    }

    /**
     * **A field is a pure function, and this one very nearly was not.** The neighbourhood it works out is
     * held in locals; an earlier draft kept the current cell in the object, which is correct under the
     * preview's single thread and wrong under the chunk workers' several.
     */
    test("the answer does not depend on the thread that asked") {
        val columns = (-400..400 step 13).flatMap { worldZ -> (-400..400 step 11).map { it to worldZ } }
        val alone = columns.associateWith { (worldX, worldZ) -> topAt(world, worldX, worldZ) }
        val together = ConcurrentHashMap<Pair<Int, Int>, Int?>()
        columns.parallelStream().forEach { (worldX, worldZ) ->
            together[worldX to worldZ] = topAt(world, worldX, worldZ)
        }
        val disagreed = columns.filter { alone[it] != together[it] }
        check(disagreed.isEmpty()) { "${disagreed.size} columns answered differently in parallel: ${disagreed.take(4)}" }
    }

    /** A lattice covers the world, so the network has to be as dense at its edge as at the origin. */
    test("the network is as dense far from the origin as at it") {
        fun carvedShareAround(originX: Int, originZ: Int): Double {
            var columns = 0
            var carved = 0
            for (worldZ in originZ - REACH..originZ + REACH step 7) {
                for (worldX in originX - REACH..originX + REACH step 5) {
                    val cut = (topAt(bareLand, worldX, worldZ) ?: continue) - (topAt(world, worldX, worldZ) ?: continue)
                    columns++
                    if (cut > 0) carved++
                }
            }
            return carved.toDouble() / columns
        }
        val here = carvedShareAround(0, 0)
        val farAway = carvedShareAround(FAR, -FAR)
        check(here in LEAST_CARVED..MOST_CARVED) { "at the origin the rivers took ${share(here)}" }
        check(farAway in LEAST_CARVED..MOST_CARVED) { "far out they took ${share(farAway)}" }
    }

    /** The trunks run wet and the headwaters dry, which is what makes it read as rivers rather than valleys. */
    test("the waterline reaches the trunks and not the headwaters") {
        val tops = (-600..600 step 7).flatMap { worldZ ->
            (-600..600 step 5).mapNotNull { worldX -> topAt(world, worldX, worldZ) }
        }
        val wet = tops.count { it < RiverlandsField.WATERLINE }.toDouble() / tops.size
        check(wet > LEAST_WET) { "only ${share(wet)} of the world was under the waterline, so there are no rivers" }
        check(wet < MOST_WET) { "${share(wet)} was under the waterline, so this is a sea with islands" }
    }

    /**
     * **The water follows its own beds.** A waterline is one plane and a network runs downhill everywhere,
     * so the test that matters is that rivers are wet *well above* where a plane could have reached — which
     * is exactly what the preset could not do before the water became a field.
     */
    test("the rivers run above the waterline as well as below it") {
        val surfaces = (-600..600 step 7).flatMap { worldZ ->
            (-600..600 step 5).mapNotNull { worldX -> water.columnSpans(worldX, worldZ).highestSolidY }
        }
        check(surfaces.isNotEmpty()) { "no column anywhere carried water" }
        val overTheLine = surfaces.count { it > RiverlandsField.WATERLINE }
        check(overTheLine > surfaces.size / 2) {
            "only $overTheLine of ${surfaces.size} wetted columns stood over the waterline, so a plane would have done"
        }
    }

    /** And it stands *in* the beds: a river surface below its own channel floor would be no river at all. */
    test("every river surface stands over the ground it runs on") {
        var running = 0
        for (worldZ in -600..600 step 11) {
            for (worldX in -600..600 step 5) {
                val surface = water.columnSpans(worldX, worldZ).highestSolidY ?: continue
                val bed = topAt(world, worldX, worldZ) ?: continue
                // Only where the water is actually over the ground is there a river here at all.
                if (surface <= bed) continue
                running++
                val land = topAt(bareLand, worldX, worldZ) ?: continue
                check(surface <= land) { "at ($worldX, $worldZ) water at $surface stood over the land at $land" }
            }
        }
        check(running > 0) { "no column had water standing over its bed, so there are no rivers" }
    }

    /**
     * **The one that caught a real defect.** A column may lie inside more than one valley, and the ground
     * takes whichever reach cut it deepest — so the water must come from that same reach. Taking the
     * *highest* surface instead stood a wall of water out over a trunk wherever a tributary's valley
     * overlapped it, at the tributary's level: a spike of water hanging in mid-air.
     *
     * What it shows up as is water far deeper than any reach could carry, so that is what this measures.
     */
    test("no column holds more water than a reach could carry") {
        val deepest = water.waterDepth * (1.0 + MOST_INFLOWS * water.depthPerOrder) + A_BLOCK_OR_TWO
        var running = 0
        for (worldZ in -900..900 step 7) {
            for (worldX in -900..900 step 3) {
                val surface = water.columnSpans(worldX, worldZ).highestSolidY ?: continue
                val bed = topAt(world, worldX, worldZ) ?: continue
                if (surface <= bed) continue
                running++
                check(surface - bed <= deepest) {
                    "at ($worldX, $worldZ) water stood ${surface - bed} over its bed, against a deepest reach of $deepest"
                }
            }
        }
        check(running > 0) { "no column carried water at all, so this checked nothing" }
    }

    test("resizing scales the lengths and keeps the profile") {
        val small = world.resized(HALF, pivotY = world.floorY)
        check(small.spacing == world.spacing * HALF) { "the reach came out ${small.spacing}" }
        check(small.halfWidth == world.halfWidth * HALF) { "the valley came out ${small.halfWidth}" }
        check(small.profile == world.profile) { "the profile changed to ${small.profile}" }
        check(small.jitter == world.jitter) { "the jitter is a share and should not scale: ${small.jitter}" }
        check(small.widthPerOrder == world.widthPerOrder) { "a per-order share scaled: ${small.widthPerOrder}" }
    }

    test("the world round-trips through its codec") {
        val encoded = TerrainField.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, world)
            .getOrThrow { failure -> error("the network would not encode: $failure") }
        val read = TerrainField.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded)
            .getOrThrow { failure -> error("the network would not read back: $failure") }
        check(read == world) { "read back as a different world: $read" }
    }
}) {
    private companion object {
        const val REACH = 900
        const val FAR = 20_000
        const val HALF = 0.5

        /** How much deeper than one headwater a trunk has to get before the hierarchy is real. */
        const val DEEPER_THAN_A_HEADWATER = 2.0

        const val LEAST_CARVED = 0.15
        const val MOST_CARVED = 0.9
        /** The most streams that can join one node, its neighbourhood being eight. */
        const val MOST_INFLOWS = 8
        const val A_BLOCK_OR_TWO = 2.0

        const val LEAST_WET = 0.02
        const val MOST_WET = 0.4

        fun share(of: Double) = "%.0f%%".format(of * 100)
    }
}
