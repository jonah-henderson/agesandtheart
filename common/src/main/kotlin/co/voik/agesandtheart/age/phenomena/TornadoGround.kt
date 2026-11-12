package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a tornado does to the ground (`notes/the-art-design.md` §5.2.2, "Tornado"), and the two ways it spends its strength: **tearing up
 * blocks** and **climbing**.
 *
 * At its foot it tears up blocks as fast as it sweeps new ground, the tallest first, down to a trench below the
 * untouched ground ahead of it, deeper toward its middle, so it strips what grows, then the turf and the soil, evenly. **Every block it takes is thrown** (Jonah): a falling
 * block its winds carry up the funnel and out, which lands as a block, or — where a block will not come away whole, or
 * too many are already in the air — broken where it stood, its drops left for the winds.
 *
 * **What a block costs is its blast resistance** (Jonah), twice over, so dirt costs one and stone twelve and anything a
 * pack adds costs what it should. Past stone's, a block is lifted only now and then, the more rarely the harder, and
 * is otherwise ground it stands on, costing nothing; so obsidian is rarely moved and never stops a tornado by itself.
 * What cannot be broken, and `#tornado_proof`, is never moved. Over water it lifts spray, a waterspout, which costs as
 * little as soil.
 *
 * **Climbing** is the rise of the ground under its foot across the last stretch it travelled, as long as its foot is
 * wide: a step of one block is free, and more grows steeply, so a cliff can end it where a hill never will.
 */
object TornadoGround {

    /** What it costs to lift [state], or null where it is never moved. */
    fun costOf(state: BlockState, level: Level, at: BlockPos): Double? {
        val isNeverMoved = state.isAir || state.getDestroySpeed(level, at) < 0.0f || state.`is`(TORNADO_PROOF)
        if (isNeverMoved) return null
        if (!state.fluidState.isEmpty && state.block is LiquidBlock) return SPRAY_COST
        return COST_PER_RESISTANCE * state.block.explosionResistance
    }

    /** How often a block costing [cost] comes away when it is tried: always up to stone's cost, then more rarely. */
    fun liftChanceFor(cost: Double): Double = if (cost <= ALWAYS_LIFTED_UP_TO) 1.0 else ALWAYS_LIFTED_UP_TO / cost

    /** What lifting a block costing [cost] spends: what it costs, but never more than [MOST_A_BLOCK_SPENDS]. */
    fun spentOn(cost: Double): Double = cost.coerceAtMost(MOST_A_BLOCK_SPENDS)

    /**
     * What climbing [rise] blocks in one stretch costs, all of it from nothing: none for a step of one, and growing as
     * the half-again power of the rest, so a rise of sixty-four costs an ordinary tornado all it has.
     */
    fun climbCostOf(rise: Double): Double = CLIMB_COST * (rise - FREE_STEP).coerceAtLeast(0.0).pow(CLIMB_POWER)

