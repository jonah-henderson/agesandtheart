package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.worldgen.AgeChunkGenerator
import co.voik.agesandtheart.worldgen.AgeRock
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import kotlin.math.abs

/**
 * Lava tubes, seated in the floor of a volcanic caldera (design §7.1.2).
 *
 * **The caldera is found by looking rather than by being told where it is.** A crater is a relationship
 * between a column and the ground around it — low, with higher ground around, and high above the world —
 * which is a thing the finished terrain knows and a site list would only approximate. It also survives
 * two cones blending into one massif, where a list of centres would put vents on a shoulder that the
 * blend had buried, and it survives the warp that makes a crater's outline irregular in the first place.
 *
 * This runs only in an Age whose recipe asked for volcanoes, so any high basin it finds is one of ours.
 */
object VolcanoVents : Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val land = landUnder(context) ?: return false
        val floor = calderaFloorIn(land, context.origin()) ?: return false
        return seat(context.level(), land, floor, context.random())
    }

    /**
     * The Age's own rock, asked of the generator rather than of the world.
     *
     * **A crater is tens of blocks wider than the chunk being decorated**, and a feature may only reach a
     * chunk or so past its own — read further and generation either cascades or throws, depending on where
     * the caldera happened to fall. The field has no such limit: it is a pure function of a column, so it
     * answers for a rim tens of blocks away at the cost of the arithmetic and nothing else.
     *
     * Null for an Age wearing vanilla's rock, which cannot have raised one of our cones in the first place.
     */
    private fun landUnder(context: FeaturePlaceContext<NoneFeatureConfiguration>): TerrainField? =
        ((context.chunkGenerator() as? AgeChunkGenerator)?.rock as? AgeRock.Ours)?.landform

    /**
     * The bottom of a caldera, if this chunk is the one holding it.
     *
     * **A crater is many chunks wide, and every one of them can see a bowl.** Taking the lowest column of
     * each chunk seats a vent in each, so a single volcano came out with twenty separate masses all
     * throwing at once — which is a barrage rather than a volcano, and it also breaks the force ramp,
     * since twenty small masses are not one big one. So a chunk's own candidate has to earn it against
     * the whole crater: [aloneAtTheBottom] is what makes exactly one chunk in a caldera answer.
     */
    private fun calderaFloorIn(land: TerrainField, origin: BlockPos): BlockPos? {
        var best: BlockPos? = null
        for (offsetX in 0..<CHUNK step STRIDE) {
            for (offsetZ in 0..<CHUNK step STRIDE) {
                val x = origin.x + offsetX
                val z = origin.z + offsetZ
                val y = surfaceAt(land, x, z)
                if (!isCalderaFloor(land, x, y, z)) continue
                if (best == null || y < best.y) best = BlockPos(x, y, z)
            }
        }
        return best?.takeIf { aloneAtTheBottom(land, it) }
    }

    /** Low, ringed, and high above the world — the three things that make a column a crater bottom. */
    private fun isCalderaFloor(land: TerrainField, x: Int, y: Int, z: Int): Boolean =
        y >= HIGH_ENOUGH && ringedByHigherGround(land, x, z, y)

    /**
     * Whether this column is *the* bottom of its crater, over the whole width of one.
     *
     * **A rival has to be a crater floor too, not merely lower.** Measuring against every column within
     * reach compares the floor with the mountainside outside a breached rim, which is far below it — so
     * every candidate in every caldera lost to a hillside and no volcano anywhere seated a vent.
     *
     * Ordered on `(y, x, z)` rather than on height alone, because a caldera floor is broad and flat enough
     * to hold ties, and a tie under a height-only test lets every tied chunk seat a vent — the exact
     * failure this is here to prevent. A total order has exactly one winner however flat the floor is.
     *
     * **The rivals are counted in strides, not in blocks, and that is load-bearing.** Candidates are drawn
     * from the [STRIDE] lattice, so a scan that lands anywhere else compares a column against columns that
     * could never be candidates — and then nothing in a crater is ever alone at the bottom and no volcano
     * anywhere erupts. Written as `-30..30 step STRIDE` it silently did exactly that, the range simply
     * missing zero.
     */
    private fun aloneAtTheBottom(land: TerrainField, candidate: BlockPos): Boolean {
        fun sitsBelow(x: Int, y: Int, z: Int): Boolean = when {
            y != candidate.y -> y < candidate.y
            x != candidate.x -> x < candidate.x
            else -> z < candidate.z
        }
        for (stepX in -SOLE_VENT_STRIDES..SOLE_VENT_STRIDES) {
            for (stepZ in -SOLE_VENT_STRIDES..SOLE_VENT_STRIDES) {
                val x = candidate.x + stepX * STRIDE
                val z = candidate.z + stepZ * STRIDE
                val y = surfaceAt(land, x, z)
                // Height first: it settles all but a handful of columns, and the ringing test behind it
                // is four more field evaluations that those columns then never pay for.
                if (!sitsBelow(x, y, z)) continue
                if (isCalderaFloor(land, x, y, z)) return false
            }
        }
        return true
    }

    /** The top of the rock in this column, or [NO_ROCK] where the field leaves the column empty. */
    private fun surfaceAt(land: TerrainField, x: Int, z: Int): Int =
        land.columnSpans(x, z).ranges.lastOrNull()?.last ?: NO_ROCK

    /**
     * Whether the ground climbs on nearly every bearing out of this column — which is what a crater is
     * and a hillside is not.
     *
     * **Three of the four rather than all of them.** A saddle between two peaks climbs on two and falls
     * away on the others, so three still refuses one; but a warped crater has an irregular rim, and
     * insisting on all four made a single breached wall the difference between a volcano that erupts and
     * one that is inert.
     */
    private fun ringedByHigherGround(land: TerrainField, x: Int, z: Int, floor: Int): Boolean {
        val climbing = RIM_BEARINGS.count { (stepX, stepZ) ->
            surfaceAt(land, x + stepX * RIM_REACH, z + stepZ * RIM_REACH) >= floor + RIM_CLIMB
        }
        return climbing >= ENOUGH_BEARINGS
    }

    /**
     * A short run of tubes in the floor, which is what makes a mass rather than a single vent.
     *
     * **Each column is sunk from its own surface**, not from one height read at the middle. A caldera
     * floor is rippled by the same noise as everything else, so a flat slab of tubes would surface on the
     * low side and bury itself on the high side — and a buried tube is a plugged one, which wells nothing
     * and throws nothing. Following the floor keeps the whole top layer open.
     *
     * Columns that turn out to be well above the floor are skipped: those are the crater wall, and lava
     * tubes up a wall would drain the pool down the mountain.
     */
    private fun seat(level: WorldGenLevel, land: TerrainField, floor: BlockPos, random: RandomSource): Boolean {
        val reach = NARROWEST_VENT + random.nextInt(WIDEST_VENT - NARROWEST_VENT + 1)
        val depth = SHALLOWEST_VENT + random.nextInt(DEEPEST_VENT - SHALLOWEST_VENT + 1)
        val crowns = mutableListOf<BlockPos>()
        for ((offsetX, offsetZ) in discOf(reach)) {
            val x = floor.x + offsetX
            val z = floor.z + offsetZ
            val surface = surfaceAt(land, x, z)
            if (abs(surface - floor.y) > FLOOR_RELIEF) continue
            var seated = false
            for (course in 0..<depth) {
                val at = BlockPos(x, surface - course, z)
                if (!level.getBlockState(at).isSolidRender) continue
                level.setBlock(at, AgeContent.LAVA_TUBE_BLOCK.defaultBlockState(), UPDATE_NONE)
                seated = true
            }
            if (seated) crowns += BlockPos(x, surface, z)
        }
        prime(level, crowns)
        return crowns.isNotEmpty()
    }

    /**
     * A skin of lava standing on the vents from the moment the chunk is made (Jonah, 2026-09-06).
     *
     * **A volcano you walk up to should have been erupting for an age, not for four seconds.** The pool a
     * mass wells is laid at runtime, so an unvisited caldera arrived bone dry and began filling as you
     * watched it — which reads as a volcano that has just switched on.
     *
     * **One course, and never a level.** Filling to a shared brim looks right on paper and is wrong on the
     * ground: the vents follow a rumpled floor, so a column sitting a couple of blocks low gets three or
     * four courses stacked on it, and where the disc has a gap beside it that stack is a lava *pillar*
     * standing in the air (Jonah, walked). A single course over each tube is held up by that tube whatever
     * its neighbours do, and the runtime pour deepens it within a tick or two of the chunk waking up.
     *
     * Air only — a cluster buried in rock has nowhere to put it, and one under water would only make stone.
     */
    private fun prime(level: WorldGenLevel, crowns: List<BlockPos>) {
        for (crown in crowns) {
            val at = crown.above()
            if (!level.getBlockState(at).isAir) continue
            level.setBlock(at, Blocks.LAVA.defaultBlockState(), UPDATE_NONE)
        }
    }

    /**
     * The columns within [reach] of the middle.
     *
     * **Width is what the mechanic actually needs, twice over.** The pool a mass wells is capped at the
     * mass's own height, so depth buys almost nothing and a caldera floor wants covering — and the bombs
     * only start above sixteen connected blocks, which a narrow vent could never reach. Drawing the reach
     * per caldera is what spreads volcanoes along the force ramp instead of leaving them all at one point
     * on it: the smallest vent here seeps and barely throws, the largest is at full strength.
     */
    private fun discOf(reach: Int): List<Pair<Int, Int>> =
        (-reach..reach).flatMap { x -> (-reach..reach).map { z -> x to z } }
            .filter { (x, z) -> x * x + z * z <= reach * reach }

    private val RIM_BEARINGS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private const val ENOUGH_BEARINGS = 3

    /**
     * Twenty-nine columns to a layer, and **never the thirteen a radius of two gives**.
     *
     * A vent has to clear [co.voik.agesandtheart.content.LavaTubes]' throwing threshold *after* the floor
     * filter below has taken its cut, and a narrow disc could not be relied on to: thirteen columns two
     * deep is twenty-six nominal, which a rumpled floor can drop under sixteen. Below that a vent throws
     * nothing and — since a pool's reach is sized by its vent — fills only a patch of the crater it sits
     * in, which is what a walk saw as calderas "regularly failing to fill the bottom level". Twenty-nine
     * columns survive the same cut with room to spare.
     */
    private const val NARROWEST_VENT = 3
    private const val WIDEST_VENT = 3

    /** The pool is as deep as the mass is tall, and a deep hole is a poor caldera. */
    private const val SHALLOWEST_VENT = 2
    private const val DEEPEST_VENT = 3

    /**
     * How far a vent column may sit off the floor it was found at before it counts as the wall.
     *
     * The surface noise rolls a caldera floor by about three blocks across a vent's width, so two rejected
     * a good share of a disc for being ordinary floor. Three keeps them, which is most of what makes the
     * mass dependable — and the deeper mass it leaves wells a deeper lake, since a pool rises as far as
     * the vent is tall.
     */
    private const val FLOOR_RELIEF = 3

    /** Past the widest caldera floor but inside the rim, for the craters the cones are cut to. */
    private const val RIM_REACH = 30

    /** Enough of a climb that a gentle dip in a hillside is not mistaken for a crater. */
    private const val RIM_CLIMB = 6

    /** As wide as a caldera, so one crater has one bottom and therefore one vent — in [STRIDE]s. */
    private const val SOLE_VENT_STRIDES = 8

    /**
     * Above anything ordinary ground reaches, so only a summit qualifies.
     *
     * Low enough for the shield, whose crater floor sits around y=79 before the surface roll takes a few
     * more off it — that shape is the constraint, and a bar set for the tall cones would have made it the
     * one volcano that never erupts. Set this low and an ordinary hill basin can pass, which is an
     * accepted trade (Jonah, 2026-09-06): a stray vent is dormant without a cluster behind it and reads
     * as an Age being volcanically active either way.
     */
    private const val HIGH_ENOUGH = 72

    private const val CHUNK = 16

    /** Coarse: a caldera is tens of blocks across, so every fourth column finds it. */
    private const val STRIDE = 4

    /** Below the world, so an empty column can never be mistaken for a crater floor or a rim. */
    private const val NO_ROCK = Int.MIN_VALUE / 2

    private const val UPDATE_NONE = 2
}
