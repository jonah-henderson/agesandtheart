package co.voik.agesandtheart.content

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.entity.ai.attributes.AttributeInstance
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.equipment.ArmorMaterials
import net.minecraft.world.item.equipment.ArmorType
import kotlin.math.abs

/**
 * That the suit is what it says it is: it stops you burning, it outlasts diamond, and it protects worse
 * than iron.
 *
 * **The arithmetic is the point, and it is invisible in play.** A burning-time modifier that came to -0.9
 * over four pieces would leave a wearer catching fire for a tenth as long, which looks exactly like working
 * until an inferno kills somebody; one that came to -1.1 would be a negative duration nobody has thought
 * about. Only a number can tell those apart from the thing that is wanted.
 *
 * **Read against vanilla's own materials rather than against numbers written here twice**, so that a
 * vanilla rebalance of iron or diamond fails this rather than silently moving what the suit is relative to.
 */
@Tags(NEEDS_REGISTRIES)
class ProtectiveSuitCheck : FunSpec({

    val worn = listOf(ArmorType.HELMET, ArmorType.CHESTPLATE, ArmorType.LEGGINGS, ArmorType.BOOTS)

    test("every piece takes a quarter off the time you burn for") {
        for (type in worn) {
            val burning = burningModifiersOf(type)
            check(burning.size == 1) { "${type.getName()} carries ${burning.size} burning-time modifiers" }
            val modifier = burning.single()
            check(modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                "${type.getName()} scales the burn by ${modifier.operation()}, which does not sum across pieces"
            }
            check(modifier.amount() == -A_QUARTER) {
                "${type.getName()} takes ${modifier.amount()} off the burn rather than $-A_QUARTER"
            }
        }
    }

    /**
     * The whole suit comes to exactly nothing — not nearly nothing, and not past it.
     *
     * **Worked out by vanilla's own attribute, not by adding the amounts up.** `ADD_MULTIPLIED_TOTAL`
     * multiplies per modifier, so four quarters off it left a third of the burn, and a check that summed the
     * amounts passed while a whole suit still caught fire in lava (walked 2026-10-04).
     */
    test("the four pieces together put the burning time at nought") {
        val burning = AttributeInstance(Attributes.BURNING_TIME) {}
        for (type in worn) burningModifiersOf(type).forEach(burning::addTransientModifier)
        check(abs(burning.value) < A_ROUNDING) { "a whole suit still burns for ${burning.value} of the time" }
    }

    /** A piece is still worth wearing on its own, which is what a per-piece modifier buys. */
    test("a lone piece is worth a quarter of the protection rather than none") {
        val boots = burningModifiersOf(ArmorType.BOOTS).sumOf { it.amount() }
        check(boots < 0.0) { "a boot alone does nothing about burning" }
        check(boots > -WHOLE) { "a boot alone is the whole suit, so the other three are free" }
    }

    test("it outlasts diamond and protects worse than iron") {
        val ours = ProtectiveSuit.MATERIAL
        check(ours.durability() > ArmorMaterials.DIAMOND.durability()) {
            "the suit lasts ${ours.durability()} against diamond's ${ArmorMaterials.DIAMOND.durability()}"
        }
        check(ours.durability() < ArmorMaterials.NETHERITE.durability()) {
            "the suit outlasts netherite, which makes it the best armour in the game"
        }
        val mine = worn.sumOf { ours.defense()[it] ?: 0 }
        val iron = worn.sumOf { ArmorMaterials.IRON.defense()[it] ?: 0 }
        check(mine < iron) { "the suit protects $mine against iron's $iron" }
        check(mine > iron - MUCH_WORSE) { "the suit protects $mine against iron's $iron, which is not 'slightly'" }
    }

    /** Nothing that would make it good in a fight, which is the trade the whole design rests on. */
    test("it is no use in a fight beyond its plates") {
        check(ProtectiveSuit.MATERIAL.toughness() == 0.0f) { "the suit has armour toughness" }
        check(ProtectiveSuit.MATERIAL.knockbackResistance() == 0.0f) { "the suit resists knockback" }
    }

    test("it keeps the ordinary armour modifiers it would have had") {
        for (type in worn) {
            val armour = ProtectiveSuit.attributesFor(type).modifiers()
                .filter { it.attribute() == Attributes.ARMOR }
            check(armour.isNotEmpty()) { "${type.getName()} lost its armour value to the burning modifier" }
        }
    }
}) {
    companion object {
        private const val A_QUARTER = 0.25
        private const val WHOLE = 1.0
        private const val A_ROUNDING = 1e-9

        /** More than this below iron and "slightly less protection" is not what it is. */
        private const val MUCH_WORSE = 5

        init {
            MinecraftRegistries.ensureStoodUp()
        }

        private fun burningModifiersOf(type: ArmorType): List<AttributeModifier> =
            ProtectiveSuit.attributesFor(type).modifiers()
                .filter { it.attribute() == Attributes.BURNING_TIME }
                .map { it.modifier() }
    }
}
