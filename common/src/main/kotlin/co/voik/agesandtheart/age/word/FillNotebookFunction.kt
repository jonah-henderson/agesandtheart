package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.NotebookItem
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider
import net.minecraft.world.level.storage.loot.providers.number.NumberProviders
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier
import java.util.Optional

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
 * belongs, so asking a second question of it could only contradict the first.
 */
class FillNotebookFunction(
    predicates: List<LootItemCondition>,
    val pages: NumberProvider,
    val derivedOnly: Boolean,
    val pool: Identifier?,
) : LootItemConditionalFunction(predicates) {

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
        val wanted = pages.getInt(context).coerceAtMost(available.size)
        val chosen = LinkedHashSet<ItemStack>()
        var attempts = 0
        while (chosen.size < wanted && attempts < wanted * ATTEMPT_HEADROOM) {
            attempts++
            val word = available[context.random.nextInt(available.size)]
            val page = ItemStack(AgeContent.PAGE)
            page.set(AgeContent.PAGE_WORD, word.id)
            if (chosen.none { it.get(AgeContent.PAGE_WORD) == word.id }) chosen += page
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
        } else if (derivedOnly) {
            vocabulary.derivedWords
        } else {
            vocabulary.words
        }

    companion object {
        /** How many draws to allow past the target before settling for a shorter notebook. */
        private const val ATTEMPT_HEADROOM = 4

        private val DEFAULT_PAGES: NumberProvider = UniformGenerator.between(9.0f, 12.0f)

        val MAP_CODEC: MapCodec<FillNotebookFunction> = RecordCodecBuilder.mapCodec { instance ->
            commonFields(instance)
                .and(
                    NumberProviders.CODEC.optionalFieldOf("pages", DEFAULT_PAGES)
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
                .apply(instance) { predicates, pages, derivedOnly, pool ->
                    FillNotebookFunction(predicates, pages, derivedOnly, pool.orElse(null))
                }
        }
    }
}
