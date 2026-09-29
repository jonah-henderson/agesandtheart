package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.generation.AgeGeneration
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.MagmaChambers
import co.voik.agesandtheart.age.aspect.Volcanoes
import co.voik.agesandtheart.worldgen.field.Box
import co.voik.agesandtheart.worldgen.field.StandingFluid
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.level.block.Blocks

/**
 * **Shape of ours laid over rock that is not** — the seam that lets a writer say `volcano` over vanilla's
 * own landmass and get mountains (see [Overlay]).
 *
 * Two separate things fail here, and only one of them is arithmetic.
 *
 * The first is the arithmetic: an overlay is read by the chunk fill one way and by the two *height* exits
 * another, and those three have to agree about every block or a structure places against ground that is not
 * where the blocks are. That is checked exhaustively below rather than argued, because the failure is
 * invisible — a village generates, and it generates inside the mountain.
 *
 * The second is wiring, and it is the bug this file was written for: `volcano` was accepted, priced and
 * scored by every Age, and the cones were built **only for a landform of ours**. The word was taken and
 * ignored, which is worse than refusing it. So the last tests here ask the recipe path itself.
 */
@Tags(NEEDS_REGISTRIES)
class OverlayCheck : FunSpec({

    /**
     * **The two readers cannot disagree**, checked block by block over a shape with all three parts in it.
     *
     * `forEachBlock` is what writes a chunk and `blockAt` is what answers a height query, and they are
     * separate code. Precedence is where they would drift: a caldera cut from a cone and a lake standing in
     * the caldera means the same Y is claimed by all three of raise, hollow and pour, and *which one wins*
     * has to be the same answer twice.
     */
    test("what the fill writes is what a height query reads") {
        for (x in -A_FEW..A_FEW) {
            for (z in -A_FEW..A_FEW) {
                val column = A_CONE_WITH_A_LAKE_IN_IT.at(x, z)
                // What the fill would leave standing, taken the way a chunk takes it: last write wins.
                val written = HashMap<Int, String>()
                column.forEachBlock(ROCK) { y, state -> written[y] = state.toString() }
                for (y in LOW..HIGH) {
                    val asked = column.blockAt(y, ROCK)?.toString()
                    check(asked == written[y]) {
                        "at ($x, $y, $z) the fill writes ${written[y] ?: "nothing"} " +
                            "and a height query reads ${asked ?: "nothing"}"
                    }
                }
            }
        }
    }

    /**
     * And that the sample is worth anything: a check over an overlay that happened to say nothing anywhere
     * would pass on air. All three kinds have to appear in it.
     */
    test("the sample actually holds rock, a hollow and a lake") {
        val columns = (-A_FEW..A_FEW).flatMap { x -> (-A_FEW..A_FEW).map { z -> A_CONE_WITH_A_LAKE_IN_IT.at(x, z) } }
        check(columns.any { it.rock.ranges.isNotEmpty() }) { "no column of the sample holds any rock" }
        check(columns.any { it.air.ranges.isNotEmpty() }) { "nothing in the sample was hollowed" }
        check(columns.any { it.fluids.isNotEmpty() }) { "nothing in the sample holds a lake" }
    }

    /**
     * **`topmostY` counts a hollow**, which is the whole reason it is not a surface height.
     *
     * It is the test [AgeChunkGenerator] uses to decide whether a column needs composing against vanilla's
     * at all, so a hollow it did not count is a caldera that silently never lowers the ground.
     */
    test("the top of an overlay counts what it took out as well as what it put in") {
        val onlyAHollow = Overlay(hollows = Box(-A_FEW, LOW, -A_FEW, A_FEW, HIGH, A_FEW))
        val column = onlyAHollow.at(0, 0)
        check(column.rock.ranges.isEmpty()) { "a hollow put rock somewhere" }
        check(column.topmostY == HIGH) { "a hollow reaching y=$HIGH tops out at ${column.topmostY}" }
        check(!column.saysNothing) { "a column that is entirely hollowed says nothing" }
    }

    /**
     * **The bug: a book naming a volcano over vanilla's landmass gets mountains.**
     *
     * Read at a cone's own axis, taken from the same field the terrain builds from — so this is the cone
     * the Age would actually have, not a hope that one is nearby.
     */
    test("a volcano written over vanilla's own landmass still raises its cones") {
        val overlay = AgeGeneration.volcanicOverlay(vanillaTerrainWriting(Volcanoes.ID.toString()), SEED)
        check(!overlay.isEmpty) { "a book naming a volcano over vanilla's landmass produced no overlay" }

        // A caldera column: its own rock is the crater *floor*, so the mountain is read from the rim
        // around it rather than from here.
        val (atX, atZ) = firstCaldera()
        val floor = overlay.at(atX, atZ)

        val rim = (-ACROSS..ACROSS step STRIDE).flatMap { offsetX ->
            (-ACROSS..ACROSS step STRIDE).mapNotNull { offsetZ ->
                overlay.at(atX + offsetX, atZ + offsetZ).rock.highestSolidY
            }
        }.max()
        check(rim > A_MOUNTAIN) {
            "the cone at ($atX, $atZ) tops out at y=$rim, which is not a mountain over vanilla's ground"
        }

        // The half a heightmap could never show, and the half `VolcanoVents` seats its tubes in.
        val lake = floor.fluids.singleOrNull()
        check(lake != null) { "the caldera at ($atX, $atZ) carries no crater lake" }
        check(lake.second.highestSolidY!! > floor.rock.highestSolidY!!) {
            "the lava at ($atX, $atZ) stands under the crater floor rather than in it"
        }
    }

    /**
     * And the split holds on this path too: three words, three features, and naming one does not smuggle in
     * another. The words were one word until 2026-09-10 and the overlay is where two of them now meet.
     */
    test("the three volcanic words stay separate over vanilla's landmass") {
        val conesOnly = AgeGeneration.volcanicOverlay(vanillaTerrainWriting(Volcanoes.ID.toString()), SEED)
        check(conesOnly.raises != null) { "a volcano raises nothing" }
        check(conesOnly.hollows == null) { "a volcano on its own hollowed out a magma chamber" }

        val chambersOnly = AgeGeneration.volcanicOverlay(vanillaTerrainWriting(MagmaChambers.ID.toString()), SEED)
        check(chambersOnly.hollows != null) { "a magma chamber hollows nothing" }
        check(chambersOnly.raises == null) { "a magma chamber on its own raised a mountain" }

        // And the control: a book naming neither gets no terrain of ours at all.
        check(AgeGeneration.volcanicOverlay(vanillaTerrainWriting(), SEED).isEmpty) {
            "an Age naming nothing volcanic was given an overlay anyway"
        }
    }
}) {
    private companion object {

        private const val SEED = 4242L

        private val ROCK by lazy { Blocks.STONE.defaultBlockState() }
        private val LAVA by lazy { Blocks.LAVA.defaultBlockState() }

        /** A few columns each way — the claim is per column, so a handful of them exercises it fully. */
        private const val A_FEW = 6

        private const val LOW = 40
        private const val HIGH = 80

        /**
         * Forty blocks clear of vanilla's own waterline — the bar is "a mountain arrived", not a
         * particular mountain, and the cones tune under `VolcanoField`. Measured at y=117 on this seed.
         */
        private const val A_MOUNTAIN = 63 + 40

        /**
         * A shape with all three parts overlapping on purpose: a block of rock, a hollow cut down into its
         * top, and lava standing in the hollow. Every Y in the middle is claimed by all three, which is the
         * only place precedence can be got wrong.
         */
        private val A_CONE_WITH_A_LAKE_IN_IT: Overlay by lazy {
            Overlay(
                raises = Box(-A_FEW, LOW, -A_FEW, A_FEW, HIGH, A_FEW),
                hollows = Box(-HALF, MIDDLE, -HALF, HALF, HIGH, HALF),
                pours = listOf(
                    StandingFluid(
                        Box(-HALF, MIDDLE, -HALF, HALF, MIDDLE + A_FEW, HALF),
                        LAVA,
                        StandingFluid.CRATER_LAKES,
                    ),
                ),
            )
        }

        private const val HALF = A_FEW / 2
        private const val MIDDLE = (LOW + HIGH) / 2

        /** A hand-composed Age over vanilla's landmass, naming [placed] and nothing else. */
        private fun vanillaTerrainWriting(vararg placed: String): AgeComposition {
            // The pool's own slot within the aspect, which is where a claim lives — `features=` alone sets
            // the aspect's value set and `claimsOn(PLACES)` never sees it.
            val slot = "${Aspect.FEATURES.page}.${Features.PLACES.name}"
            val spelled = if (placed.isEmpty()) "" else " $slot=${placed.joinToString(",")}"
            return AgeComposition.parse("landmass=overworld$spelled").getOrElse {
                error("'landmass=overworld$spelled' is not a composition this build parses: $it")
            }
        }

        /**
         * A crater a volcano would actually have on [SEED] — read off the same field the terrain builds
         * from, so this is a cone the Age has rather than a hope that one is nearby.
         *
         * Found by its **lava** rather than its height: a tall column could be a flank, where a column
         * holding a crater lake is a caldera by definition.
         */
        private fun firstCaldera(): Pair<Int, Int> {
            val volcanoes = VolcanoField.over(SEED)
            for (x in 0..SEARCH step STRIDE) {
                for (z in 0..SEARCH step STRIDE) {
                    if (volcanoes.lakes.columnSpans(x, z).ranges.isNotEmpty()) return x to z
                }
            }
            error("no crater lake within ${SEARCH}x$SEARCH blocks of the origin on seed $SEED")
        }

        private const val SEARCH = 3000

        /** The lattice the vent feature draws from, and past the widest caldera to its rim. */
        private const val STRIDE = 4
        private const val ACROSS = 60
    }
}
