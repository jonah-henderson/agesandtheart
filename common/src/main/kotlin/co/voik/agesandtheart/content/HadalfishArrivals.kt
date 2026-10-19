package co.voik.agesandtheart.content

import co.voik.agesandtheart.platform.Services
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.WorldGenLevel

/**
 * The hadalfish a vent keeps, one vent in four, made with it.
 *
 * A stray anywhere else in the abyss is not this: it is the abyss biome's own natural spawn, so it arrives as
 * vanilla's monsters do and a book's words about creatures reach it. Its rarity is [Hadalfish.spawnsHere]'s, and
 * the abyss's spawn cost keeps it away from a vent's fish.
 */
object HadalfishArrivals {

    /**
     * A hadalfish for one vent in [VENTS_PER_FISH], in deep water over its mouth, kept there: it is persistent and
     * its home is the vent. Called by `DeepSeaVent` once the vent is built, so the draw moves nothing about its shape.
     */
    fun keepOneByTheVent(level: WorldGenLevel, random: RandomSource, mouth: BlockPos) {
        if (random.nextInt(VENTS_PER_FISH) != 0) return
        val at = deepWaterAround(level, random, mouth, KEPT_WITHIN, aboveBy = OVER_THE_MOUTH) ?: return
        val fish = AgeContent.HADALFISH.create(level.level, EntitySpawnReason.STRUCTURE) ?: return
        fish.snapTo(at.x + HALF, at.y.toDouble(), at.z + HALF, random.nextFloat() * FULL_TURN, 0.0f)
        Services.MOB_SPAWNING.finish(level, fish, level.getCurrentDifficultyAt(at), EntitySpawnReason.STRUCTURE)
        fish.setPersistenceRequired()
        fish.setHomeTo(mouth, HOME_RADIUS)
        level.addFreshEntity(fish)
    }

    private fun deepWaterAround(level: WorldGenLevel, random: RandomSource, from: BlockPos, within: Int, aboveBy: Int): BlockPos? {
        repeat(PLACEMENT_TRIES) {
            val at = from.offset(
                random.nextIntBetweenInclusive(-within, within),
                random.nextIntBetweenInclusive(1, aboveBy),
                random.nextIntBetweenInclusive(-within, within),
            )
            if (level.ensureCanWrite(at) && isDeepAndOpen(level, at)) return at
        }
        return null
    }

    /** Deep water here and over it, so a two-block fish is not put half into the rock or the shallows. */
    private fun isDeepAndOpen(level: ServerLevelAccessor, at: BlockPos): Boolean =
        level.getFluidState(at).`is`(DeepWater.DEEP_WATER) && level.getFluidState(at.above()).`is`(DeepWater.DEEP_WATER)

    /** One vent in four. */
    private const val VENTS_PER_FISH = 4

    /** A vent's fish starts within this of the vent's mouth, and over it. */
    private const val KEPT_WITHIN = 6
    private const val OVER_THE_MOUTH = 8

    /** And keeps to within this of the mouth while it has nothing to hunt. */
    private const val HOME_RADIUS = 24

    private const val PLACEMENT_TRIES = 12
    private const val HALF = 0.5
    private const val FULL_TURN = 360.0f
}
