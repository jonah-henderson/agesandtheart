package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player

/**
 * Temperstone climbers: any face of the stuff can be climbed (design §7.1.2).
 *
 * They share the boot slot with [RimeSkates], so owning both is a choice between going fast and going up.
 */
object Temperstone {

    /**
     * Whether [entity] may climb the temperstone it is against.
     *
     * Only ever widens the answer — a false result leaves vanilla's own standing, so this can never take a
     * ladder away from someone in the wrong boots.
     */
    @JvmStatic
    fun climbing(entity: LivingEntity): Boolean {
        if (entity !is Player) return false
        if (entity.getItemBySlot(EquipmentSlot.FEET).item !== AgeContent.TEMPERSTONE_CLIMBERS) return false
        return againstIt(entity)
    }

    /**
     * Whether the entity is up against temperstone.
     *
     * Adjacency alone, and deliberately not `horizontalCollision`: that flag is only true while you are
     * pushing into the wall, so letting go of forward would end the climb and drop you — the opposite of
     * [heldOn]'s rule that stopping leaves you where you are.
     *
     * Feet and head are both asked so a wall is not lost halfway up as the feet clear a ledge.
     */
    private fun againstIt(entity: LivingEntity): Boolean {
        val feet = entity.blockPosition()
        return Direction.Plane.HORIZONTAL.any { way ->
            temperstoneAt(entity, feet.relative(way)) || temperstoneAt(entity, feet.above().relative(way))
        }
    }

    private fun temperstoneAt(entity: LivingEntity, at: BlockPos): Boolean =
        entity.level().getBlockState(at).`is`(AgeContent.TEMPERSTONE_BLOCK)

    /**
     * The vertical motion of a climber holding temperstone, replacing a ladder's slow slide.
     *
     * A ladder drops you at 0.15 unless you sneak; this holds you still unless you ask to move. Sneak
     * descends at a ladder's own rate, and rising is left to vanilla, which already lifts anything pressed
     * into a climbable or jumping against one.
     *
     * Returns null where the entity is not climbing temperstone, leaving vanilla's rule alone.
     */
    @JvmStatic
    fun heldOn(entity: LivingEntity, rising: Double): Double? {
        if (!climbing(entity)) return null
        if (entity.isShiftKeyDown) return -LADDER_RATE
        return maxOf(rising, HOLDING)
    }

    /** A ladder's slide, borrowed so going down feels like something players already know. */
    private const val LADDER_RATE = 0.15

    private const val HOLDING = 0.0
}
