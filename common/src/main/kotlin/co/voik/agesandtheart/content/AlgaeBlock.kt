package co.voik.agesandtheart.content

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * The algae the D'ni lived on: a red mat lying in the top of still, sunless water, glowing (design §7.6).
 *
 * **It is the water it floats in.** The block occupies the topmost water block rather than standing on it,
 * so [getFluidState] answers with a source and the lake reads as unbroken — a mat sitting in the block
 * above would leave a seam of air between the growth and the water it lives on.
 *
 * **Sunlight kills it, torchlight does not.** The rule is keyed on the *sky* channel alone, which is what
 * makes it a cavern plant rather than a dark-cave one: a D'ni city may be lit as brightly as its builders
 * like and the crop is untouched, and no amount of roofing over an open pond will grow it.
 *
 * **And it keeps the hour.** The glow rises and falls with the world's own day, which is how a people with
 * no sky had one — so what the light is doing lives in [GLOW], a state the light engine can read, rather
 * than in the time, which it cannot. Every light level is a rung, so the lake shades rather than bands.
 *
 * **Each mat jumps to the hour on its own random tick rather than walking toward it**, and the arithmetic
 * is what settles that. A block is random-ticked about seventeen times a Minecraft day where the hour
 * crosses twenty-eight rungs, so a mat that could only move one rung a tick could never keep up: it would
 * lag further behind through every dawn and never reach either end of the cycle. Jumping also means a
 * chunk that has been asleep is right again one tick after it wakes.
 *
 * What makes it a *fade* is that the ticks are scattered, not that the steps are small — so a lake crosses
 * raggedly over a minute or two, every mat at its own point on the curve, which is also what keeps the
 * relighting spread out instead of arriving as one spike.
 */
class AlgaeBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(GLOW, BRIGHTEST))
    }

    override fun codec(): MapCodec<AlgaeBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(GLOW)
    }

    /** Sown at whatever the hour is, so a planted mat matches the lake it was planted in. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState().setValue(GLOW, glowAtHour(context.level.defaultClockTime))

    /** The water it lies in, so the surface is unbroken and anything swimming through carries on. */
    override fun getFluidState(state: BlockState): FluidState = Fluids.WATER.getSource(false)

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = MAT

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean =
        canGrowAt(level, pos)

    /**
     * Wither in the sun, and otherwise spread across the water.
     *
     * **The spread is a mushroom's**, which is the shape that fills a place and then stops: a crowded
     * neighbourhood simply declines, so a lake comes out mottled rather than paved and clearing a patch
     * lets the ones around it close the gap. Nothing here reaches for a position the plant could not have
     * been placed at, so it can only ever follow the surface of the water it is already on.
     */
    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (level.getBrightness(LightLayer.SKY, pos) > NO_SUN_AT_ALL) {
            level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState())
            return
        }
        val hour = glowAtHour(level.defaultClockTime)
        if (state.getValue(GLOW) != hour) {
            level.setBlock(pos, state.setValue(GLOW, hour), UPDATE_CLIENTS)
            return
        }
        if (random.nextInt(SPREADS_ONE_TICK_IN) != 0) return
        if (crowdedAround(level, pos)) return
        val reachX = random.nextInt(SPREAD_ACROSS * 2 + 1) - SPREAD_ACROSS
        val reachY = random.nextInt(SPREAD_DOWN * 2 + 1) - SPREAD_DOWN
        val reachZ = random.nextInt(SPREAD_ACROSS * 2 + 1) - SPREAD_ACROSS
        val to = pos.offset(reachX, reachY, reachZ)
        if (!level.getBlockState(to).`is`(Blocks.WATER)) return
        if (!canGrowAt(level, to)) return
        level.setBlockAndUpdate(to, defaultBlockState())
    }

    /** Whether this neighbourhood already holds as much as it will hold — see [MOST_IN_REACH]. */
    private fun crowdedAround(level: ServerLevel, pos: BlockPos): Boolean {
        var found = 0
        for (around in BlockPos.betweenClosed(
            pos.offset(-SPREAD_ACROSS, -SPREAD_DOWN, -SPREAD_ACROSS),
            pos.offset(SPREAD_ACROSS, SPREAD_DOWN, SPREAD_ACROSS),
        )) {
            if (!level.getBlockState(around).`is`(this)) continue
            found++
            if (found >= MOST_IN_REACH) return true
        }
        return false
    }

    companion object {
        val CODEC: MapCodec<AlgaeBlock> = simpleCodec(::AlgaeBlock)

        /**
         * Whether a mat can lie here — **the same three facts the feature places on and the D'ni city
         * looks for**: still water, nothing over its face, and no sun on it.
         *
         * The feature cannot ask this one: the light engine has not run when decoration does, so it reads
         * the heightmap for the same fact instead (see `Algae`). Everything else is shared.
         */
        fun canGrowAt(level: LevelReader, pos: BlockPos): Boolean {
            val standsOnSomething = !level.getBlockState(pos.below()).isAir
            val isTheSurface = level.getFluidState(pos.above()).isEmpty
            val noSunReachesIt = level.getBrightness(LightLayer.SKY, pos) <= NO_SUN_AT_ALL
            return standsOnSomething && isTheSurface && noSunReachesIt
        }

        /**
         * How bright the sky channel may be over it. **Zero, not a threshold**: the plant is meant to be
         * impossible under the open sky rather than merely unhappy there, and any level above nothing at
         * all means the sun reaches it at some hour.
         */
        const val NO_SUN_AT_ALL = 0

        /**
         * How far up its cycle a mat is burning — **a state rather than a brightness**, because
         * `lightLevel` is asked of the block state and can never be asked of the clock.
         */
        val GLOW: IntegerProperty = IntegerProperty.create("glow", 0, BRIGHTEST)

        /**
         * What a mat emits: **the rung itself**, there being a state for every level it can burn at.
         *
         * **The bottom is nothing at all**, which is the whole of what makes this a night: the roofed
         * dimension type admits monsters at block light zero, so a floor of even one would keep every
         * hostile thing off the lake for ever, where going properly dark makes the cavern a different
         * place after dusk.
         */
        fun lightAt(state: BlockState): Int = state.getValue(GLOW)

        /**
         * Where the cycle stands at [clockTime] — full at noon, out at midnight, and a cosine between so
         * the change is slowest at both ends and quickest at dawn and dusk.
         *
         * **The dimension's own clock** (`Level.getDefaultClockTime`), which is what 26.1 replaced
         * `getDayTime` with: a world declares which clock it runs on, and a sealed Age with no sun still
         * runs on one. That is the whole trick — the algae is how a people with no sky read the hour.
         */
        fun glowAtHour(clockTime: Long): Int {
            val throughTheDay = Math.floorMod(clockTime, A_DAY).toDouble() / A_DAY
            val risen = (1.0 - cos((throughTheDay - AT_MIDNIGHT) * FULL_TURN)) / 2.0
            return (risen * BRIGHTEST).roundToInt().coerceIn(0, BRIGHTEST)
        }

        /**
         * The top rung, and so how many there are — **cave vines' own**, which is what the lake being the
         * light of the world asks for. One below what a block can emit, deliberately: full brightness is
         * glowstone's, and a growth should not be the brightest thing there is.
         */
        const val BRIGHTEST = 14

        private const val A_DAY = 24000L

        /** Where in the day the cycle bottoms out, as a fraction — Minecraft's own midnight. */
        private const val AT_MIDNIGHT = 0.75

        private const val FULL_TURN = 2.0 * Math.PI

        /** Told to clients, not to neighbours: a rung is a look, and nothing is listening for it. */
        private const val UPDATE_CLIENTS = 2

        /** How thin it lies in its block — enough to read as a growth on the water and not as a lid. */
        private val MAT: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0)

        /** How often a mat considers spreading at all, against a random tick. Vanilla's mushroom rate. */
        private const val SPREADS_ONE_TICK_IN = 25

        /** How far a spread reaches, and how much of a step down it will take to follow a falling shore. */
        private const val SPREAD_ACROSS = 4
        private const val SPREAD_DOWN = 1

        /**
         * How many mats in that reach are enough. **A quarter of the surface it can see**, so a lake comes
         * out mottled with open water between the patches rather than paved over — read against the 81
         * columns [SPREAD_ACROSS] spans.
         */
        private const val MOST_IN_REACH = 20
    }
}
