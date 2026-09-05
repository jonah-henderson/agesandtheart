package co.voik.agesandtheart.content

import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player

/**
 * Boots with a rime blade under them: the ground stops holding you back.
 *
 * **Not merely slippery — faster than walking** (Jonah, 2026-09-05), and the two halves are separate on
 * purpose because friction alone cannot do it. `LivingEntity.getFrictionInfluencedSpeed` divides your
 * acceleration by the cube of the friction, so a slicker surface accelerates you *worse* even as it holds
 * your speed better; blue ice tops out around one and three quarters of a walk however long the straight.
 * So the speed is an attribute on the boots and the slickness is what lets you keep it.
 *
 * **What you buy and what you pay.** They are slow off the mark, quick once running, and they do not stop
 * — which is the whole trade. A corridor is worse than a plain, a ledge is a real hazard, and none of that
 * needed designing in: it is what low friction *is*.
 */
object RimeSkates {

    /**
     * The friction underfoot for [entity], or null where the ground's own answer stands.
     *
     * Called from `LivingEntityMixin`, which is a mixin because neither loader can answer this: NeoForge
     * patches an entity-aware `getFriction` into `BlockBehaviour` and Fabric has nothing at all, so the one
     * seam both sides share is the local in `travelInAir` that the block's answer lands in.
     */
    @JvmStatic
    fun underfoot(entity: LivingEntity): Float? {
        if (entity !is Player) return null
        if (entity.getItemBySlot(EquipmentSlot.FEET).item !== AgeContent.RIME_SKATES) return null
        return LIKE_BLUE_ICE
    }

    /** Blue ice's own, so the answer to "what does this feel like" is a thing players already know. */
    private const val LIKE_BLUE_ICE = 0.989f
}
