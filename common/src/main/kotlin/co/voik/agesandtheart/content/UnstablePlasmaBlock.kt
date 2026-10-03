package co.voik.agesandtheart.content

import co.voik.agesandtheart.AgeConfig
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.WeakHashMap

/**
 * Plasma let loose (design §7.1.2), erupting in bolts that fly straight and fork. Where it is [RELEASED] it
 * flashes and sends a bolt out each of the six ways. A bolt moves a block a tick and never turns, though it
 * jogs a block aside now and then and carries on the same way, which makes it jagged as lightning is; each
 * step it may fork, carrying on ahead and throwing a branch out to the side, every bolt of the fork with one
 * fork fewer. A bolt with no forks left bursts instead of forking, as a charged creeper does, and sets fire; so
 * does one that runs into something plasma cannot take.
 *
 * Where a bolt lands, whatever is not `#plasma_proof` is incinerated. The blocks are momentary — each is the
 * bolt's white-hot head for one tick, flashing as it goes — and what lingers is the glowing dust it leaves.
 */
class UnstablePlasmaBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(RELEASED, true)
                .setValue(FORKS, BOLT_FORKS)
                .setValue(HEADING, Direction.UP)
                .setValue(STRIDE, 0),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(RELEASED, FORKS, HEADING, STRIDE)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        Shapes.empty()

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        Shapes.empty()

    override fun getVisualShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        Shapes.block()

    override fun getOcclusionShape(state: BlockState): VoxelShape = Shapes.block()

    override fun propagatesSkylightDown(state: BlockState): Boolean = false

    override fun isPathfindable(state: BlockState, type: PathComputationType): Boolean = false

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        if (!oldState.`is`(this)) level.scheduleTick(pos, this, A_TICK_A_STEP)
    }

    /** A creature caught in it is badly burned rather than erased; anything else disintegrates. */
    override fun entityInside(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        entity: Entity,
        effectApplier: InsideBlockEffectApplier,
        isPrecise: Boolean,
    ) {
        if (level !is ServerLevel) return
        if (entity !is LivingEntity) return entity.discard()
        entity.hurtServer(level, Plasma.damageIn(level), CONTACT_DAMAGE)
    }

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        level.removeBlock(pos, false)
        leaveATrace(level, pos)
        if (state.getValue(RELEASED)) return erupt(level, pos)
        val heading = state.getValue(HEADING)
        val forks = state.getValue(FORKS)
        val stride = state.getValue(STRIDE)
        val forksHere = stride >= MOST_STRIDE || random.nextDouble() < FORK_CHANCE
        if (!forksHere) {
            val way = if (random.nextDouble() < JOG_CHANCE) asideOf(heading, random, 1).single() else heading
            if (!strike(level, pos.relative(way), bolt(forks, heading, stride + 1))) burst(level, pos)
            return
        }
        if (forks == 0) return burst(level, pos)
        val sideBranches = if (random.nextDouble() < TWO_SIDE_BRANCHES) 2 else 1
        val branches = listOf(heading) + asideOf(heading, random, sideBranches)
        val struck = branches.count { way -> strike(level, pos.relative(way), bolt(forks - 1, way, 0)) }
        if (struck == 0) burst(level, pos)
    }

    /** The point of release: a flash and a crack of thunder, and a bolt each of the six ways. */
    private fun erupt(level: ServerLevel, pos: BlockPos) {
        EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED)?.let { flash ->
            flash.snapTo(Vec3.atBottomCenterOf(pos))
            flash.setVisualOnly(true)
            level.addFreshEntity(flash)
        }
        level.playSound(null, pos, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.BLOCKS, LOUD, THUNDER_PITCH)
        for (way in Direction.entries) strike(level, pos.relative(way), bolt(BOLT_FORKS, way, 0))
    }

    private fun bolt(forks: Int, heading: Direction, stride: Int): BlockState =
        defaultBlockState().setValue(RELEASED, false).setValue(FORKS, forks).setValue(HEADING, heading).setValue(STRIDE, stride)

    /** [count] different ways out to the side of [heading], never ahead and never back. */
    private fun asideOf(heading: Direction, random: RandomSource, count: Int): List<Direction> {
        val sides = Direction.entries.filter { it.axis != heading.axis }.toMutableList()
        return List(count) { sides.removeAt(random.nextInt(sides.size)) }
    }

    /** Puts [bolt] at [at], incinerating what stands there; false where plasma-proof or unloaded ground stops it. */
    private fun strike(level: ServerLevel, at: BlockPos, bolt: BlockState): Boolean {
        if (!level.isLoaded(at)) return false
        val there = level.getBlockState(at)
        if (there.`is`(Plasma.PROOF)) return false
        val isOpen = there.isAir || there.canBeReplaced()
        if (!isOpen && !AgeConfig.plasmaAnnihilates.get()) return false
        level.setBlockAndUpdate(at, bolt)
        level.sendParticles(HEAD_FLASH, at.x + 0.5, at.y + 0.5, at.z + 0.5, 1, 0.0, 0.0, 0.0, 0.0)
        return true
    }

    /** Glowing dust where the bolt was, which hangs for a second or so after the block is gone, and a few glints. */
    private fun leaveATrace(level: ServerLevel, pos: BlockPos) {
        val x = pos.x + 0.5
        val y = pos.y + 0.5
        val z = pos.z + 0.5
        level.sendParticles(TRACE, x, y, z, TRACE_MOTES, SPREAD, SPREAD, SPREAD, 0.0)
        level.sendParticles(ParticleTypes.END_ROD, x, y, z, GLINTS, SPREAD, SPREAD, SPREAD, GLINT_DRIFT)
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, SPARKS, SPREAD, SPREAD, SPREAD, SPARK_SPEED)
    }

    /** The end of a bolt: a charged creeper's blast and fire, while this tick's share of them lasts. */
    private fun burst(level: ServerLevel, pos: BlockPos) {
        if (!mayBurst(level)) return setAlightAround(level, pos)
        level.playSound(null, pos, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.BLOCKS, LOUD, 1.0f)
        val breaksBlocks = if (AgeConfig.plasmaAnnihilates.get()) Level.ExplosionInteraction.BLOCK else Level.ExplosionInteraction.NONE
        level.explode(null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, BLAST, true, breaksBlocks)
    }

    private fun setAlightAround(level: ServerLevel, pos: BlockPos) {
        for (direction in Direction.entries) {
            val beside = pos.relative(direction)
            if (level.getBlockState(beside).isAir) level.setBlockAndUpdate(beside, BaseFireBlock.getState(level, beside))
        }
    }

    companion object {
        /** The point of release, which bursts all six ways; every block after it is a bolt. */
        val RELEASED: BooleanProperty = BooleanProperty.create("released")

        /** How many more times a bolt may fork before it bursts. */
        const val BOLT_FORKS = 3
        val FORKS: IntegerProperty = IntegerProperty.create("forks", 0, BOLT_FORKS)

        /** Which way the bolt flies; it never turns but by forking. */
        val HEADING: EnumProperty<Direction> = BlockStateProperties.FACING

        /** How far it has flown since it last forked: past [MOST_STRIDE] it must fork, so no bolt flies on for ever. */
        private const val MOST_STRIDE = 12
        val STRIDE: IntegerProperty = IntegerProperty.create("stride", 0, MOST_STRIDE)

        /** A fork every seven blocks or so, so a bolt mostly shoots out straight and covers some distance. */
        private const val FORK_CHANCE = 0.15
        private const val TWO_SIDE_BRANCHES = 0.2

        /** How often a step that does not fork sidesteps a block instead, keeping its heading. */
        private const val JOG_CHANCE = 0.25

        /** Lightning's pace: a block a tick, the whole eruption in a second and a half or so. */
        private const val A_TICK_A_STEP = 1

        private const val CONTACT_DAMAGE = 30.0f

        /** A charged creeper's. */
        private const val BLAST = 6.0f

        /** How many bursts a level may take in one tick; the rest only set fire, so one release cannot stall a server. */
        private const val MOST_BURSTS_A_TICK = 8

        /** Plasma's green, large enough to read as the bolt's body rather than as sparks. */
        private val TRACE = DustParticleOptions(0x9CFF7E, 2.5f)

        /** A firework's flash at each head as it lands, white with plasma's green in it. */
        private val HEAD_FLASH = ColorParticleOption.create(ParticleTypes.FLASH, 0xFFE6FFDC.toInt())
        private const val TRACE_MOTES = 10
        private const val GLINTS = 2
        private const val GLINT_DRIFT = 0.02
        private const val SPARKS = 6
        private const val SPARK_SPEED = 0.2
        private const val SPREAD = 0.35
        private const val LOUD = 4.0f
        private const val THUNDER_PITCH = 1.6f

        private val burstsThisTick = WeakHashMap<ServerLevel, Pair<Long, Int>>()

        private fun mayBurst(level: ServerLevel): Boolean {
            val (tick, count) = burstsThisTick[level] ?: (level.gameTime to 0)
            val counted = if (tick == level.gameTime) count else 0
            if (counted >= MOST_BURSTS_A_TICK) return false
            burstsThisTick[level] = level.gameTime to counted + 1
            return true
        }
    }
}
