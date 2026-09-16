package co.voik.agesandtheart.worldgen

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.system.measureTimeMillis

/**
 * **What a column of terrain actually costs**, printed rather than asserted.
 *
 * The instrument this project did not have. `/age bench` measures a whole chunk pipeline on a server —
 * surface rules, carvers, decoration, structures — so a field's own share of it is buried, and the reading
 * is confounded by JIT warmup unless every Age is run twice (which is how a first attempt at this on
 * 2026-09-11 came out with magma chambers *cheaper* than a plain Age). A field is a pure function of a
 * column, so its cost can be read here in a second with nothing else in the way.
 *
 * **Timings, so nothing here asserts a duration.** A number that fails on a loaded laptop is a number that
 * gets deleted; what this is for is comparing two fields against each other, and a change against itself.
 *
 * **What it said the first time it was run**, over 1200×1200 blocks on seed 4242 at a written amount:
 * cones 408 ms, crater lakes 24, chamber hollows 23, chamber pools 20. So a cone is worth about seventeen
 * of everything else volcanic put together, which is what a union of two cones, truncated, warped and
 * undulated twice costs against one ellipsoid.
 *
 * **And it priced `Instanced`'s per-instance cull at about a third**: 408 against 632 ms with the cull
 * disabled, the other three fields moving in step. Free, since the terrain it produces is identical to the
 * block.
 *
 * **The number that matters most here is the one that says this is not the bottleneck.** Four and a half
 * microseconds a column over a chunk's 256 columns is about a millisecond, against the hundred-odd
 * milliseconds `/age bench` reports for a chunk. Whatever makes a volcanic Age slow to generate, it is not
 * the field being asked what shape it is.
 *
 * Tagged with the landforms so it runs under `:common:landformTest` and stays out of the everyday suite.
 */
@Tags(NEEDS_LANDFORMS)
class FieldCostCheck : FunSpec({

    /**
     * The volcanic fields side by side, per column.
     *
     * The cones are the expensive one and by a distance — a cone is a union of two cones, truncated,
     * warped, then undulated twice, where a chamber is one ellipsoid.
     */
    test("what each volcanic field costs per column, for reading") {
        val volcanoes = VolcanoField.over(SEED, WRITTEN)
        val chambers = MagmaChamberField.chambers(SEED, WRITTEN)
        val fields = listOf(
            "cones" to volcanoes.cones,
            "crater lakes" to volcanoes.lakes,
            "chamber hollows" to chambers.cones,
            "chamber pools" to chambers.lakes,
        )
        println("  per column over ${ACROSS * 2}x${ACROSS * 2} blocks, seed $SEED:")
        for ((named, field) in fields) {
            // Warm it, then read it — the same discipline the server bench needed and did not have.
            walk(field)
            val took = measureTimeMillis { walk(field) }
            val each = took * MICROS_PER_MILLI / columns()
            println("    ${named.padEnd(18)} ${took.toString().padStart(5)} ms  (${each} us/column)")
        }
    }

    /**
     * **How much rock a volcanic Age adds**, which is the other half of what a chunk costs.
     *
     * A field being asked its shape is one thing; every block it answers with has to be *written*, and then
     * surfaced, and then counted into two heightmaps. A cone is a mountain — so if naming `volcano` makes a
     * chunk slower, the first place to look is not the arithmetic but the sheer number of blocks that
     * arithmetic asks for.
     */
    test("how much rock each volcanic field adds per column, for reading") {
        val volcanoes = VolcanoField.over(SEED, WRITTEN)
        val chambers = MagmaChamberField.chambers(SEED, WRITTEN)
        val fields = listOf(
            "cones" to volcanoes.cones,
            "crater lakes" to volcanoes.lakes,
            "chamber hollows" to chambers.cones,
            "chamber pools" to chambers.lakes,
        )
        println("  blocks added per column, averaged over ${ACROSS * 2}x${ACROSS * 2} blocks:")
        for ((named, field) in fields) {
            var blocks = 0L
            for (x in -ACROSS..ACROSS step STRIDE) {
                for (z in -ACROSS..ACROSS step STRIDE) {
                    for (range in field.columnSpans(x, z).ranges) blocks += range.last - range.first + 1
                }
            }
            val each = blocks / columns()
            println("    ${named.padEnd(18)} $each blocks/column  (${each * COLUMNS_IN_A_CHUNK} a chunk)")
        }
    }

    /**
     * **How much of the work a cull can save**, which is the number that justified having one.
     *
     * `Instanced` walks every cell within the *largest* pose's reach and evaluates whatever each cell drew.
     * A column outside that particular instance's own radius can only come back empty, so counting how
     * often that happens says what the cull is worth before anyone writes it.
     */
    test("how many instance evaluations are wasted without a cull, for reading") {
        val cones = VolcanoField.over(SEED, WRITTEN).cones
        var looked = 0
        var solid = 0
        for (x in -ACROSS..ACROSS step STRIDE) {
            for (z in -ACROSS..ACROSS step STRIDE) {
                looked++
                if (cones.columnSpans(x, z).ranges.isNotEmpty()) solid++
            }
        }
        val share = solid * PERCENT / looked
        println("  $solid of $looked columns stand on a cone at all — $share%")
        println("  every other column pays the cell walk and finds nothing, which is what the cull skips")
    }
}) {
    private companion object {

        private const val SEED = 4242L

        /** What a written `volcano` is worth, which is the amount a real Age uses. */
        private const val WRITTEN = 2.0

        /** A couple of cone cells each way, so the reading covers country with and without mountains. */
        private const val ACROSS = 600
        private const val STRIDE = 4

        private const val MICROS_PER_MILLI = 1000
        private const val PERCENT = 100

        /** 16 x 16, which is what turns a per-column figure into a per-chunk one. */
        private const val COLUMNS_IN_A_CHUNK = 256

        private fun columns(): Int {
            val side = (ACROSS * 2 / STRIDE) + 1
            return side * side
        }

        private fun walk(field: co.voik.agesandtheart.worldgen.field.TerrainField) {
            for (x in -ACROSS..ACROSS step STRIDE) {
                for (z in -ACROSS..ACROSS step STRIDE) {
                    field.columnSpans(x, z)
                }
            }
        }
    }
}
