package co.voik.agesandtheart.content

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

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
 * **And it keeps the hour.** A mat is burning or it is out — [LIT], a state the light engine can read,
 * where the clock is not — and the world's own day decides which: alight from sunrise, dark from sunset.
 * **What you see and what you see by are separate**: the model is drawn full-bright at every hour, so a
 * night lake reads as red specks in a black cavern, while the light it *casts* is all or nothing.
 *
 * **The fade is the lake's, never a mat's.** No mat is ever half lit; what crosses gradually is which
 * mats have woken to the hour, so a lake turns in patches. That is also the whole of what it costs — two
 * light changes a day apiece rather than the twenty-eight a ladder of rungs needed, which on a vault's
 * lake is the difference between a relight and a storm of them.
 *
 * **And a mat that wakes wakes the ones around it**, which is not decoration — see [wakeTheNeighbours].
 */
class AlgaeBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(LIT, true))
    }

    override fun codec(): MapCodec<AlgaeBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(LIT)
    }

    /** Sown at whatever the hour is, so a planted mat matches the lake it was planted in. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState().setValue(LIT, isLitAtHour(context.level.defaultClockTime))

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
     * **Washed away the moment it stops being the surface of still water**, and back into the water it was
     * lying in rather than into air, so a lake keeps its face where a mat goes.
     *
     * Declaring [canSurvive] alone bought nothing: it is only ever asked through this, which is where a
     * plant answers a neighbour changing, and without it the mat survived anything at all happening around
     * it — lava ran across a lake and simply capped the growth, which is what a walk found (2026-09-08).
     * Fluid running over it is now the ordinary case: the mat dissolves, and the lava meets water rather
     * than algae.
     */
    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        direction: Direction,
        neighbourPos: BlockPos,
        neighbourState: BlockState,
        random: RandomSource,
    ): BlockState =
        if (canSurvive(state, level, pos)) state
        else Blocks.WATER.defaultBlockState()

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
        // **The hour is followed and the tick carries on**, rather than being spent on it: returning here
        // would skip the spread on the ticks that happen to fall at dawn or dusk.
        val lit = isLitAtHour(level.defaultClockTime)
        if (state.getValue(LIT) != lit) {
            level.setBlock(pos, state.setValue(LIT, lit), UPDATE_CLIENTS)
            wakeTheNeighbours(level, pos, lit)
        }
        if (random.nextInt(SPREADS_ONE_TICK_IN) != 0) return
        if (crowdedAround(level, pos)) return
        val reachX = random.nextInt(SPREAD_ACROSS * 2 + 1) - SPREAD_ACROSS
        val reachY = random.nextInt(SPREAD_DOWN * 2 + 1) - SPREAD_DOWN
        val reachZ = random.nextInt(SPREAD_ACROSS * 2 + 1) - SPREAD_ACROSS
        val to = pos.offset(reachX, reachY, reachZ)
        if (!level.getBlockState(to).`is`(Blocks.WATER)) return
        if (!canGrowAt(level, to)) return
        // **At the hour, not at the default**, which is lit. A mat sown into a dark lake would otherwise
        // come up burning and stay that way until its own random tick found it a minute later — and one
        // mat lights a wide circle of water, so what that reads as is a patch of the lake turning *on* at
        // midnight (Jonah, walked 2026-09-08).
        level.setBlockAndUpdate(to, defaultBlockState().setValue(LIT, lit))
    }

    /**
     * Every mat within reach brought to the same hour as the one that just woke.
     *
     * **This is what makes the lake turn at one speed in both directions**, and without it the two are not
     * even close. Lighting up looks quick because a lake reads as lit once the *first* mats have caught
     * up, each one lighting a wide radius; going dark waits on the *last*, and the tail of a random-tick
     * process is about `ln(mats)` times its mean — eight minutes against twenty-five seconds for a
     * thousand of them (Jonah, walked 2026-09-08: "it takes ages for them all to darken").
     *
     * Waking a neighbourhood rather than a block turns that tail into a patchwork: every mat now has as
     * many chances to be caught as there are mats near it. It costs nothing when the lake is settled,
     * because a mat that was already at the hour never gets here.
     */
    private fun wakeTheNeighbours(level: ServerLevel, pos: BlockPos, lit: Boolean) {
        for (around in BlockPos.betweenClosed(
            pos.offset(-WAKES_WITHIN, -WAKES_BELOW, -WAKES_WITHIN),
            pos.offset(WAKES_WITHIN, WAKES_BELOW, WAKES_WITHIN),
        )) {
            val neighbour = level.getBlockState(around)
            if (!neighbour.`is`(this)) continue
            if (neighbour.getValue(LIT) == lit) continue
            level.setBlock(around.immutable(), neighbour.setValue(LIT, lit), UPDATE_CLIENTS)
        }
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
         * Whether a mat is burning — **a state rather than a brightness**, because `lightLevel` is asked
         * of the block state and can never be asked of the clock.
         */
        val LIT: BooleanProperty = BooleanProperty.create("lit")

        /**
         * What a mat emits: everything or nothing.
         *
         * **Nothing is the load-bearing half**, and it is what makes this a night: the roofed dimension
         * type admits monsters at block light zero, so a floor of even one would keep every hostile thing
         * off the lake for ever, where going properly dark makes the cavern a different place after dusk.
         */
        fun lightAt(state: BlockState): Int = if (state.getValue(LIT)) BRIGHTEST else OUT

        /**
         * Whether the hour at [clockTime] is one the algae burns at — **the world's own day, kept by a
         * plant that cannot see it**: alight at sunrise, out at sunset, and nothing in between to be
         * halfway through.
         *
         * A ladder of rungs stood here and read the hour as a curve. It cost every mat twenty-eight light
         * changes a day, and it bought nothing a walk could see: the lake's fade was always the scatter of
         * the random ticks rather than the shape of the curve, and near midnight a mat that had not ticked
         * since the evening still carried a rung or two, so the lake came out speckled with lights when it
         * should have been black.
         *
         * **The dimension's own clock** (`Level.getDefaultClockTime`), which is what 26.1 replaced
         * `getDayTime` with: a world declares which clock it runs on, and a sealed Age with no sun still
         * runs on one. That is the whole trick — the algae is how a people with no sky read the hour.
         */
        fun isLitAtHour(clockTime: Long): Boolean = Math.floorMod(clockTime, A_DAY) < NIGHT_FALLS

        /**
         * The top rung, and so how many there are — **cave vines' own**, which is what the lake being the
         * light of the world asks for. One below what a block can emit, deliberately: full brightness is
         * glowstone's, and a growth should not be the brightest thing there is.
         */
        const val BRIGHTEST = 14

        /** And what it emits when it is not — see [lightAt] for why nothing is the number that matters. */
        const val OUT = 0

        private const val A_DAY = 24000L

        /** Vanilla's own sunset, and zero is its sunrise — so "dark from sundown to sunup" is literal. */
        private const val NIGHT_FALLS = 12000L

        /**
         * How far a waking mat reaches, and how far down it looks — see [wakeTheNeighbours].
         *
         * Twenty-five columns, which is enough to collapse the straggler tail without making a random tick
         * expensive. It is smaller than the spread's own reach on purpose: this is the hour travelling
         * through a patch that already exists, not the patch growing.
         */
        private const val WAKES_WITHIN = 2
        private const val WAKES_BELOW = 1

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
