package co.voik.agesandtheart.page

import co.voik.agesandtheart.content.PageItem
import co.voik.agesandtheart.Constants
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction
import net.minecraft.core.Holder
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import java.util.Optional
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.CannotAppearInLoot
import co.voik.agesandtheart.age.word.Withheld
import co.voik.agesandtheart.age.word.WordRarity
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.age.word.WriterStock

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
 * twice. Without one the whole corpus is drawn by rarity, which is what a found page does, and `rarity`
 * narrows that roll to the buckets named — a secret room's rare pages.
 *
 * ```json
 * { "function": "agesandtheart:roll_page_word" }
 * { "function": "agesandtheart:roll_page_word", "rarity": ["uncommon", "rare"] }
 * { "function": "agesandtheart:roll_page_word", "pool": "agesandtheart:writer_stock/master" }
 * ```
 */
class PageWordFunction(
    predicate: Optional<Holder<LootItemCondition>>,
    val pool: Identifier?,
    /** The rarity buckets to roll among, or null for all of them. Ignored with a [pool]. */
    val rarity: Set<String>?,
) : LootItemConditionalFunction(predicate) {

    override fun codec(): MapCodec<out LootItemConditionalFunction> = MAP_CODEC

    override fun run(itemStack: ItemStack, context: LootContext): ItemStack {
        val vocabulary = Vocabulary.of(context.level.server)
        val registries = context.level.registryAccess()
        val word = if (pool == null) {
            drawFoundWord(vocabulary, registries, context.random, rarity)
        } else {
            drawFromStock(vocabulary, registries, context)
        }
        if (word == null) {
            Constants.LOG.warn("No word to write on a page (pool={}); is art/rarity empty?", pool)
            return itemStack
        }
        PageItem.write(itemStack, word, vocabulary)
        return itemStack
    }

    private fun drawFromStock(
        vocabulary: Vocabulary,
        registries: RegistryAccess,
        context: LootContext,
    ): Word? = vocabulary.stock.draw(requireNotNull(pool), vocabulary, registries, context.random)

    companion object {
        /** A word a found page may carry, drawn by rarity from [rarity]'s buckets, or all of them when null. */
        fun drawFoundWord(vocabulary: Vocabulary, registries: RegistryAccess, random: RandomSource, rarity: Set<String>?): Word? =
            vocabulary.rarity.draw(vocabulary, random, rarity) { word ->
                !Withheld.holdsBack(word, registries) && !CannotAppearInLoot.keepsOut(word, vocabulary, registries)
            }

        /** The registry holds the codec itself in 26.1 — there is no function-type wrapper any more. */
        val MAP_CODEC: MapCodec<PageWordFunction> = RecordCodecBuilder.mapCodec { instance ->
            commonFields(instance)
                .and(
                    Identifier.CODEC.optionalFieldOf("pool")
                        .forGetter { Optional.ofNullable(it.pool) },
                )
                .and(
                    WordRarity.BUCKET_NAMES_CODEC.optionalFieldOf("rarity")
                        .forGetter { Optional.ofNullable(it.rarity) },
                )
                .apply(instance) { predicate, pool, rarity ->
                    PageWordFunction(predicate, pool.orElse(null), rarity.orElse(null))
                }
        }
    }
}
