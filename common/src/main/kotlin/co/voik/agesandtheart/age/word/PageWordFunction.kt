package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.content.AgeContent
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition

/**
 * Writes a word on a page as it is generated.
 *
 * A loot **function** rather than anything bespoke, so pages are ordinary loot: any pack can put them in
 * any table it authors with plain JSON, and ours is not a special case.
 *
 * ```json
 * { "function": "agesandtheart:roll_page_word" }
 * ```
 */
class PageWordFunction(predicates: List<LootItemCondition>) : LootItemConditionalFunction(predicates) {

    override fun codec(): MapCodec<out LootItemConditionalFunction> = MAP_CODEC

    override fun run(itemStack: ItemStack, context: LootContext): ItemStack {
        val vocabulary = Vocabulary.of(context.level.server)
        val registries = context.level.registryAccess()
        val word = vocabulary.rarity.draw(vocabulary, context.random) {
            !PageExclusion.isExcluded(it, registries)
        }
        if (word == null) {
            Constants.LOG.warn("No word to write on a page; is art/rarity empty?")
            return itemStack
        }
        itemStack.set(AgeContent.PAGE_WORD, word.id)
        return itemStack
    }

    companion object {
        /** The registry holds the codec itself in 26.1 — there is no function-type wrapper any more. */
        val MAP_CODEC: MapCodec<PageWordFunction> = RecordCodecBuilder.mapCodec { instance ->
            commonFields(instance).apply(instance, ::PageWordFunction)
        }
    }
}
