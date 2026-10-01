package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.PalmWood
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CocoaBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One palm, standing on the ground under [place]'s origin — what the palm beach grows and what a palm
 * sapling grows into (`notes/palm-beach-design.md`).
 *
 * **Built here because nothing in vanilla builds one.** No trunk placer leans a trunk into a curve, and no
 * foliage placer hangs fronds from a single point. So: a trunk that stands straight at the foot and bends
 * further over as it climbs, and a crown of fronds arching out from its top and drooping at the tips, with
 * coconuts hanging under them.
 *
 * **It leans towards the low ground**, which on a beach is the sea — the way palms on a real shore lean out
 * over the water.
 *
 * **Every frond is within a leaf's reach of the trunk.** Leaves rot six steps from wood, and a tree's own
 * leaves are told how far they are when it is placed, as vanilla's tree feature does. A frond long enough to
 * look right would have tips past that, so the crown is drawn as a shape first and then walked from the
 * trunk outward, and a cell the walk cannot reach in time is left out. What stands is what stays.
 */
object PalmTree : Feature {

    val CODEC: MapCodec<PalmTree> = MapCodec.unit { PalmTree }

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(level: WorldGenLevel, generator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        // Sand, or the overworld's soil — not `#dirt`, which 26.3 emptied of grass, so a sapling on a lawn grows.
        val ground = level.getBlockState(origin.below())
        if (!ground.`is`(BlockTags.SAND) && !ground.`is`(BlockTags.SUBSTRATE_OVERWORLD)) return false

        val trunk = trunkFrom(origin, leanTowards(level, origin, random), random)
        if (!trunk.all { isOpen(level, it) }) return false
        val top = trunk.last()
        val crown = reachable(crownOver(top, random), trunk)

        val log = PalmWood.LOG.defaultBlockState()
        for (at in trunk) level.setBlock(at, log, Block.UPDATE_CLIENTS)
        for ((at, distance) in crown) {
            if (!isOpen(level, at)) continue
            level.setBlock(at, PalmWood.FRONDS.defaultBlockState().setValue(LeavesBlock.DISTANCE, distance), Block.UPDATE_CLIENTS)
        }
        hangCoconuts(level, top.below(), random)
        return true
    }

    /**
     * The trunk's blocks from [origin] up, bending towards [lean] (radians, around the vertical).
     *
     * The offset grows as the height to a power over one, so the foot is near upright and the top leans
     * furthest. Each block is face-to-face with the one below it: a step sideways puts a block beside the
     * last before climbing, so the trunk never joins at a corner.
     */
    private fun trunkFrom(origin: BlockPos, lean: Float, random: RandomSource): List<BlockPos> {
        val height = random.nextIntBetweenInclusive(SHORTEST_TRUNK, TALLEST_TRUNK)
        val reach = Mth.nextFloat(random, LEAST_LEAN, MOST_LEAN)
        val trunk = mutableListOf(origin)
        for (step in 1..<height) {
            val along = (step.toFloat() / (height - 1)).pow(BEND)
            val x = origin.x + (cos(lean) * reach * along).roundToInt()
            val z = origin.z + (sin(lean) * reach * along).roundToInt()
            val below = trunk.last()
            if (x != below.x) trunk += BlockPos(x, below.y, below.z)
            if (z != below.z) trunk += BlockPos(x, below.y, z)
            trunk += BlockPos(x, origin.y + step, z)
        }
        return trunk
    }

