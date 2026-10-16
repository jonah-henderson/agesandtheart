package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.location
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes

/**
 * What a book wrote of how its creatures move and how strong they are, member by member — `slow monsters`,
 * `strong spawns`. Laid on as each creature enters the Age, as [StillCreatures] roots them, and transient, so a
 * creature carried out of the Age is its own again.
 */
object WrittenCreatures {

    fun loaded(entity: Entity) {
        val mob = entity as? Mob ?: return
        val level = mob.level() as? ServerLevel ?: return
        val recipe = Ages.recipeOf(level) ?: return
        val spawns = recipe.composition?.optionsFor(Aspect.SPAWNS) ?: return
        val kind = BuiltInRegistries.ENTITY_TYPE.getKey(mob.type).toString()
        spawns.steer(Spawns.PACE, recipe.seed, member = kind)?.let { pace ->
            val change = pace * if (pace < 0.0) SLOWED_IN_FULL else QUICKENED_IN_FULL
            for (attribute in MOVING) changeBy(mob, attribute, PACED, change)
        }
        spawns.steer(Spawns.STRENGTH, recipe.seed, member = kind)?.let { strength ->
            val change = strength * if (strength < 0.0) WEAKENED_IN_FULL else STRENGTHENED_IN_FULL
            val wasWhole = mob.health >= mob.maxHealth
            for (attribute in STRONG) changeBy(mob, attribute, STRENGTHENED, change)
            mob.health = if (wasWhole) mob.maxHealth else mob.health.coerceAtMost(mob.maxHealth)
        }
    }

    /** [mob]'s [attribute] multiplied by one and [change], once, however often it enters. */
    private fun changeBy(mob: Mob, attribute: Holder<Attribute>, id: Identifier, change: Double) {
        val instance = mob.getAttribute(attribute) ?: return
        if (instance.hasModifier(id)) return
        instance.addTransientModifier(AttributeModifier(id, change, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
    }

    private val MOVING = listOf(Attributes.MOVEMENT_SPEED, Attributes.FLYING_SPEED)
    private val STRONG = listOf(Attributes.MAX_HEALTH, Attributes.ATTACK_DAMAGE)

    private val PACED = "written_pace".location()
    private val STRENGTHENED = "written_strength".location()

    /** What the bottom of [Spawns.PACE] takes off a creature's speed, and what the top adds. */
    private const val SLOWED_IN_FULL = 0.8
    private const val QUICKENED_IN_FULL = 0.5

    /** What the bottom of [Spawns.STRENGTH] takes off its health and its blows, and what the top adds. */
    private const val WEAKENED_IN_FULL = 0.5
    private const val STRENGTHENED_IN_FULL = 1.0
}
