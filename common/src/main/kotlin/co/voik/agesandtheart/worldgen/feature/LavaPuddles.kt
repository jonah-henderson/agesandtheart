package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The small ones: a dent in the ground with a lip round it and a lava puddle inside (design §7.1.2).
 *
 * **Not the mountains at a smaller size, which is what the first three passes were** (Jonah, walked
 * 2026-09-10). They were `Mountain` templates with every dial turned down, and however far down they went
 * they stayed the *shape* of a volcano: a cone with a crater in the top of it. Read as little hills, three
 * times running, because that is what they were.
 *
 * So this is its own thing and shares nothing with the landform. **The bottom third of a sphere, cut
 * out of the ground** (Jonah, 2026-09-10) — seven to nine blocks across at the mouth and three deep,
 * with one block of rim cut from a shell two sizes bigger and a good part of that ring missing. Nothing here
 * is a cone and nothing rises far enough to read as terrain; what you come across is something that
 * happened to the ground rather than something the ground did.
 *
 * **Being a feature rather than terrain is what contains the lava**, and that is the whole reason the maars
 * kept spilling. Terrain lava is a `SeaFill`: one level across the Age, poured wherever the rock is under
 * it — which is exactly right for a caldera fifteen deep and hopeless at this size, because a flat level
 * through a six-block dent leaks wherever the rim happens to dip below it. This digs its own hollow and
 * fills only what it dug, so there is nowhere for it to go.
 *
 * The other half of the containment is `LavaTubes.POOL_RISES_BY`: a tube at the bottom of a bowl would
 * otherwise creep its pool up to the rim and over it.
 */
object LavaPuddles {

    /**
     * Scatter what this chunk gets, and answer whether anything went down.
     *
     * Placed from [VolcanoVents], which already runs once per chunk in a volcanic Age — a second registered
     * feature would need a second id in the recipe, and what a writer asks for is *volcanoes*, not a list
     * of the things volcanoes come with.
     */
    fun scatter(level: WorldGenLevel, origin: BlockPos, random: RandomSource): Boolean {
        if (random.nextFloat() >= IN_A_CHUNK) return false
        val x = origin.x + INSET + random.nextInt(CHUNK - INSET - INSET)
        val z = origin.z + INSET + random.nextInt(CHUNK - INSET - INSET)
        return dig(level, x, z, random)
    }

    /**
     * One puddle, or nothing where the ground will not hold one.
     *
     * **Flatness is the whole of the siting rule**, and it is a containment rule rather than a taste one: a
     * bowl cut into a slope is open on its downhill side, so the lava runs out of it before anything has
     * ticked. Sampling the rim rather than the middle is what catches that — a middle tells you nothing
     * about what it is standing on.
     */
    private fun dig(level: WorldGenLevel, x: Int, z: Int, random: RandomSource): Boolean {
        val radius = NARROWEST + random.nextInt(WIDEST - NARROWEST + ONE)
        val surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - ONE
        if (!level.getBlockState(BlockPos(x, surface, z)).isSolidRender) return false
        if (!levelEnoughFor(level, x, z, radius, surface)) return false

        // **The middle of the sphere sits a third of a radius OVER the ground**, so the third of it below
        // is the part that got cut out. Everything else falls out of that one number: how deep the hole
        // is, how wide its mouth opens, and whether the rim leans in over the void or widens away from it.
        val middleY = surface + radius / SITS_PROUD
        val bottom = ceil(middleY - radius).toInt()
        val lava = surface - ONE
        val mouth = sqrt(radius * radius - (surface - middleY).let { it * it })

        // **Carved first, lipped second, and they are two passes because they overlap.** The shell the lip
        // is cut from leans in over the mouth, so a column can be hollowed *and* carry a block of rim —
        // and the hollow clears whatever stands above it, which would take that block straight back out.
        for (offsetX in -radius - ONE..radius + ONE) {
            for (offsetZ in -radius - ONE..radius + ONE) {
                val flat = (offsetX * offsetX + offsetZ * offsetZ).toDouble()
                if (sqrt(flat) > mouth) continue
                hollow(level, x + offsetX, z + offsetZ, flat, middleY, radius, bottom, surface, lava)
            }
        }
        val lipY = surface + ONE
        val fromMiddle = lipY - middleY
        for (offsetX in -radius - RIM_SHELL..radius + RIM_SHELL) {
            for (offsetZ in -radius - RIM_SHELL..radius + RIM_SHELL) {
                val flat = (offsetX * offsetX + offsetZ * offsetZ).toDouble()
                val shell = sqrt(flat + fromMiddle * fromMiddle)
                if (shell <= radius + RIM_SHELL - ONE || shell > radius + RIM_SHELL) continue
                perch(level, x + offsetX, z + offsetZ, surface, random)
            }
        }
        seatTubes(level, BlockPos(x, bottom, z), random)
        return true
    }

