package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.alchemy.Potion

/**
 * What a potion brewed from a sea pickle gives: the abyss's pressure does nothing, an axe cannot knock the
 * shield out of your hands, and a mace's smash lands as an ordinary blow without throwing you back.
 *
 * The way to the vents short of a whole deretheni suit. It is fire resistance's shape — a potion for a
 * while against what the suit answers for good — and its brewing is datapack JSON (`recipe/brewing/`).
 */
object CrushingResistance {
    val EFFECT_ID: Identifier = "crushing_resistance".location()

    val EFFECT_INSTANCE: MobEffect = CrushingResistanceEffect()

    /** Lazy, so the potions take the registered holder rather than a direct one made before registration. */
    val EFFECT: Holder<MobEffect> by lazy { BuiltInRegistries.MOB_EFFECT.wrapAsHolder(EFFECT_INSTANCE) }

    /** Built when first asked, which each loader does only after the effects are registered. */
    val potions: List<Pair<Identifier, Potion>> by lazy {
        listOf(
            "crushing_resistance".location() to potionLasting(FIRE_RESISTANCES_TICKS),
            "long_crushing_resistance".location() to potionLasting(LONG_FIRE_RESISTANCES_TICKS),
        )
    }

    /** One name for both, as vanilla's long potions share theirs; it is the language file's key. */
    private fun potionLasting(ticks: Int) = Potion(POTION_NAME, MobEffectInstance(EFFECT, ticks))

    @JvmStatic
    fun isResisting(body: Entity): Boolean = body is LivingEntity && body.hasEffect(EFFECT)

    private class CrushingResistanceEffect : MobEffect(MobEffectCategory.BENEFICIAL, COLOUR)

    private const val POTION_NAME = "agesandtheart.crushing_resistance"

    private const val FIRE_RESISTANCES_TICKS = 3_600
    private const val LONG_FIRE_RESISTANCES_TICKS = 9_600

    /** A sea pickle's green under the deep's blue. */
    private const val COLOUR = 0x3E8C7A
}
