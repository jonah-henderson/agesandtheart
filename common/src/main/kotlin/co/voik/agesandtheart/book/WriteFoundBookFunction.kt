package co.voik.agesandtheart.book

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition

/**
 * Writes a found descriptive book, so it arrives already describing somewhere.
 *
 * ```json
 * { "function": "agesandtheart:write_found_book" }
 * ```
 *
 * **This is how the grammar is taught** (design §4.5). `and`, `only`, `except` and the rungs are structure
 * rather than content, so no page loot hands them out and no device derives one — a writer meets them in a
 * book somebody else wrote, and reading it is what teaches them
 * ([co.voik.agesandtheart.page.PageLearning.study], called when the book is opened). A player who has only
 * ever found *pages* has a vocabulary and no sentences.
 *
 * What it writes is the `book` generation grammar (`art/generation/book.json`), which is why a found book
 * reads as something a person would write rather than as a heap of words — and why retuning what turns up
 * is a datapack edit rather than a code one.
 *
 * **Written here rather than left to fill itself.** A blank descriptive book already writes itself on its
 * first tick in an inventory, so seeding the bare item would nearly work; but the book would sit in the
 * chest with no title and no reading until somebody picked it up, and its words would come from whenever
 * that happened rather than from the chest's own generation. A found book should be a *thing already
 * written*, and reading its cover is half of what makes finding one worth anything.
 */
class WriteFoundBookFunction(predicates: List<LootItemCondition>) : LootItemConditionalFunction(predicates) {

    override fun codec(): MapCodec<out LootItemConditionalFunction> = MAP_CODEC

    override fun run(itemStack: ItemStack, context: LootContext): ItemStack {
        // The loot context's own randomness, so a chest generates the same book each time it is rolled
        // from the same seed — the same promise every other found thing here makes.
        FoundBook.write(itemStack, context.level.server, context.random.nextLong())
        return itemStack
    }

    companion object {
        val MAP_CODEC: MapCodec<WriteFoundBookFunction> = RecordCodecBuilder.mapCodec { instance ->
            commonFields(instance).apply(instance, ::WriteFoundBookFunction)
        }
    }
}
