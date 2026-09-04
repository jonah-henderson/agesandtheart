package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * Drawing a word from a writer's stock, which is what a page sold **unwritten** does when it is taken.
 *
 * **The behaviour this exists for is "buy twice, learn twice".** A villager's offer is built once and
 * bought many times, so anything a trade writes into the item is written once — a word rolled while the
 * offer was being made is the word every copy carries, and a writer stops being worth trading with. The
 * item carries the stock instead, and this is the draw that happens per purchase.
 *
 * **What is checked here is the draw and not the writing**, because writing touches our own items and
 * `AgeContent` cannot be built offline: every item makes an intrusive holder in its constructor and
 * `Bootstrap.bootStrap()` freezes the registry that hands those out. `WriterStockCheck` holds the other
 * half — that every trade selling one names a stock that exists.
 */
@Tags(NEEDS_REGISTRIES)
class StockedItemsCheck : FunSpec({

    val vocabulary by lazy { Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen) }

    /** Blocks are built in, which is the master stock's derived half; biomes simply find nothing offline. */
    val registries: RegistryAccess by lazy {
        MinecraftRegistries.ensureStoodUp()
        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
    }

    val master = Identifier.parse("agesandtheart:writer_stock/master")

    fun drawnAt(seed: Long) = vocabulary.stock.draw(master, vocabulary, registries, XoroshiroRandomSource(seed))

    test("a stock draws a word") {
        val word = drawnAt(1L)
        checkNotNull(word) { "the master stock drew nothing at all" }
        val held = vocabulary.stock.words(master, vocabulary, registries).map { it.id }
        check(word.id in held) { "${word.id} was drawn from a stock that does not hold it" }
    }

    /** The whole point: the same trade, bought again, is a different word. */
    test("drawing again is a different word") {
        val words = (1L..60L).mapNotNull { drawnAt(it)?.id }.toSet()
        check(words.size > 1) { "sixty draws from the master stock all came out as $words" }
    }

    /** A stock nobody declared draws nothing, rather than quietly falling back to the whole corpus. */
    test("a stock that does not exist draws nothing") {
        val nowhere = Identifier.parse("agesandtheart:writer_stock/no_such_stock")
        val word = vocabulary.stock.draw(nowhere, vocabulary, registries, XoroshiroRandomSource(1L))
        check(word == null) { "an undeclared stock handed over ${word?.id}" }
    }
})
