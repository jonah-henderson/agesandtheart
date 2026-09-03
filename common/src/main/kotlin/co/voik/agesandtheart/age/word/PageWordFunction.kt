package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.content.AgeContent
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import java.util.Optional

/**
 * Writes a word on a page as it is generated.
 *
 * A loot **function** rather than anything bespoke, so pages are ordinary loot: any pack can put them in
 * any table it authors with plain JSON, and ours is not a special case. A villager trade is loot too —
 * `given_item_modifiers` takes the same functions — which is how a writer sells a page without a line of
 * trading code.
 *
 * `pool` names a [WriterStock] pool by its tag id, and the draw is **uniform** within it: a curated pool
 * is already a statement about what should turn up, and weighting it again would say the same thing
 * twice. Without one the whole corpus is drawn by rarity, which is what a found page does.
 *
 * ```json
 * { "function": "agesandtheart:roll_page_word" }
 * { "function": "agesandtheart:roll_page_word", "pool": "agesandtheart:writer_stock/master" }
 * ```
 */
class PageWordFunction(
    predicates: List<LootItemCondition>,
    val pool: Identifier?,
) : LootItemConditionalFunction(predicates) {

    override fun codec(): MapCodec<out LootItemConditionalFunction> = MAP_CODEC

    override fun run(itemStack: ItemStack, context: LootContext): ItemStack {
        val vocabulary = Vocabulary.of(context.level.server)
        val registries = context.level.registryAccess()
        val word = if (pool == null) {
            vocabulary.rarity.draw(vocabulary, context.random) { !Withheld.holdsBack(it, registries) }
        } else {
            drawFromStock(vocabulary, registries, context)
        }
        if (word == null) {
            Constants.LOG.warn("No word to write on a page (pool={}); is art/rarity empty?", pool)
            return itemStack
        }
        itemStack.set(AgeContent.PAGE_WORD, word.id)
        return itemStack
    }

    private fun drawFromStock(
        vocabulary: Vocabulary,
        registries: RegistryAccess,
        context: LootContext,
    ): Word? {
        val stocked = vocabulary.stock.words(requireNotNull(pool), vocabulary, registries)
            .filterNot { Withheld.holdsBack(it, registries) }
        if (stocked.isEmpty()) return null
        return stocked[context.random.nextInt(stocked.size)]
    }

    companion object {
        /** The registry holds the codec itself in 26.1 — there is no function-type wrapper any more. */
        val MAP_CODEC: MapCodec<PageWordFunction> = RecordCodecBuilder.mapCodec { instance ->
            commonFields(instance)
                .and(
                    Identifier.CODEC.optionalFieldOf("pool")
                        .forGetter { Optional.ofNullable(it.pool) },
                )
                .apply(instance) { predicates, pool -> PageWordFunction(predicates, pool.orElse(null)) }
        }
    }
}
