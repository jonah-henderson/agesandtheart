package co.voik.agesandtheart.worldgen.feature

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature

/**
 * A small tree long dead, standing bare on the ground under [place]'s origin: a pale oak trunk, crooked near its top,
 * and a few branches stepping out and up from it, with no leaves at all. What grows in a hollow tree's crown.
 */
object DeadTree : Feature {

    val CODEC: MapCodec<DeadTree> = MapCodec.unit { DeadTree }

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(level: WorldGenLevel, generator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        if (!PalmTree.takesRootIn(level.getBlockState(origin.below()))) return false
        val height = random.nextIntBetweenInclusive(SHORTEST_TRUNK, TALLEST_TRUNK)
        val trunk = (0..<height).map { origin.above(it) }
        if (!trunk.all { isOpen(level, it) }) return false
        for (at in trunk) level.setBlock(at, log(Direction.Axis.Y), Block.UPDATE_CLIENTS)

        val crookedTop = Direction.Plane.HORIZONTAL.getRandomDirection(random)
        stepOut(level, trunk.last(), crookedTop, random.nextIntBetweenInclusive(1, CROOK), random)
        repeat(random.nextIntBetweenInclusive(FEWEST_BRANCHES, MOST_BRANCHES)) {
            val from = trunk[random.nextIntBetweenInclusive(height / 2, height - 1)]
            stepOut(level, from, Direction.Plane.HORIZONTAL.getRandomDirection(random), random.nextIntBetweenInclusive(SHORTEST_BRANCH, LONGEST_BRANCH), random)
        }
        return true
    }

    /** A branch from beside [from] out along [heading], climbing a block now and then, each log laid along its own step. */
    private fun stepOut(level: WorldGenLevel, from: BlockPos, heading: Direction, steps: Int, random: RandomSource) {
        var at = from
        repeat(steps) {
            val climbs = random.nextFloat() < CLIMB_CHANCE
            val next = if (climbs) at.above() else at.relative(heading)
            if (!isOpen(level, next)) return
            level.setBlock(next, log(if (climbs) Direction.Axis.Y else heading.axis), Block.UPDATE_CLIENTS)
            at = next
        }
    }

    private fun log(axis: Direction.Axis) = Blocks.PALE_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis)

    private fun isOpen(level: WorldGenLevel, at: BlockPos): Boolean {
        val there = level.getBlockState(at)
        return there.isAir || there.`is`(BlockTags.REPLACEABLE_BY_TREES)
    }

    private const val SHORTEST_TRUNK = 4
    private const val TALLEST_TRUNK = 8
    private const val CROOK = 2
    private const val FEWEST_BRANCHES = 2
    private const val MOST_BRANCHES = 4
    private const val SHORTEST_BRANCH = 2
    private const val LONGEST_BRANCH = 4
    private const val CLIMB_CHANCE = 0.35f
}
