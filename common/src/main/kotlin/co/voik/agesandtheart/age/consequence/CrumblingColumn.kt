package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.phenomena.CaveIn
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB

/**
 * One column a collapse tear is taking (design §5.3): the whole column cracks, warns, and then crumbles
 * from the bottom up, the floor of it becoming the tear. It looks like a cave-in's crumbling, but its
 * settings are its own so the two can be tuned apart.
 *
 * The crack is vanilla's mining overlay, which draws on any block without changing it.
 *
 * It saves only when it began and how high the column stood; every layer's moment is worked out from
 * those, so a reloaded column carries on where its clock says it is.
 */
class CrumblingColumn(type: EntityType<out CrumblingColumn>, level: Level) : Entity(type, level) {

    private var startedAt = NOT_STARTED

    /** The highest layer this takes, read once so the sweep is not chasing a heightmap it is lowering. */
    private var top = UNREAD

    /** The lowest layer still standing. Not saved: taking an overdue layer twice is harmless. */
    private var nextToGo = UNREAD

    /** Whether this run has put the warning cracks on. Not saved, since a client forgets them on reload. */
    private var showedTheCracks = false

    /** The layer that last threw rubble, so one column does not shed on two layers running. */
    private var threwRubbleAt = UNREAD

    override fun defineSynchedData(builder: SynchedEntityData.Builder) = Unit

    override fun isPickable(): Boolean = false

    override fun canBeCollidedWith(against: Entity?): Boolean = false

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean = false

    override fun tick() {
        val level = level()
        if (level !is ServerLevel) return
        settleIn(level)
        if (!showedTheCracks) showTheCracks(level)
        val elapsed = level.gameTime - startedAt
        for (y in nextToGo..top) {
            val sinceItStarted = elapsed - waitsFor(level, y)
            // Each layer waits longer than the one under it, so nothing above can be due yet either.
            if (sinceItStarted < 0) break
            val stage = CRACKED_TO + (sinceItStarted / A_STAGE).toInt()
            if (stage < GONE) {
                crack(level, at(y), stage)
                continue
            }
            giveWay(level, y)
            nextToGo = y + 1
        }
        if (nextToGo > top) discard()
    }

    private fun settleIn(level: ServerLevel) {
        if (startedAt == NOT_STARTED) startedAt = level.gameTime
        if (top == UNREAD) top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ)
        if (nextToGo == UNREAD) nextToGo = Collapse.floorOfATear(level)
    }

    private fun showTheCracks(level: ServerLevel) {
        for (y in nextToGo..top) {
            if (!level.getBlockState(at(y)).isAir && !isSpared(level, at(y))) crack(level, at(y), CRACKED_TO)
        }
        showedTheCracks = true
    }

    /** The warning, and then a little longer the higher the layer, so the column goes from the bottom up. */
    private fun waitsFor(level: ServerLevel, y: Int): Long =
        WARNS_FOR + (y - Collapse.floorOfATear(level)) / LAYERS_A_TICK

    /**
     * The floor of the column becomes the tear; anything above is simply removed, with no drops, and sometimes
     * falls as rubble instead. What `#immune_to_collapse` names, and what nara holds, stays where it stands,
     * as a cave-in leaves it.
     */
    private fun giveWay(level: ServerLevel, y: Int) {
        val at = at(y)
        crack(level, at, CLEARED)
        val standing = level.getBlockState(at)
        if (isSpared(level, at)) return
        if (y == Collapse.floorOfATear(level)) {
            level.setBlock(at, TEAR, Block.UPDATE_ALL)
            return
        }
        if (standing.isAir) return
        level.setBlock(at, AIR, Block.UPDATE_ALL)
        val throwsRubble = random.nextFloat() < LEAVES_RUBBLE && standing.isSolidRender
        if (throwsRubble && threwRubbleAt != y - 1) {
            threwRubbleAt = y
            FallingBlockEntity.fall(level, at, standing)
        }
    }

    private fun at(y: Int) = BlockPos(blockX, y, blockZ)

    private fun isSpared(level: ServerLevel, at: BlockPos): Boolean =
        level.getBlockState(at).`is`(CaveIn.IMMUNE_TO_COLLAPSE) || BetweenNara.holds(level, at)

    private fun crack(level: ServerLevel, at: BlockPos, stage: Int) =
        level.destroyBlockProgress(breakerFor(at), at, stage)

    /**
     * A breaker id per position: vanilla keys the overlay by breaker, so one id cracks one block at a time.
     * Negative, so it can never collide with a real entity's id.
     */
    private fun breakerFor(at: BlockPos): Int = -(at.asLong().hashCode() and Int.MAX_VALUE) - 1

    override fun readAdditionalSaveData(input: ValueInput) {
        startedAt = input.getLongOr(STARTED_KEY, NOT_STARTED)
        top = input.getIntOr(TOP_KEY, UNREAD)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.putLong(STARTED_KEY, startedAt)
        output.putInt(TOP_KEY, top)
    }

    companion object {
        /** Start the column at [x], [z] crumbling, unless one already is. */
        fun begin(level: ServerLevel, x: Int, z: Int) {
            val column = AABB(
                x.toDouble(), level.minY.toDouble(), z.toDouble(),
                x + 1.0, (level.minY + level.height).toDouble(), z + 1.0,
            )
            if (level.getEntities(AgeContent.CRUMBLING_COLUMN, column) { true }.isNotEmpty()) return
            val born = AgeContent.CRUMBLING_COLUMN.create(level, EntitySpawnReason.EVENT) ?: return
            born.startedAt = level.gameTime
            born.top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
            born.setPos(x + HALF, born.top.toDouble(), z + HALF)
            level.addFreshEntity(born)
        }

        /** Stage 5 of vanilla's ten: cracked enough to be unmistakable, far from falling. */
        private const val CRACKED_TO = 5

        /** Past the last stage, which is what removing the overlay looks like. */
        private const val GONE = 10
        private const val CLEARED = -1

        /** How long the column sits cracked before it starts going: three seconds, a stride and a jump (§5.3). */
        private const val WARNS_FOR = 60L

        /** Ticks per stage once a layer goes, so about a quarter of a second from first movement to gone. */
        private const val A_STAGE = 1L

        /** How many layers start going each tick once the warning is over. */
        private const val LAYERS_A_TICK = 2

        /** The share of what gives way that falls as a block rather than vanishing. */
        private const val LEAVES_RUBBLE = 0.08f

        private const val NOT_STARTED = Long.MIN_VALUE
        private const val UNREAD = Int.MIN_VALUE
        private const val HALF = 0.5

        private const val STARTED_KEY = "started_at"
        private const val TOP_KEY = "top"

        private val TEAR by lazy { AgeContent.COLLAPSING_FISSURE_BLOCK.defaultBlockState() }
        private val AIR = Blocks.AIR.defaultBlockState()
    }
}
