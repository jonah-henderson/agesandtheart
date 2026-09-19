package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier

/**
 * The desk's template, offline: how typed text is read into words, which pages a book draws, and what the
 * rest costs. Everything here is pure — the corpus it reads against is made up for the purpose.
 */
class TemplateCheck : FunSpec({

    fun id(path: String) = Identifier.fromNamespaceAndPath("agesandtheart", path)

    val copper = id("copper")
    val copperStairs = id("weathered_copper_stairs")
    val stairs = id("stairs")
    val flat = id("flat")
    val risingEast = id("rising_east")
    val age = id("age")
    val names = TemplateNames.of(listOf(copper, copperStairs, stairs, flat, risingEast, age)) { word ->
        if (word == risingEast) "East Rising" else null
    }
    val learned = setOf(copper, copperStairs, flat, risingEast, age)

    fun read(text: String) = TemplateReading.read(text, names, learned::contains)

    test("the longest name wins over a shorter one it starts with") {
        val words = read("Weathered Copper Stairs flat")
        check(words.map { it.word } == listOf(copperStairs, flat)) { "Read as ${words.map { it.word }}" }
        check(words[0].start == 0 && words[0].end == "Weathered Copper Stairs".length) { "Span ${words[0]}" }
    }

    test("a shorter word still reads alone where no longer name follows") {
        val words = read("copper flat")
        check(words.map { it.word } == listOf(copper, flat)) { "Read as ${words.map { it.word }}" }
    }

    test("case, underscores and runs of spaces do not matter") {
        val words = read("WEATHERED_copper   stairs")
        check(words.single().word == copperStairs) { "Read as $words" }
    }

    test("a translated name is recognised as well as the path") {
        check(read("East Rising").single().word == risingEast)
        check(read("rising east").single().word == risingEast)
    }

    test("the three states: learned, real but unlearned, and not a word") {
        val words = read("flat stairs flta")
        check(words.map { it.state } == listOf(WordState.LEARNED, WordState.UNLEARNED, WordState.UNKNOWN)) {
            "States ${words.map { it.state }}"
        }
        check(!TemplateReading.isWritable(words)) { "An unlearned word and a typo must both block the bind" }
        check(TemplateReading.learnedWords(words) == listOf(flat))
    }

    test("a particle the Art never writes reads as not a word") {
        check(read("the").single().state == WordState.UNKNOWN)
    }

    test("pages are drawn from archives first, then the inventory, and the rest are written") {
        val allocation = PageAllocation.of(
            listOf(flat, flat, flat, copper),
            inArchives = { if (it == flat) 1 else 0 },
            inInventory = { if (it == flat) 1 else 0 },
        )
        check(allocation.fromArchives == mapOf(flat to 1)) { "From archives ${allocation.fromArchives}" }
        check(allocation.fromInventory == mapOf(flat to 1)) { "From inventory ${allocation.fromInventory}" }
        check(allocation.toWrite == listOf(flat, copper)) { "To write ${allocation.toWrite}" }
        check(allocation.drawn == 2)
    }

    test("better paper carries more words to the sheet, rounded up") {
        check(WriteCost.sheetsFor(0, InkTier.COMMON) == 0)
        check(WriteCost.sheetsFor(5, InkTier.COMMON) == 5)
        check(WriteCost.sheetsFor(5, InkTier.FINE) == 2)
        check(WriteCost.sheetsFor(10, InkTier.MASTERWORK) == 1)
        check(WriteCost.sheetsFor(11, InkTier.MASTERWORK) == 2)
    }

    test("a book's ink is grouped by the ink each word demands") {
        val common = PagePrice(InkTier.COMMON, mapOf(InkTier.COMMON to 10L, InkTier.FINE to 8L))
        val fine = PagePrice(InkTier.FINE, mapOf(InkTier.COMMON to 20L, InkTier.FINE to 15L))

        val cost = BookCost.of(listOf(common, fine), InkTier.FINE)
        check(cost.ink == mapOf(InkTier.COMMON to 8L, InkTier.FINE to 15L)) { "Ink ${cost.ink}" }
        check(cost.sheets == 1 && cost.bindings == 1)
    }
})
