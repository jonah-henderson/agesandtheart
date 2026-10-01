package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.reward.ScarabHabitat
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * A scarab raising its pillar: sand carried from within reach of the nest a piece at a time, each laid on
 * top as mud, until the pillar is as tall as it was meant to be (design §7.1.2).
 *
 * **The sand is never taken out of the world** (Jonah): a scarab scrabbles at a patch for a moment, kicking
 * up its grains, and flies off with a load of its own making, so a beach beside a colony stays a beach.
 * Laying the mud still changes the world and answers to `mob_griefing`, as a villager's farming does; with
 * it off a colony still lives, and simply does not build.
 *
 * Sand is looked for by sampling columns at random rather than nearest first, so a pillar is not always fed
 * from the same hole — and sampling rather than sweeping keeps the search a fixed cost.
 */
class ScarabBuild(private val scarab: Scarab) : Goal() {

    private var sand: BlockPos? = null
    private var travelling = 0
    private var scrabbling = 0
    private var restUntil = 0L

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean {
        val level = scarab.level() as? ServerLevel ?: return false
        if (level.gameTime < restUntil || !isFitToWork(level)) return false
        val nest = scarab.nest() ?: return false
        if (scarab.carried != null) return true
        sand = sandToCarry(level, nest.blockPos)
        if (sand == null) restUntil = level.gameTime + LOOK_AGAIN_AFTER
        return sand != null
    }

    override fun canContinueToUse(): Boolean {
        val level = scarab.level() as? ServerLevel ?: return false
        val hasSomethingToDo = scarab.carried != null || sand != null
        return hasSomethingToDo && isFitToWork(level) && scarab.nest() != null && travelling < GIVES_UP_AFTER
    }

    override fun start() {
        travelling = 0
        scrabbling = 0
    }

    override fun stop() {
        sand = null
        scrabbling = 0
        scarab.navigation.stop()
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun tick() {
        val level = scarab.level() as? ServerLevel ?: return
        val nest = scarab.nest() ?: return
        travelling++
        val carried = scarab.carried
        if (carried == null) fetch(level) else lay(level, nest)
    }

    /**
     * Adult, fed, housed, allowed to move blocks, and with a pillar still short of what it meant — a
     * colony whose sand is gone stops here too, by never finding any.
     */
    private fun isFitToWork(level: ServerLevel): Boolean {
        val pillarIsShort = scarab.nest()?.isPillarFinished(level) == false
        return !scarab.isBaby && !scarab.isHungry && scarab.mayDisturbTheWorld(level) && pillarIsShort
    }

    private fun fetch(level: ServerLevel) {
        val at = sand ?: return
        val home = scarab.nest()?.blockPos ?: return
        val state = level.getBlockState(at)
        if (!isSandToCarry(level, at, state, home.y + 1)) {
            sand = null
            scrabbling = 0
            return
        }
        val over = Vec3.atBottomCenterOf(at.above())
        scarab.headFor(over)
        if (!scarab.isNear(over, WITHIN_REACH)) return
        scrabbling++
        if (scrabbling % KICKS_EVERY == 0) kickUp(level, at, state)
        if (scrabbling < SCRABBLES_FOR) return
        scarab.carry(state)
        sand = null
        scrabbling = 0
        travelling = 0
    }

    /** Grains thrown up off the patch, and the scuff of it — all a scarab's digging leaves behind. */
    private fun kickUp(level: ServerLevel, at: BlockPos, state: BlockState) {
        val top = Vec3.atBottomCenterOf(at.above())
        val grains = BlockParticleOption(ParticleTypes.BLOCK, state)
        level.sendParticles(grains, top.x, top.y, top.z, GRAINS, GRAIN_SPREAD, 0.0, GRAIN_SPREAD, GRAIN_SPEED)
        val sound = state.soundType
        level.playSound(null, at, sound.hitSound, SoundSource.NEUTRAL, sound.volume * SCUFF_VOLUME, sound.pitch)
    }

    private fun lay(level: ServerLevel, nest: ScarabNestBlockEntity) {
        val course = nest.topOfThePillar(level)
        // Something has been put where the pillar was to go: the load is spent, and the pillar is as tall
        // as it is going to get.
        if (!level.getBlockState(course).canBeReplaced()) {
            scarab.carry(null)
            return
        }
        // Over the course rather than in it, so the mud is never laid on the scarab laying it.
        val over = Vec3.atBottomCenterOf(course.above())
        scarab.headFor(over)
        if (!scarab.isNear(over, WITHIN_REACH)) return
        level.setBlockAndUpdate(course, nest.nextCourse(level))
        level.playSound(null, course, SoundEvents.MUD_PLACE, SoundSource.NEUTRAL, VOLUME, PITCH)
        scarab.carry(null)
        restUntil = level.gameTime + PAUSE_BETWEEN_LOADS
    }

    /** A column of loose sand within reach of the nest, or null where none of the sampled columns has one. */
    private fun sandToCarry(level: ServerLevel, nest: BlockPos): BlockPos? {
        val random = scarab.random
        val reach = ScarabHabitat.SAND_REACH
        repeat(SAMPLES) {
            val x = nest.x + random.nextIntBetweenInclusive(-reach, reach)
            val z = nest.z + random.nextIntBetweenInclusive(-reach, reach)
            val top = ScarabHabitat.groundNear(level, x, z, nest.y + 1) ?: return@repeat
            if (isSandToCarry(level, top, level.getBlockState(top), nest.y + 1)) return top
        }
        return null
    }

    /** Sand or red sand that is the ground a scarab sees there. Not suspicious sand, which holds an archaeologist's find. */
    private fun isSandToCarry(level: ServerLevel, at: BlockPos, state: BlockState, nearY: Int): Boolean {
        val isOnTop = ScarabHabitat.groundNear(level, at.x, at.z, nearY) == at
        return state.`is`(BlockTags.SAND) && !state.hasBlockEntity() && isOnTop
    }

    private companion object {
        const val SAMPLES = 64
        const val WITHIN_REACH = 0.8
        const val GIVES_UP_AFTER = 1200
        const val LOOK_AGAIN_AFTER = 200L

        /** A second and a half at the patch before it rises with its load. */
        const val SCRABBLES_FOR = 30
        const val KICKS_EVERY = 4
        const val GRAINS = 4
        const val GRAIN_SPREAD = 0.2
        const val GRAIN_SPEED = 0.15
        const val SCUFF_VOLUME = 0.5f

        /** Five seconds between loads, so a pillar rises over a morning and not in front of you at once. */
        const val PAUSE_BETWEEN_LOADS = 100L

        const val VOLUME = 0.7f
        const val PITCH = 1.1f
    }
}
