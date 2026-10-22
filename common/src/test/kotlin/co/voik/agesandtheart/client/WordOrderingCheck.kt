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
    val gold = row("gold")
    val rows = listOf(sky, frozen, charged)
    val rarity = mapOf(charged.word to WordHeadings.Rarity.UNCOMMON, frozen.word to WordHeadings.Rarity.RARE)
    val applies = mapOf(charged.word to listOf(sky.word, row("creeper").word), frozen.word to listOf(sky.word))

    val names = WordOrdering.Names(
        rarity = { it.key },
        of = { it.path },
        parts = "parts",
        baseWorlds = "base worlds",
        grammar = "grammar",
        namesMembers = { it.path == "creeper" },
        namesAPart = { it == sky.word },
        isGrammar = { it.path == "and" },
        membersOf = { "members of ${it.path}" },
    )

    fun ordered(order: WordOrder, searchedFor: String = "") = WordOrdering.grouped(
        rows,
        order,
        searchedFor,
        rarityOf = { rarity[it] ?: WordHeadings.Rarity.COMMON },
        appliesTo = { applies[it].orEmpty() },
        isAMaterial = { it == gold.word },
        names = names,
    ).map { group -> group.heading to group.values.map { it.readable } }

    test("by name and by count, one run with no heading") {
        check(ordered(WordOrder.ALPHABETICAL) == listOf(null to listOf("charged", "frozen", "sky"))) { "${ordered(WordOrder.ALPHABETICAL)}" }
        check(ordered(WordOrder.STOCKED) == listOf(null to listOf("frozen", "charged", "sky"))) { "${ordered(WordOrder.STOCKED)}" }
    }

    test("by rarity, rarest first, with no heading over nothing") {
        val expected = listOf("rare" to listOf("frozen"), "uncommon" to listOf("charged"), "common" to listOf("sky"))
        check(ordered(WordOrder.RARITY) == expected) { "${ordered(WordOrder.RARITY)}" }
    }

    test("a search finds a heading and keeps all under it, and otherwise keeps the rows it finds by name") {
        check(ordered(WordOrder.APPLIES_TO, "creep") == listOf("creeper" to listOf("charged"))) { "${ordered(WordOrder.APPLIES_TO, "creep")}" }
        check(ordered(WordOrder.RARITY, "Rare") == listOf("rare" to listOf("frozen"))) { "${ordered(WordOrder.RARITY, "Rare")}" }
        check(ordered(WordOrder.APPLIES_TO, "froz") == listOf("sky" to listOf("frozen"))) { "${ordered(WordOrder.APPLIES_TO, "froz")}" }
        check(ordered(WordOrder.ALPHABETICAL, "sk") == listOf(null to listOf("sky"))) { "${ordered(WordOrder.ALPHABETICAL, "sk")}" }
    }

    test("by what each applies to, a word under every one of them, then the parts of the world and what is left") {
        val expected = listOf(
            "creeper" to listOf("charged"),
            "sky" to listOf("charged", "frozen"),
            "parts" to listOf("sky"),
        )
        check(ordered(WordOrder.APPLIES_TO) == expected) { "${ordered(WordOrder.APPLIES_TO)}" }
    }

    test("the words naming members of a part are folded under that part, and what applies to nothing is sorted last") {
        val creeper = row("creeper")
        val void = row("void")
        val and = row("and")
        val grouped = WordOrdering.grouped(
            listOf(creeper, frozen, sky, void, and),
            WordOrder.APPLIES_TO,
            rarityOf = { WordHeadings.Rarity.COMMON },
            appliesTo = { mapOf(creeper.word to listOf(sky.word), frozen.word to listOf(sky.word))[it].orEmpty() },
            names = names,
        )
        val (underSky, parts, baseWorlds, grammar) = grouped
        check(underSky.heading == "sky" && underSky.values.map { it.readable } == listOf("frozen")) { "$underSky" }
        val members = underSky.subgroups.single()
        check(members.heading == "members of sky" && members.values == listOf(creeper)) { "$members" }
        check(parts.heading == "parts" && parts.values == listOf(sky)) { "$parts" }
        check(baseWorlds.heading == "base worlds" && baseWorlds.values == listOf(void)) { "$baseWorlds" }
        check(grammar.heading == "grammar" && grammar.values == listOf(and)) { "$grammar" }
    }

    test("the materials under a heading are folded beneath the rest, and a search finds them there") {
        val withAMaterial = mapOf(gold.word to listOf(sky.word))
        fun grouped(searchedFor: String = "") = WordOrdering.grouped(
            listOf(frozen, gold),
            WordOrder.APPLIES_TO,
            searchedFor,
            rarityOf = { WordHeadings.Rarity.COMMON },
            appliesTo = { (applies + withAMaterial)[it].orEmpty() },
            isAMaterial = { it == gold.word },
            names = names,
        )
        val sky = grouped().single()
        check(sky.heading == "sky" && sky.values.map { it.readable } == listOf("frozen")) { "$sky" }
        val materials = sky.subgroups.single()
        check(materials.heading == "materials" && materials.values.map { it.readable } == listOf("gold")) { "$materials" }
        check(sky.size == 2) { "the heading counts ${sky.size} rows" }
        val found = grouped("gol").single()
        check(found.values.isEmpty() && found.subgroups.single().values.map { it.readable } == listOf("gold")) { "$found" }
    }
})
