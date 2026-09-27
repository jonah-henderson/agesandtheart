package co.voik.agesandtheart.page

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.NotebookItem
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction
import net.minecraft.core.Holder
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.minecraft.world.level.storage.loot.providers.number.ints.ConstantValue
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders
import net.minecraft.world.level.storage.loot.providers.number.ints.UniformGenerator
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier
import java.util.Optional
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.CannotAppearInLoot
import co.voik.agesandtheart.age.word.Withheld
import co.voik.agesandtheart.age.word.WordRarity
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.age.word.WriterStock
import co.voik.agesandtheart.content.PageItem

/**
 * Fills a notebook with pages someone else already collected.
 *
 * ```json
 * { "function": "agesandtheart:fill_notebook", "pages": { "type": "minecraft:uniform", "min": 9, "max": 12 },
 *   "derived_only": true }
 * { "function": "agesandtheart:fill_notebook", "pages": { "type": "minecraft:uniform", "min": 4, "max": 5 },
 *   "pool": "agesandtheart:writer_stock/apprentice" }
 * ```
 *
 * `derived_only` is what makes a found notebook feel like a naturalist's field record rather than a
 * shortcut: block and biome words are the ones worth cataloguing in bulk, and the authored vocabulary
 * stays something you find one page at a time.
 *
 * `pool` names a [WriterStock] pool instead, for a notebook somebody assembled to sell rather than kept.
 * It **replaces** `derived_only` rather than narrowing it: a pool is already a decision about what
 * belongs, so asking a second question of it could only contradict the first. `rarity` narrows the
 * corpus to the buckets named, as it does for a loose page.
 */
class FillNotebookFunction(
    predicate: Optional<Holder<LootItemCondition>>,
    val pages: Holder<ContextIntProvider>,
    val derivedOnly: Boolean,
    val pool: Identifier?,
    /** The rarity buckets a page may come from, or null for any. Ignored with a [pool]. */
    val rarity: Set<String>?,
) : LootItemConditionalFunction(predicate) {

    override fun codec(): MapCodec<out LootItemConditionalFunction> = MAP_CODEC

    override fun run(itemStack: ItemStack, context: LootContext): ItemStack {
        val vocabulary = Vocabulary.of(context.level.server)
        val registries = context.level.registryAccess()
        val available = sourceWords(vocabulary, registries)
            .filterNot { Withheld.holdsBack(it, registries) }
        if (available.isEmpty()) {
            Constants.LOG.warn(
                "No words to fill a notebook with (derived_only={}, pool={})", derivedOnly, pool,
            )
            return itemStack
        }
        // Distinct: a notebook someone kept would not hold the same word twice, and the draw is with
        // replacement. Asking for more pages than the corpus has simply yields fewer.
        val wanted = pages.value().getInt(context).coerceAtMost(available.size)
        val chosen = LinkedHashSet<ItemStack>()
        var attempts = 0
        while (chosen.size < wanted && attempts < wanted * ATTEMPT_HEADROOM) {
            attempts++
            val word = available[context.random.nextInt(available.size)]
            val page = PageItem.writtenWith(word.id)
            if (chosen.none { it.get(AgeComponents.PAGE_WORD) == word.id }) chosen += page
        }
        // Through `NotebookItem`, which is what everything else reads a notebook by. Set as vanilla's
        // CONTAINER instead, the pages were there and nothing could see them — the desk, the tooltip and
        // the notebook's own screen all ask for `NOTEBOOK_PAGES`, so a found notebook opened empty.
        NotebookItem.setPages(itemStack, chosen.toList())
        return itemStack
    }

    private fun sourceWords(vocabulary: Vocabulary, registries: RegistryAccess): List<Word> =
        if (pool != null) {
            vocabulary.stock.words(pool, vocabulary, registries)
        } else {
            val corpus = if (derivedOnly) vocabulary.derivedWords else vocabulary.words
            corpus.filter { word -> isInAskedRarity(word, vocabulary) }
                .filterNot { CannotAppearInLoot.keepsOut(it, vocabulary, registries) }
        }

    private fun isInAskedRarity(word: Word, vocabulary: Vocabulary): Boolean =
        rarity == null || vocabulary.rarity.bucketOf(word)?.name in rarity

    companion object {
        /** How many draws to allow past the target before settling for a shorter notebook. */
        private const val ATTEMPT_HEADROOM = 4

        private val DEFAULT_PAGES: Holder<ContextIntProvider> = Holder.direct(
            UniformGenerator(Holder.direct(ConstantValue(FEWEST_PAGES)), Holder.direct(ConstantValue(MOST_PAGES))),
        )

        /** How many pages a found notebook holds — nine to twelve, as it always has. */
        private const val FEWEST_PAGES = 9
        private const val MOST_PAGES = 12

        val MAP_CODEC: MapCodec<FillNotebookFunction> = RecordCodecBuilder.mapCodec { instance ->
            commonFields(instance)
                .and(
                    ContextIntProviders.CODEC.optionalFieldOf("pages", DEFAULT_PAGES)
                        .forGetter(FillNotebookFunction::pages),
                )
                .and(
                    Codec.BOOL.optionalFieldOf("derived_only", true)
                        .forGetter(FillNotebookFunction::derivedOnly),
                )
                .and(
                    Identifier.CODEC.optionalFieldOf("pool")
                        .forGetter { Optional.ofNullable(it.pool) },
                )
                .and(
                    WordRarity.BUCKET_NAMES_CODEC.optionalFieldOf("rarity")
                        .forGetter { Optional.ofNullable(it.rarity) },
                )
                .apply(instance) { predicate, pages, derivedOnly, pool, rarity ->
                    FillNotebookFunction(predicate, pages, derivedOnly, pool.orElse(null), rarity.orElse(null))
                }
        }
    }
}
