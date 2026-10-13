package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.location
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes

/**
 * Nothing living in the Age moves from where it stands (Jonah, 2026-10-12) — strange, and every creature at once.
 *
 * **Rooted, not inert**: walking, flying and jumping are taken away and nothing else, so a creature still turns
 * its head to follow you and still strikes or shoots at whatever comes into its reach. Transient, so a creature
 * carried out of the Age walks again.
 */
object StillCreatures {

    /** Roots [entity] where it stands, if it is a creature in an Age whose instability bought it. */
    fun loaded(entity: Entity) {
        val mob = entity as? Mob ?: return
        val level = mob.level() as? ServerLevel ?: return
        val recipe = Ages.recipeOf(level) ?: return
        if (Spending.of(level.server, recipe).bought(Manifestation.STILL_CREATURES) == 0) return
        for (attribute in ROOTED) {
            val instance = mob.getAttribute(attribute) ?: continue
            if (!instance.hasModifier(ROOTED_ID)) instance.addTransientModifier(ROOTING)
        }
    }

    private val ROOTED = listOf(Attributes.MOVEMENT_SPEED, Attributes.FLYING_SPEED, Attributes.JUMP_STRENGTH)

    private val ROOTED_ID = "still_creatures".location()

    /** All of it, whatever the base: `ADD_MULTIPLIED_TOTAL` at minus one leaves nothing. */
    private val ROOTING = AttributeModifier(ROOTED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
}