    /**
     * Cut this column out of the sphere and pour the lava back in.
     *
     * The test is the sphere's own — three squares against one — rather than a profile computed per ring,
     * which is what makes the thing read as a sphere rather than as a bowl somebody approximated. Anything
     * left standing over the hole goes with it, or a tree rooted where the cut lands keeps its trunk and
     * loses its stump.
     */
    private fun hollow(
        level: WorldGenLevel,
        x: Int,
        z: Int,
        flat: Double,
        middleY: Double,
        radius: Int,
        bottom: Int,
        surface: Int,
        lava: Int,
    ) {
        for (y in bottom..surface) {
            val fromMiddle = y - middleY
            if (flat + fromMiddle * fromMiddle > radius * radius) continue
            level.setBlock(BlockPos(x, y, z), if (y <= lava) LAVA else AIR, UPDATE_NONE)
        }
        var y = surface + ONE
        while (y <= surface + CLEARS_EVERYTHING && !level.getBlockState(BlockPos(x, y, z)).isAir) {
            level.setBlock(BlockPos(x, y, z), AIR, UPDATE_NONE)
            y++
        }
    }

    /**
     * One block of the rim — **cut from the shell of a sphere [RIM_SHELL] blocks bigger than the hole**
     * (Jonah, 2026-09-10), clipped to the single layer that stands above the ground.
     *
     * With the middle of the sphere sitting *over* the ground, the shell is still widening at the height
     * the rim stands — so the ring lies outside the mouth rather than leaning in over it, on a curve the
     * hole itself is on. Two sizes out leaves a stride of bare ground between the hole and the ring, which
     * is what reads as a rim standing back from a hole rather than a kerb laid round it.
     *
     * **Exactly one block, and much of it missing.** What makes a rim look thrown up rather than laid is
     * which blocks are *absent*, not how tall the rest are — [LIP_KEEPS] is how much of the ring is there.
     *
     * **And it is the column's own surface block**, so grass joins grass and stone joins stone. Taking the
     * middle's put a ring of whatever the centre happened to be around a hole in something else.
     */
    private fun perch(level: WorldGenLevel, x: Int, z: Int, surface: Int, random: RandomSource) {
        if (random.nextFloat() >= LIP_KEEPS) return
        // **On this column's own ground, not the middle's** — the second thing that was leaving rim blocks
        // in the air. The ring's *shape* is the sphere's and is measured from the middle; where the block
        // ends up is whatever it has to stand on, and the two differ by a block wherever the ground does.
        val standing = BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - ONE, z)
        // **And only where that ground is level with the hole.** A puddle sited near the brow of a hill
        // has rim columns whose ground has fallen away under them; placing there either leaves a block in
        // the air over the gap or trails the ring away down the slope. Neither is a rim, so it is simply
        // absent — which the ring is already, in three columns out of eight.
        if (abs(standing.y - surface) > LIES_WITHIN) return
        val ground = level.getBlockState(standing)
        if (!ground.isSolidRender) return
        val at = standing.above()
        if (!level.getBlockState(at).isAir) return
        level.setBlock(at, ground, UPDATE_NONE)
        // **What was the top is not the top any more.** Grass under a block is dirt, and vanilla only gets
        // there by a random tick that worldgen never waits for — so two courses of grass stood in the
        // column until something happened to notice (Jonah, walked 2026-09-10).
        BURIED[ground.block]?.let { level.setBlock(standing, it, UPDATE_NONE) }
    }

    /** Two or three tubes in the bottom of it, which is what makes a puddle a vent rather than a pool. */
    private fun seatTubes(level: WorldGenLevel, floor: BlockPos, random: RandomSource) {
        level.setBlock(floor, TUBE, UPDATE_NONE)
        for ((offsetX, offsetZ) in BESIDE) {
            if (random.nextFloat() >= ALSO) continue
            val at = BlockPos(floor.x + offsetX, floor.y, floor.z + offsetZ)
            // Lava, because by now the bowl is full of it — this runs after the hollow is filled, and a
            // solid-only test put every neighbour tube nowhere at all.
            val standing = level.getBlockState(at)
            if (!standing.isSolidRender && !standing.`is`(Blocks.LAVA)) continue
            level.setBlock(at, TUBE, UPDATE_NONE)
        }
    }

    /**
     * Whether the ground under the whole footprint is level enough to hold what is cut into it.
     *
     * **Eight ways, not four** (Jonah, walked 2026-09-10). Sampling the cardinals alone let a puddle sit on
     * the brow of a hill whose fall ran between them, and the rim then generated over the gap. The
     * diagonals are where a slope hides from four probes.
     */
    private fun levelEnoughFor(level: WorldGenLevel, x: Int, z: Int, radius: Int, surface: Int): Boolean =
        AROUND.all { (offsetX, offsetZ) ->
            val reach = radius + RIM_SHELL
            val here = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x + offsetX * reach, z + offsetZ * reach) - ONE
            abs(here - surface) <= LIES_WITHIN
        }

    /** What a surface block becomes once something is standing on it. */
    private val BURIED: Map<Block, BlockState> = mapOf(
        Blocks.GRASS_BLOCK to Blocks.DIRT.defaultBlockState(),
        Blocks.PODZOL to Blocks.DIRT.defaultBlockState(),
        Blocks.MYCELIUM to Blocks.DIRT.defaultBlockState(),
    )

    private val LAVA = Blocks.LAVA.defaultBlockState()
    private val AIR = Blocks.AIR.defaultBlockState()
    private val TUBE = AgeContent.LAVA_TUBE_BLOCK.defaultBlockState()

    private val BESIDE = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** The eight ways a slope can run away from a site — the four beside it and the four between. */
    private val AROUND = BESIDE + listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

    /** The sphere's radius — seven and a half to nine and a half blocks across at the mouth. */
    private const val NARROWEST = 4
    private const val WIDEST = 5

    /**
     * How far the middle of the sphere sits **over** the ground, as a share of its radius.
     *
     * Three, so the bottom third of the sphere is the part that was cut away and the other two thirds are
     * imaginary. It decides three things at once and they move together: the hole is two thirds of a
     * radius deep, its mouth opens to within a twentieth of the sphere's full width, and — because the
     * shell a block bigger is still widening at the height the rim stands — **the rim spreads outward
     * rather than leaning in.** Sinking the middle instead put two thirds of the sphere underground and
     * hung the inner half of the rim over the void it had just cut (Jonah, walked 2026-09-10).
     */
    private const val SITS_PROUD = 3.0

    /**
     * How many sizes out the shell the rim is cut from is.
     *
     * Two, moved out from one (Jonah, walked 2026-09-10) — at one the ring sat on the very edge of the
     * mouth; at two there is about a stride of untouched ground between the hole and it.
     */
    private const val RIM_SHELL = 2

    /** How much of the rim ring is actually there — the rest is what makes it read as broken. */
    private const val LIP_KEEPS = 0.62f

    /** How much the ground may fall across the footprint before it will not hold a puddle. */
    private const val LIES_WITHIN = 1

    /** And no further than this, so a puddle under an overhang does not take the ceiling out with it. */
    private const val CLEARS_EVERYTHING = 12

    /**
     * How often a chunk in volcanic country holds one, for an Age that asked for volcanoes and no more
     * than that.
     *
     * One chunk in two hundred is about one per two hundred blocks square — something you come across
     * rather than something the country is made of.
     *
     * **A quantifier multiplies this without touching it**, and that is why there is no rung in here: a
     * writer who asks for `teeming volcano` scales the whole placed feature through
     * [FeatureDensity], so this runs as many times over as the claim's amount says. Reading the amount
     * here as well would square it.
     */
    private const val IN_A_CHUNK = 0.005f

    /** How likely each of the four beside the middle is to be a tube as well. */
    private const val ALSO = 0.5f

    /** Kept off the chunk edge, so a puddle and its lip stay inside what this pass may write. */
    private const val INSET = 5

    private const val CHUNK = 16
    private const val UPDATE_NONE = 2
    private const val ONE = 1
}