    /** Towards whichever way the ground falls furthest within a few blocks, give or take a little. */
    private fun leanTowards(level: WorldGenLevel, origin: BlockPos, random: RandomSource): Float {
        var lowest = Int.MAX_VALUE
        var towards = random.nextFloat() * Mth.TWO_PI
        for (heading in 0..<HEADINGS_SAMPLED) {
            val angle = heading * Mth.TWO_PI / HEADINGS_SAMPLED
            val x = origin.x + (cos(angle) * LOOKS_FOR_LOW_GROUND).roundToInt()
            val z = origin.z + (sin(angle) * LOOKS_FOR_LOW_GROUND).roundToInt()
            val ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z)
            if (ground < lowest) {
                lowest = ground
                towards = angle
            }
        }
        // Level ground has no downhill, and every heading tied: the first won, so draw one instead.
        if (lowest >= origin.y) towards = random.nextFloat() * Mth.TWO_PI
        return towards + Mth.nextFloat(random, -LEAN_WANDERS, LEAN_WANDERS)
    }

    /**
     * The crown as a shape, before anything is cut from it: a tuft over the top of the trunk, and fronds
     * running out from the top level with it, arching briefly and drooping towards their tips, broadest a
     * little way out.
     */
    private fun crownOver(top: BlockPos, random: RandomSource): Set<BlockPos> {
        val crown = mutableSetOf<BlockPos>()
        val tuft = top.above()
        crown += tuft
        for (side in Direction.Plane.HORIZONTAL) crown += tuft.relative(side)
        val fronds = random.nextIntBetweenInclusive(FEWEST_FRONDS, MOST_FRONDS)
        val turned = random.nextFloat() * Mth.TWO_PI
        for (frond in 0..<fronds) {
            val angle = turned + frond * Mth.TWO_PI / fronds + Mth.nextFloat(random, -FROND_WANDERS, FROND_WANDERS)
            val length = random.nextIntBetweenInclusive(SHORTEST_FROND, LONGEST_FROND)
            crown += frondFrom(top, angle, length)
        }
        return crown
    }

    /** One frond out of [top] along [angle], face-to-face all the way, so leaves can trace it to the trunk. */
    private fun frondFrom(top: BlockPos, angle: Float, length: Int): List<BlockPos> {
        val frond = mutableListOf<BlockPos>()
        var last = top
        // Square to the frond: a frond running mostly along z broadens along x, and the other way about.
        val across = if (abs(sin(angle)) > abs(cos(angle))) Direction.EAST else Direction.SOUTH
        var along = 0f
        while (along < length) {
            along += RASTER_STEP
            val x = top.x + (cos(angle) * along).roundToInt()
            val z = top.z + (sin(angle) * along).roundToInt()
            val y = top.y + rise(along, length)
            if (x == last.x && z == last.z && y == last.y) continue
            // Face-to-face: across first, then along, then down, rather than at a corner.
            if (x != last.x && z != last.z) frond += BlockPos(x, last.y, last.z)
            if (y < last.y) frond += BlockPos(x, last.y, z)
            last = BlockPos(x, y, z)
            frond += last
            // Broadest from a third of the way out to two thirds, which is what makes it read as a leaf.
            if (along > length * BROAD_FROM && along < length * BROAD_TO) {
                frond += last.relative(across)
                frond += last.relative(across.opposite)
            }
        }
        return frond
    }

    /** How far above or below the top of the trunk a frond stands, [along] of [length] blocks out. */
    private fun rise(along: Float, length: Int): Int {
        val out = along / length
        return (ARCH * out - DROOP * out * out).roundToInt()
    }

    /**
     * [shape] cut to what leaves can trace back to [trunk] within their reach, each with how far it is — the
     * walk vanilla's own tree feature does after placing, done before instead, so nothing is placed only to
     * rot. Steps go face to face, through the shape alone.
     */
    private fun reachable(shape: Set<BlockPos>, trunk: List<BlockPos>): Map<BlockPos, Int> {
        val distances = mutableMapOf<BlockPos, Int>()
        var edge = trunk.toSet()
        for (distance in 1..FARTHEST_LEAF) {
            val next = mutableSetOf<BlockPos>()
            for (from in edge) {
                for (side in Direction.entries) {
                    val to = from.relative(side)
                    if (to in shape && to !in distances && to !in trunk) {
                        distances[to] = distance
                        next += to
                    }
                }
            }
            edge = next
        }
        return distances
    }

    /** A few coconuts on the trunk just under the crown, mostly ripe. */
    private fun hangCoconuts(level: WorldGenLevel, under: BlockPos, random: RandomSource) {
        if (!level.getBlockState(under).`is`(PalmWood.LOGS)) return
        for (side in Direction.Plane.HORIZONTAL) {
            if (random.nextFloat() >= COCONUT_CHANCE) continue
            val at = under.relative(side)
            if (!level.isEmptyBlock(at)) continue
            val ripeness = if (random.nextFloat() < RIPE_CHANCE) CocoaBlock.MAX_AGE else random.nextInt(CocoaBlock.MAX_AGE)
            val coconut = PalmWood.COCONUT.defaultBlockState()
                .setValue(CocoaBlock.FACING, side.opposite)
                .setValue(CocoaBlock.AGE, ripeness)
            level.setBlock(at, coconut, Block.UPDATE_CLIENTS)
        }
    }

    /** Whether a palm may grow through [at]: air, or something a growing thing pushes aside. */
    private fun isOpen(level: WorldGenLevel, at: BlockPos): Boolean {
        val there = level.getBlockState(at)
        return there.isAir || there.`is`(BlockTags.REPLACEABLE_BY_TREES) || there.`is`(PalmWood.SAPLING)
    }

    private const val SHORTEST_TRUNK = 7
    private const val TALLEST_TRUNK = 11

    /** How far the top of the trunk stands out from its foot, in blocks. */
    private const val LEAST_LEAN = 1.0f
    private const val MOST_LEAN = 3.5f

    /** The power the lean grows by with height: over one, so the foot stands near upright. */
    private const val BEND = 1.8f

    private const val HEADINGS_SAMPLED = 8
    private const val LOOKS_FOR_LOW_GROUND = 6
    private const val LEAN_WANDERS = 0.5f

    private const val FEWEST_FRONDS = 6
    private const val MOST_FRONDS = 8
    private const val FROND_WANDERS = 0.25f
    private const val SHORTEST_FROND = 4
    private const val LONGEST_FROND = 6
    private const val RASTER_STEP = 0.25f

    /** How a frond arches up from the crown and falls away: up by ARCH·t, down by DROOP·t², t from 0 to 1. */
    private const val ARCH = 1.2f
    private const val DROOP = 3.4f
    private const val BROAD_FROM = 0.3f
    private const val BROAD_TO = 0.7f

    /** The furthest a leaf may be from wood and live — one short of vanilla's `DECAY_DISTANCE`. */
    private const val FARTHEST_LEAF = LeavesBlock.DECAY_DISTANCE - 1

    private const val COCONUT_CHANCE = 0.35f
    private const val RIPE_CHANCE = 0.7f
}
