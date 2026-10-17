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
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import kotlin.math.pow

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
            for (attribute in MOVING) changeBy(mob, attribute, PACED, paceAt(pace) - AS_IT_IS)
        }
        spawns.steer(Spawns.STRENGTH, recipe.seed, member = kind)?.let { strength ->
            val wasWhole = mob.health >= mob.maxHealth
            for (attribute in STRONG) changeBy(mob, attribute, STRENGTHENED, strengthAt(strength) - AS_IT_IS)
            mob.health = if (wasWhole) mob.maxHealth else mob.health.coerceAtMost(mob.maxHealth)
        }
        spawns.steer(Spawns.SIZE, recipe.seed, member = kind)?.let { size ->
            val wasWhole = mob.health >= mob.maxHealth
            for (attribute in SIZED_BY) changeBy(mob, attribute, SIZED, sizeAt(size) - AS_IT_IS)
            mob.health = if (wasWhole) mob.maxHealth else mob.health.coerceAtMost(mob.maxHealth)
        }
    }

    /** How many times its own size a creature of [size] is, and its health, its blow and its drops with it. */
    fun sizeAt(size: Double): Double = WIDEST_SIZE.pow(size)

    /** How many times its own drops [entity] leaves, where its Age wrote it a size, or null where it did not. */
    fun dropsOf(entity: Entity): Double? {
        val living = entity as? LivingEntity ?: return null
        val modifier = living.getAttribute(Attributes.SCALE)?.getModifier(SIZED) ?: return null
        return AS_IT_IS + modifier.amount()
    }

    /** [drops] at [times] their count, split into stacks no larger than an item allows. */
    fun scaled(drops: List<ItemStack>, times: Double, random: RandomSource): List<ItemStack> = drops.flatMap { stack ->
        stacksFor(stack.count, stack.maxStackSize, times, random.nextDouble()).map(stack::copyWithCount)
    }

    /**
     * [count] at [times] itself, the fraction left over kept where [roll] falls under it, as stacks of at most
     * [largest] — so two fifths of one bone is a bone two times in five.
     */
    fun stacksFor(count: Int, largest: Int, times: Double, roll: Double): List<Int> {
        val asked = count * times
        val whole = asked.toInt() + if (roll < asked - asked.toInt()) 1 else 0
        return generateSequence(whole) { left -> left - largest }.takeWhile { it > 0 }
            .map { left -> left.coerceAtMost(largest) }.toList()
    }

    /** How many times its own speed a creature of [pace] moves at — what the danger score reads too. */
    fun paceAt(pace: Double): Double = AS_IT_IS + pace * if (pace < 0.0) SLOWED_IN_FULL else QUICKENED_IN_FULL

    /** How many times its own health and blows a creature of [strength] has — what the danger score reads too. */
    fun strengthAt(strength: Double): Double =
        AS_IT_IS + strength * if (strength < 0.0) WEAKENED_IN_FULL else STRENGTHENED_IN_FULL

    /** [mob]'s [attribute] multiplied by one and [change], once, however often it enters. */
    private fun changeBy(mob: Mob, attribute: Holder<Attribute>, id: Identifier, change: Double) {
        val instance = mob.getAttribute(attribute) ?: return
        if (instance.hasModifier(id)) return
        instance.addTransientModifier(AttributeModifier(id, change, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
    }

    private val MOVING = listOf(Attributes.MOVEMENT_SPEED, Attributes.FLYING_SPEED)
    private val STRONG = listOf(Attributes.MAX_HEALTH, Attributes.ATTACK_DAMAGE)

    private const val AS_IT_IS = 1.0

    /** What a size scales: the body, its health and its blow, in proportion. Drops are read back off the body's. */
    private val SIZED_BY = listOf(Attributes.SCALE, Attributes.MAX_HEALTH, Attributes.ATTACK_DAMAGE)

    /** The size at either end of [Spawns.SIZE]: two and a half times their own at the top, two fifths at the bottom. */
    private const val WIDEST_SIZE = 2.5

    private val SIZED = "written_size".location()
    private val PACED = "written_pace".location()
    private val STRENGTHENED = "written_strength".location()

    /** What the bottom of [Spawns.PACE] takes off a creature's speed, and what the top adds. */
    private const val SLOWED_IN_FULL = 0.8
    private const val QUICKENED_IN_FULL = 0.5

    /** What the bottom of [Spawns.STRENGTH] takes off its health and its blows, and what the top adds. */
    private const val WEAKENED_IN_FULL = 0.5
    private const val STRENGTHENED_IN_FULL = 1.0
}
