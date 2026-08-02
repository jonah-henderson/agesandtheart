package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.IslandsField
import io.kotest.core.spec.style.FunSpec

/**
 * Islands, and chiefly the one property the preset exists to keep: **they never become a continent.**
 *
 * That is not something a render can show. The islands lie thousands of blocks apart, so a picture holds
 * one of them and says nothing about the next; two merging is a thing you would find by sailing, once, in
 * one world, at one size. It is arithmetic, so it is asserted as arithmetic.
 */
class IslandsCheck : FunSpec({

    /**
     * **The guarantee.** Two neighbours at their closest, each drawn at its largest and its coast wandering
     * as far out as it goes, still have open sea between them — at every size a writer can ask for.
     */
    test("no two islands can touch, at any extent") {
        for (extent in IslandsField.Extent.entries) {
            val apart = IslandsField.leastApart(extent)
            val reach = IslandsField.widestReach(extent)
            check(apart > 2.0 * reach) {
                "${extent.key}: neighbours can close to $apart with each reaching $reach, so they merge"
            }
        }
    }

    /** And the sea between them is a voyage rather than a swim, whatever size they are. */
    test("the sea between islands is a voyage") {
        for (extent in IslandsField.Extent.entries) {
            val openWater = IslandsField.leastApart(extent) - 2.0 * IslandsField.widestReach(extent)
            check(openWater > LEAST_OPEN_WATER) {
                "${extent.key}: only $openWater blocks of open water at the narrowest"
            }
        }
    }

    /**
     * **Somewhere to arrive.** `Ages.findFooting` walks out 288 blocks and then gives up, and islands lie
     * thousands apart — so without the origin island being anchored, a writer arrives mid-ocean.
     */
    test("the origin stands over the waterline") {
        for (extent in IslandsField.Extent.entries) {
            val world = IslandsField.world(extent.key)
            val here = world.columnSpans(0, 0).highestSolidY
            check(here != null && here > IslandsField.SEA_LEVEL) {
                "${extent.key}: the origin stands at $here, against a waterline of ${IslandsField.SEA_LEVEL}"
            }
        }
    }

    /**
     * An island **ends**. Past its widest reach there is seabed and nothing else out to its neighbour,
     * which is the difference between an island and a coastline that happens to curve back.
     */
    test("there is nothing but seabed between one island and the next") {
        val extent = IslandsField.Extent.BROAD
        val world = IslandsField.world(extent.key)
        // Past the *shelf*, not just the land: an island's shallows carry a long way out, and they are
        // not somewhere the seabed has been reached yet.
        val from = IslandsField.shelfReach(extent).toInt() + 1
        val to = (IslandsField.leastApart(extent) - IslandsField.shelfReach(extent)).toInt() - 1
        var sampled = 0
        for (out in from..to step 37) {
            for (bearing in listOf(out to 0, 0 to out, -out to 0, 0 to -out)) {
                val top = world.columnSpans(bearing.first, bearing.second).highestSolidY
                sampled++
                check(top == IslandsField.SEABED_Y) { "at $bearing the ground stood at $top rather than the seabed" }
            }
        }
        check(sampled > 40) { "only $sampled columns of open sea were sampled" }
    }

    /**
     * **A large, flat, sandy beach** — the thing this preset has that vanilla's coasts cannot.
     *
     * Vanilla's shore is wherever its terrain noise happens to cross sea level, so it is as steep as
     * whatever made it. Here the beach is a *stated* part of the profile, so what this asserts is that it
     * is genuinely both: wide enough to be somewhere, and level enough to be sand rather than a bank.
     */
    test("the shore is a wide, level beach") {
        for (extent in IslandsField.Extent.entries) {
            val world = IslandsField.world(extent.key)
            fun topAt(out: Int) = world.columnSpans(out, 0).highestSolidY ?: 0
            val reach = IslandsField.widestReach(extent).toInt()

            // Walk in from open water until the ground first stands over the waterline: that is the coast.
            val coast = (reach downTo 0).firstOrNull { topAt(it) > IslandsField.SEA_LEVEL }
                ?: error("${extent.key}: no coast found along +x")
            // And keep walking while it stays beach-height. That run is the beach.
            val backOfTheBeach = (coast downTo 0).firstOrNull { topAt(it) > IslandsField.SEA_LEVEL + BEACH_HEIGHT }
                ?: error("${extent.key}: the island never rose past beach height")
            val width = coast - backOfTheBeach

            check(width >= LEAST_BEACH) { "${extent.key}: the beach ran only $width blocks before rising" }
            val climb = topAt(backOfTheBeach) - topAt(coast)
            check(climb <= BEACH_HEIGHT) { "${extent.key}: the beach climbed $climb blocks, which is a bank" }
        }
    }

    /** The size knob is a size knob: a modest island is plainly smaller than a vast one. */
    test("the extents differ in the way they claim to") {
        fun landAcross(extent: IslandsField.Extent): Int {
            val world = IslandsField.world(extent.key)
            return (-2400..2400 step 8).count { at ->
                (world.columnSpans(at, 0).highestSolidY ?: 0) > IslandsField.SEA_LEVEL
            } * 8
        }
        val modest = landAcross(IslandsField.Extent.MODEST)
        val broad = landAcross(IslandsField.Extent.BROAD)
        val vast = landAcross(IslandsField.Extent.VAST)
        check(modest < broad && broad < vast) { "the extents came out $modest, $broad, $vast across" }
        check(modest > 0) { "the modest island had no land across its middle at all" }
    }

    test("the world round-trips through its codec") {
        val written = IslandsField.world(IslandsField.Extent.VAST.key)
        val encoded = TerrainField.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, written)
            .getOrThrow { failure -> error("the islands would not encode: $failure") }
        val read = TerrainField.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded)
            .getOrThrow { failure -> error("the islands would not read back: $failure") }
        check(read == written) { "read back as a different world" }
    }
}) {
    private companion object {
        /** Far enough that you cannot see the next island from this one's shore. */
        const val LEAST_OPEN_WATER = 1000.0

        /** How far over the water ground may stand and still be beach rather than the hill behind it. */
        const val BEACH_HEIGHT = 6

        /** And how far it has to run. Wide enough to be somewhere, at even the smallest extent. */
        const val LEAST_BEACH = 25
    }
}
