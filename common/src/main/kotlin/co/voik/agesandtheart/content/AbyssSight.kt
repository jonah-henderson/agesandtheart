package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level

/**
 * The surface of an abyss, which no creature but the hadalfish sees across.
 *
 * A player cannot see across it either way, since deep water's fog closes in at once, so a drowned above it
 * throwing tridents at a diver below is a creature seeing what the player cannot. Only the two eyes are read: one in
 * deep water and the other not is a sightline across the surface.
 */
object AbyssSight {

    @JvmStatic
    fun isHiddenAcrossTheAbyss(looker: LivingEntity, target: Entity, targetEyeY: Double): Boolean {
        if (looker is Hadalfish) return false
        val level = looker.level()
        val lookerIsInTheAbyss = isInDeepWater(level, looker.x, looker.eyeY, looker.z)
        val targetIsInTheAbyss = isInDeepWater(level, target.x, targetEyeY, target.z)
        return lookerIsInTheAbyss != targetIsInTheAbyss
    }

    private fun isInDeepWater(level: Level, x: Double, y: Double, z: Double): Boolean =
        level.getFluidState(BlockPos.containing(x, y, z)).`is`(DeepWater.DEEP_WATER)
}
