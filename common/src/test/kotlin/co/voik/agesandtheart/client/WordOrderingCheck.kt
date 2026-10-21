package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.WordHeadings
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier

/** A word list ordered each way: one unheaded run by name or count, headed runs by rarity and by what words apply to. */
class WordOrderingCheck : FunSpec({

    data class Row(override val word: Identifier, override val readable: String, override val stocked: Int) : OrderedWord

    fun row(name: String, stocked: Int = 0) = Row(Identifier.fromNamespaceAndPath("test", name), name, stocked)
    val charged = row("charged", stocked = 3)
    val frozen = row("frozen", stocked = 9)
    val sky = row("sky", stocked = 1)
    val rows = listOf(sky, frozen, charged)
    val rarity = mapOf(charged.word to WordHeadings.Rarity.STAPLE, frozen.word to WordHeadings.Rarity.RARE)
    val applies = mapOf(charged.word to listOf(sky.word, row("creeper").word), frozen.word to listOf(sky.word))

    fun ordered(order: WordOrder) = WordOrdering.grouped(
        rows,
        order,
        rarityOf = { rarity[it] ?: WordHeadings.Rarity.COMMON },
        rarityName = { it.key },
        appliesTo = { applies[it].orEmpty() },
        nameOf = { it.path },
        naming = "naming",
    ).map { group -> group.heading to group.values.map { it.readable } }

    test("by name and by count, one run with no heading") {
        check(ordered(WordOrder.ALPHABETICAL) == listOf(null to listOf("charged", "frozen", "sky"))) { "${ordered(WordOrder.ALPHABETICAL)}" }
        check(ordered(WordOrder.STOCKED) == listOf(null to listOf("frozen", "charged", "sky"))) { "${ordered(WordOrder.STOCKED)}" }
    }

    test("by rarity, rarest first, with no heading over nothing") {
        val expected = listOf("rare" to listOf("frozen"), "common" to listOf("sky"), "staple" to listOf("charged"))
        check(ordered(WordOrder.RARITY) == expected) { "${ordered(WordOrder.RARITY)}" }
    }

    test("by what each applies to, a word under every one of them and the naming words last") {
        val expected = listOf(
            "creeper" to listOf("charged"),
            "sky" to listOf("charged", "frozen"),
            "naming" to listOf("sky"),
        )
        check(ordered(WordOrder.APPLIES_TO) == expected) { "${ordered(WordOrder.APPLIES_TO)}" }
    }
})
