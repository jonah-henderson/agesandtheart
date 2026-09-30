package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.phenomena.Sampling
import co.voik.agesandtheart.age.reward.ScarabHabitat
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BiomeTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.phys.AABB

/**
 * Where scarabs come from: homeless ones arriving in a written Age's jungle, near somebody who could see
 * them (design §7.1.2).
 *
 * **Presence is a gradient, not a threshold.** Beyond the jungle they arrive in, three conditions are
 * counted — a warm enough world, torchflowers growing wild, and free ground near where the player is — and
 * arrivals come often where all three are met and now and then where two are. Where one or none is, nothing
 * comes. What arrives is always the same creature; whether it lives there is its own search's to find out
 * ([ScarabSettle]), so a near miss sends a stray that wanders and goes, and a full habitat sends a founder.
 *
 * **Not a biome spawn**, deliberately: a biome's list is the same in every Age that has the biome, and the
 * conditions here are the Age's. And only in an Age a player wrote, as every reward is.
 */
object ScarabArrivals {

    /** Called from `AgeTick`, for an Age somebody is in. */
    fun arrive(level: ServerLevel, recipe: AgeRecipe) {
        if (level.gameTime % CHANCE_EVERY != 0L) return
        val age = ScarabHabitat.readAge(level, recipe) ?: return
        if (!age.writtenByAPlayer || !age.anyJungle) return
        for (player in Sampling.watchers(level)) {
            val chance = chanceOf(conditionsMet(level, player.blockPosition(), age))
            val strayAlreadyAbout = homelessNear(level, player.blockPosition()) >= MOST_HOMELESS
            if (strayAlreadyAbout || level.random.nextFloat() >= chance) continue
            bringOneNear(level, player)
        }
    }

    /**
     * How many of the three conditions beyond the jungle hold here: warmth and wild torchflowers, which are
     * the Age's, and free ground near [around], which is the place's.
     */
    fun conditionsMet(level: ServerLevel, around: BlockPos, age: ScarabHabitat.AgeReading): Int =
        listOf(age.isWarmEnough, age.growsTorchflowersWild, freeGroundNear(level, around)).count { it }

    /**
     * One scarab, somewhere jungle near [player], by day — the arrival itself, with none of the odds. What
     * `/age scarab` calls, so forcing one runs the same placement a natural one does.
     */
    fun bringOneNear(level: ServerLevel, player: ServerPlayer): Scarab? {
        val at = arrivalNear(level, player.blockPosition()) ?: return null
        if (ScarabNestBlockEntity.isTimeToRoost(level, at)) return null
        val scarab = AgeContent.SCARAB.create(level, EntitySpawnReason.NATURAL) ?: return null
        scarab.snapTo(at.x + HALF, at.y.toDouble(), at.z + HALF, level.random.nextFloat() * FULL_TURN, 0.0f)
        scarab.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.NATURAL, null)
        return if (level.addFreshEntity(scarab)) scarab else null
    }

    private fun chanceOf(conditionsMet: Int): Float = when (conditionsMet) {
        ALL_THREE -> WHOLE_HABITAT
        ALL_BUT_ONE -> NEAR_MISS
        else -> 0.0f
    }

    private fun freeGroundNear(level: ServerLevel, around: BlockPos): Boolean {
        val random = level.random
        repeat(GROUND_SAMPLES) {
            val x = around.x + random.nextIntBetweenInclusive(-GROUND_REACH, GROUND_REACH)
            val z = around.z + random.nextIntBetweenInclusive(-GROUND_REACH, GROUND_REACH)
            if (ScarabHabitat.freeSiteAt(level, x, z) != null) return true
        }
        return false
    }

    private fun homelessNear(level: ServerLevel, around: BlockPos): Int =
        level.getEntitiesOfClass(Scarab::class.java, AABB(around).inflate(HOMELESS_COUNTED_WITHIN)) { !it.isHoused }
            .size

    /** Open air over jungle ground a little way off, out of arm's reach and within sight. */
    private fun arrivalNear(level: ServerLevel, around: BlockPos): BlockPos? {
        val random = level.random
        repeat(PLACEMENT_TRIES) {
            val turn = (random.nextFloat() * Mth.TWO_PI).toDouble()
            val distance = random.nextIntBetweenInclusive(NEAREST_ARRIVAL, FARTHEST_ARRIVAL)
            val x = around.x + Mth.floor(Mth.sin(turn) * distance)
            val z = around.z + Mth.floor(Mth.cos(turn) * distance)
            val ground = ScarabHabitat.surfaceOf(level, x, z) ?: return@repeat
            val isDryJungle = level.getBiome(ground).`is`(BiomeTags.IS_JUNGLE) && level.getFluidState(ground).isEmpty
            if (isDryJungle) return ground.above(ABOVE_THE_GROUND)
        }
        return null
    }

    /** Thirty seconds between each player's chances. */
    private const val CHANCE_EVERY = 600L

    private const val ALL_THREE = 3
    private const val ALL_BUT_ONE = 2

    /** Tuned by playtest; a whole habitat should feel inhabited within minutes, a near miss visited. */
    private const val WHOLE_HABITAT = 0.35f
    private const val NEAR_MISS = 0.1f

    /** No more homeless ones about than this, so a near miss is a stray and not a swarm. */
    private const val MOST_HOMELESS = 2
    private const val HOMELESS_COUNTED_WITHIN = 128.0

    /** Dense for the same reason as `ScarabSettle`'s search: a sample that misses mud costs one read. */
    private const val GROUND_REACH = 48
    private const val GROUND_SAMPLES = 256

    private const val PLACEMENT_TRIES = 8
    private const val NEAREST_ARRIVAL = 24
    private const val FARTHEST_ARRIVAL = 48
    private const val ABOVE_THE_GROUND = 3

    private const val HALF = 0.5
    private const val FULL_TURN = 360.0f
}