    /**
     * The ground at [x], [z]: the top of what stands there, down past the trees and what grows on the ground, so a
     * forest is not taken for a cliff.
     */
    fun groundAt(level: Level, x: Int, z: Int): Int {
        val at = BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z)
        repeat(MOST_STEPS_DOWN) {
            val state = level.getBlockState(at)
            val isGrowing = state.`is`(BlockTags.LOGS) || state.`is`(BlockTags.LEAVES) || state.canBeReplaced() || state.isAir
            // Water is replaceable, and is the ground a waterspout stands on.
            if (!isGrowing || !state.fluidState.isEmpty) return at.y + 1
            at.move(0, -1, 0)
        }
        return at.y + 1
    }

    /**
     * One tick of tearing up [howMany] blocks from under [vortex]'s foot, down to [TRENCH_DEPTH] under [untouched]
     * ground at its edge and [DEEPEST_TRENCH] at its middle, and no deeper; what is returned is the strength that
     * spent. [thrown] holds what it has in the air, which [MOST_IN_THE_AIR] bounds.
     *
     * **The highest first**, across every column under the foot, so it planes the ground down evenly into a trench
     * rather than pocking it: whatever stands tallest goes, trees from the top down, then the turf, then the soil.
     */
    fun tear(level: ServerLevel, vortex: TornadoWinds.Vortex, untouched: Int, howMany: Int, thrown: MutableSet<FallingBlockEntity>): Double {
        thrown.removeIf { !it.isAlive }
        val movesBlocks = AgeConfig.tornadoesDestroyBlocks.get()
        var spent = 0.0
        var left = howMany
        for (at in tallestFirst(level, vortex, untouched)) {
            if (left <= 0) break
            val state = level.getBlockState(at)
            val cost = costOf(state, level, at) ?: continue
            left -= 1
            if (level.random.nextDouble() >= liftChanceFor(cost)) continue
            spent += spentOn(cost)
            val isWater = !state.fluidState.isEmpty && state.block is LiquidBlock
            if (isWater || !movesBlocks) continue
            throwFrom(level, at, state, thrown)
        }
        return spent
    }

    /**
     * One tick of stripping what is flimsy from the open ground all across [vortex]'s spiral, not only under its foot:
     * [howMany] places tried at random, and the top of each broken where it is as weak as leaves or weaker: foliage gone,
     * anything else dropping what it would for the winds to take. What is returned is the strength that spent, next to
     * nothing.
     */
    fun strip(level: ServerLevel, vortex: TornadoWinds.Vortex, howMany: Int): Double {
        if (!AgeConfig.tornadoesDestroyBlocks.get()) return 0.0
        var spent = 0.0
        repeat(howMany) {
            val angle = level.random.nextDouble() * Mth.TWO_PI
            val out = vortex.spiralReach * sqrt(level.random.nextDouble())
            val x = Mth.floor(vortex.x + cos(angle) * out)
            val z = Mth.floor(vortex.z + sin(angle) * out)
            if (level.chunkSource.getChunkNow(x shr CHUNK_BITS, z shr CHUNK_BITS) == null) return@repeat
            val at = BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z)
            if (at.y > vortex.footY + REACHES_OVER_THE_FOOT || at.y < vortex.footY - REACHES_UNDER_THE_FOOT) return@repeat
            val state = level.getBlockState(at)
            val isFlimsy = state.fluidState.isEmpty && state.block.explosionResistance <= FLIMSY_UP_TO
            val cost = costOf(state, level, at)
            if (!isFlimsy || cost == null) return@repeat
            spent += cost
            // What grew there is simply gone, or a meadow would put thousands of flowers in the air; what a player may
            // have put there, a torch or a carpet, drops for them to find.
            level.destroyBlock(at, !isFoliage(state))
        }
        return spent
    }

    /** What grows wild: leaves, grass, flowers, saplings, vines and snow, which a tornado strips and leaves nothing of. */
    private fun isFoliage(state: BlockState): Boolean =
        state.`is`(BlockTags.LEAVES) || state.`is`(BlockTags.REPLACEABLE_BY_TREES) || state.`is`(BlockTags.FLOWERS) ||
            state.`is`(BlockTags.SAPLINGS) || state.`is`(BlockTags.SNOW)

    /**
     * The top of every column within [vortex]'s foot that stands over the trench's floor there and within its reach, the
     * tallest first, those level with each other in no fixed order, so no side of the trench is cut before another.
     */
    private fun tallestFirst(level: ServerLevel, vortex: TornadoWinds.Vortex, untouched: Int): List<BlockPos> {
        val reach = vortex.core
        val tops = ArrayList<BlockPos>()
        val fromX = Mth.floor(vortex.x - reach)
        val fromZ = Mth.floor(vortex.z - reach)
        val highest = vortex.footY + REACHES_OVER_THE_FOOT
        for (x in fromX..Mth.floor(vortex.x + reach)) {
            for (z in fromZ..Mth.floor(vortex.z + reach)) {
                val awayX = x + HALF - vortex.x
                val awayZ = z + HALF - vortex.z
                val isUnderTheFoot = awayX * awayX + awayZ * awayZ <= reach * reach
                if (!isUnderTheFoot || level.chunkSource.getChunkNow(x shr CHUNK_BITS, z shr CHUNK_BITS) == null) continue
                val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
                val floor = untouched - trenchDepthAt((awayX * awayX + awayZ * awayZ) / (reach * reach))
                if (top >= floor && top <= highest) tops += BlockPos(x, top, z)
            }
        }
        tops.shuffle(java.util.Random(level.random.nextLong()))
        return tops.sortedByDescending { it.y }
    }

    /**
     * How deep the trench is at [outwardSquared], the square of how far out from the middle as a share of the foot's
     * half-width: a bowl, deepest at the middle and [TRENCH_DEPTH] at the edge.
     */
    private fun trenchDepthAt(outwardSquared: Double): Int =
        Mth.floor(TRENCH_DEPTH + (DEEPEST_TRENCH - TRENCH_DEPTH) * (1.0 - outwardSquared) + HALF)

    /**
     * [state] at [at] thrown as a falling block; or, where it will not come away whole — what holds things, one half of
     * something — broken where it stands, its drops left for the winds; or, once [MOST_IN_THE_AIR] are flying, broken,
     * [OVERFLOW_DROP_CHANCE] of them dropping what they would.
     */
    private fun throwFrom(level: ServerLevel, at: BlockPos, state: BlockState, thrown: MutableSet<FallingBlockEntity>) {
        val isOneOfAPair = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) ||
            state.hasProperty(BlockStateProperties.BED_PART) || state.hasProperty(BlockStateProperties.CHEST_TYPE)
        if (state.hasBlockEntity() || isOneOfAPair) {
            level.destroyBlock(at, true)
            return
        }
        // Past what may be in the air at once only some drop anything, or the widest would leave thousands of items.
        if (thrown.size >= MOST_IN_THE_AIR) {
            level.destroyBlock(at, level.random.nextFloat() < OVERFLOW_DROP_CHANCE)
            return
        }
        thrown += FallingBlockEntity.fall(level, at, state)
    }

    /** Never moved by a tornado: nara, and the blocks a world is built of rather than made of. */
    private val TORNADO_PROOF: TagKey<Block> = TagKey.create(Registries.BLOCK, "tornado_proof".location())

    /** Dirt's blast resistance is a half, and dirt is what strength is counted in. */
    private const val COST_PER_RESISTANCE = 2.0
    /** Lifting spray off water costs what soil does. */
    private const val SPRAY_COST = 1.0
    /** Stone's cost, and up to it a block always comes away; and no block spends more than two stone. */
    private const val ALWAYS_LIFTED_UP_TO = 12.0
    private const val MOST_A_BLOCK_SPENDS = 24.0

    /**
     * Climbing: one block free, and the rest as the power one and a half, at a cost set so a rise of sixty-four ends
     * an ordinary tornado, of [Tornado.NARROWEST_STRENGTH].
     */
    private const val FREE_STEP = 1.0
    private const val CLIMB_POWER = 1.5
    private const val STOPPED_BY_A_RISE_OF = 64.0
    private val CLIMB_COST = Tornado.NARROWEST_STRENGTH / (STOPPED_BY_A_RISE_OF - FREE_STEP).pow(CLIMB_POWER)

    /**
     * How deep a trench it leaves under the untouched ground: the turf and a block under it at the edge of its foot,
     * twice that at its middle, and whatever grew.
     */
    private const val TRENCH_DEPTH = 2.0
    private const val DEEPEST_TRENCH = 4.0

    /**
     * How far over and under its foot the top of a column may stand for it to reach: the tallest trees, and a little
     * down a hillside.
     */
    private const val REACHES_OVER_THE_FOOT = 24.0
    private const val REACHES_UNDER_THE_FOOT = 8.0

    /** As weak as leaves, which a blast barely notices: what is stripped all across the spiral. */
    private const val FLIMSY_UP_TO = 0.2f
    private const val HALF = 0.5
    /** How many blocks one tornado may have in the air; past it, what it takes is broken where it stands. */
    private const val MOST_IN_THE_AIR = 200
    private const val OVERFLOW_DROP_CHANCE = 0.25f

    private const val MOST_STEPS_DOWN = 48
    private const val CHUNK_BITS = 4
}
