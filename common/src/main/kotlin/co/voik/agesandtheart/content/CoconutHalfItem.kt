package co.voik.agesandtheart.content

import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * Half a coconut: a modest meal whose milk takes away the one effect with the longest left to run. An effect
 * with no end counts as the longest of all.
 */
class CoconutHalfItem(properties: Properties) : Item(properties) {

    override fun finishUsingItem(itemStack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        if (!level.isClientSide) {
            entity.activeEffects
                .maxByOrNull { effect -> if (effect.isInfiniteDuration) Int.MAX_VALUE else effect.duration }
                ?.let { longest -> entity.removeEffect(longest.effect) }
        }
        return super.finishUsingItem(itemStack, level, entity)
    }
}
