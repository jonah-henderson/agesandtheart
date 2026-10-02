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
 * A scarab working on its colony's pillars (design §7.1.2): **communal** — any adult that is fed works on the
 * nearest pillar still short of its height, or begins one on free ground — carrying sand a piece at a time
 * and laying each on top as mud, and the pillar's nest course as an empty nest for any homeless scarab.
 *
 * **A homeless scarab works hard**, since a nest is what it is building towards; **a housed one at leisure**,
 * now and then and resting long between loads, which is how a settled colony slowly adds empty nests and so
 * room to breed. Hunger stops both.
 *
 * **The sand is never taken out of the world** (Jonah): a scarab scrabbles at a patch for a moment, kicking
 * up its grains, and flies off with a load of its own making, so a beach beside a colony stays a beach.
 * Laying the mud still changes the world and answers to `mob_griefing`.
 */
class ScarabBuild(private val scarab: Scarab) : Goal() {

    /** The foot of the pillar being worked on, or null while there is none yet. */
    private var pillar: BlockPos? = null

    /** Free mud this scarab means to begin a pillar on, until it gets there. */
    private var site: BlockPos? = null

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
        val feelsLikeIt = !scarab.isHoused || scarab.random.nextInt(LEISURE_ODDS) == 0
        if (!feelsLikeIt) return false
        val anchor = workingFrom()
        pillar = unfinishedPillarNear(level, anchor)
        site = if (pillar == null) siteNear(level, anchor) else null
        val hasWork = pillar != null || site != null
        if (!hasWork) restUntil = level.gameTime + LOOK_AGAIN_AFTER
        return hasWork
    }

    override fun canContinueToUse(): Boolean {
        val level = scarab.level() as? ServerLevel ?: return false
        val hasSomethingToDo = site != null || pillar?.let { stillShort(level, it) } == true
        return hasSomethingToDo && isFitToWork(level) && travelling < GIVES_UP_AFTER
    }

    override fun start() {
        travelling = 0
        scrabbling = 0
    }

    override fun stop() {
        pillar = null
        site = null
        sand = null
        scrabbling = 0
        scarab.navigation.stop()
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun tick() {
        val level = scarab.level() as? ServerLevel ?: return
        travelling++
        site?.let { return begin(level, it) }
        val foot = pillar ?: return
        val plan = level.getBlockEntity(foot) as? ScarabPillarBlockEntity ?: return stop()
        if (scarab.carried == null) fetch(level, foot) else lay(level, plan)
    }

    /** Adult, fed, and allowed to move blocks. */
    private fun isFitToWork(level: ServerLevel): Boolean =
        !scarab.isBaby && !scarab.isHungry && scarab.mayDisturbTheWorld(level)

    /** Where a scarab looks for work from: its nest, where it has one, so a housed scarab works its own colony. */
    private fun workingFrom(): BlockPos = scarab.home ?: scarab.blockPosition()

    private fun stillShort(level: ServerLevel, foot: BlockPos): Boolean =
        (level.getBlockEntity(foot) as? ScarabPillarBlockEntity)?.isFinished(level) == false

    /** The nearest pillar in reach still short of its height. */
    private fun unfinishedPillarNear(level: ServerLevel, from: BlockPos): BlockPos? =
        ScarabHabitat.pillarsNear(level, from, WORK_REACH).firstOrNull { stillShort(level, it) }

    /** Free ground in reach to begin a pillar on, sampled at random so the search is a fixed cost. */
    private fun siteNear(level: ServerLevel, from: BlockPos): BlockPos? {
        val random = scarab.random
        repeat(SITE_SAMPLES) {
            val x = from.x + random.nextIntBetweenInclusive(-WORK_REACH, WORK_REACH)
            val z = from.z + random.nextIntBetweenInclusive(-WORK_REACH, WORK_REACH)
            ScarabHabitat.freeSiteAt(level, x, z, from.y + 1)?.let { return it }
        }
        return null
    }

    /** Flies to [mud] and makes it the foot of a new pillar, if it is still free ground on arrival. */
    private fun begin(level: ServerLevel, mud: BlockPos) {
        val over = Vec3.atBottomCenterOf(mud.above())
        scarab.headFor(over)
        if (!scarab.isNear(over, ARRIVES_WITHIN)) return
        site = null
        travelling = 0
        // Asked again on arrival: another scarab may have begun one beside it on the way.
        if (ScarabHabitat.freeSiteAt(level, mud.x, mud.z, mud.y + 1) != mud) return
        level.setBlockAndUpdate(mud, AgeContent.SCARAB_PILLAR_BLOCK.defaultBlockState())
        (level.getBlockEntity(mud) as? ScarabPillarBlockEntity)?.plan(scarab.random)
        level.playSound(null, mud, SoundEvents.MUD_PLACE, SoundSource.NEUTRAL, VOLUME, PITCH)
        pillar = mud
    }

    private fun fetch(level: ServerLevel, foot: BlockPos) {
        val at = sand ?: sandToCarry(level, foot)?.also { sand = it } ?: return run {
            pillar = null
            restUntil = level.gameTime + LOOK_AGAIN_AFTER
        }
        val state = level.getBlockState(at)
        if (!isSandToCarry(level, at, state, foot.y + 1)) {
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

    private fun lay(level: ServerLevel, plan: ScarabPillarBlockEntity) {
        val course = plan.topOf(level)
        // Something has been put where the pillar was to go: the load is spent, and the pillar is as tall
        // as it is going to get.
        if (!level.getBlockState(course).canBeReplaced()) {
            scarab.carry(null)
            pillar = null
            return
        }
        // Over the course rather than in it, so the mud is never laid on the scarab laying it.
        val over = Vec3.atBottomCenterOf(course.above())
        scarab.headFor(over)
        if (!scarab.isNear(over, WITHIN_REACH)) return
        val laid = plan.courseAt(course.y - plan.blockPos.y)
        level.setBlockAndUpdate(course, laid)
        val sound = if (laid.`is`(AgeContent.SCARAB_NEST_BLOCK)) SoundEvents.PACKED_MUD_PLACE else SoundEvents.MUD_PLACE
        level.playSound(null, course, sound, SoundSource.NEUTRAL, VOLUME, PITCH)
        scarab.carry(null)
        travelling = 0
        val rest = if (scarab.isHoused) LEISURELY_PAUSE else EAGER_PAUSE
        restUntil = level.gameTime + rest
        // A housed scarab lays one load and goes back to its day; a homeless one stays at it.
        if (scarab.isHoused) pillar = null
    }

    /** A column of loose sand within reach of the pillar, or null where none of the sampled columns has one. */
    private fun sandToCarry(level: ServerLevel, foot: BlockPos): BlockPos? {
        val random = scarab.random
        val reach = ScarabHabitat.SAND_REACH
        repeat(SAND_SAMPLES) {
            val x = foot.x + random.nextIntBetweenInclusive(-reach, reach)
            val z = foot.z + random.nextIntBetweenInclusive(-reach, reach)
            val top = ScarabHabitat.groundNear(level, x, z, foot.y + 1) ?: return@repeat
            if (isSandToCarry(level, top, level.getBlockState(top), foot.y + 1)) return top
        }
        return null
    }

    /** Sand or red sand that is the ground a scarab sees there. Not suspicious sand, which holds an archaeologist's find. */
    private fun isSandToCarry(level: ServerLevel, at: BlockPos, state: BlockState, nearY: Int): Boolean {
        val isOnTop = ScarabHabitat.groundNear(level, at.x, at.z, nearY) == at
        return state.`is`(BlockTags.SAND) && !state.hasBlockEntity() && isOnTop
    }

    private companion object {
        /** How far from where it works a scarab looks for a pillar, or ground to begin one on. */
        const val WORK_REACH = 16
        const val SITE_SAMPLES = 128
        const val SAND_SAMPLES = 64

        const val WITHIN_REACH = 0.8
        const val ARRIVES_WITHIN = 1.5
        const val GIVES_UP_AFTER = 1200
        const val LOOK_AGAIN_AFTER = 200L

        /** Five seconds between a homeless scarab's loads, so a pillar rises over a morning and not at once. */
        const val EAGER_PAUSE = 100L

        /** A housed scarab's minute between loads, and about one chance in this many a tick that it starts. */
        const val LEISURELY_PAUSE = 1200L
        const val LEISURE_ODDS = 600

        /** A second and a half at the patch before it rises with its load. */
        const val SCRABBLES_FOR = 30
        const val KICKS_EVERY = 4
        const val GRAINS = 4
        const val GRAIN_SPREAD = 0.2
        const val GRAIN_SPEED = 0.15
        const val SCUFF_VOLUME = 0.5f

        const val VOLUME = 0.7f
        const val PITCH = 1.1f
    }
}
