package co.voik.agesandtheart.advancement

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.desk.DeskTier
import co.voik.agesandtheart.desk.WritersDesk
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier
import java.io.File
import java.util.Optional

/**
 * The advancement tree's files and the conditions of our own triggers.
 *
 * Whether a trigger fires in play is a walk; this holds what can be asked offline — that a condition
 * passes what it names and refuses what it does not, and that every file in the tree has a parent that
 * exists and words a player can read. A missing parent drops the advancement and everything under it
 * without a word in the log.
 */
@Tags(NEEDS_REGISTRIES)
class AdvancementsCheck : FunSpec({

    val tree = File("src/main/resources/data/agesandtheart/advancement/art")
    val lang = File("src/main/resources/assets/agesandtheart/lang/en_us.json")

    fun advancements(): Map<String, JsonObject> =
        tree.listFiles { file -> file.extension == "json" }.orEmpty()
            .associate { it.nameWithoutExtension to JsonParser.parseString(it.readText()).asJsonObject }

    test("every advancement's parent is in the tree, and exactly one has none") {
        val all = advancements()
        check(all.isNotEmpty()) { "No advancements at ${tree.absolutePath}" }
        val parents = all.mapValues { (_, advancement) -> advancement.get("parent")?.asString }
        val roots = parents.filterValues { it == null }.keys
        val orphaned = parents.filterValues { it != null && it.removePrefix("agesandtheart:art/") !in all }
        check(roots.size == 1) { "The tree should have one root, and has $roots" }
        check(orphaned.isEmpty()) { "Parents missing from the tree: $orphaned" }
    }

    test("every advancement's title and description are in the language file") {
        val translated = JsonParser.parseString(lang.readText()).asJsonObject.keySet()
        val untranslated = advancements().values.flatMap { advancement ->
            val display = advancement.getAsJsonObject("display")
            listOf("title", "description").map { display.getAsJsonObject(it).get("translate").asString }
        }.filter { it !in translated }
        check(untranslated.isEmpty()) { "Untranslated: $untranslated" }
    }

    test("a learned word passes the word and the way asked, and refuses others") {
        val nara = Identifier.fromNamespaceAndPath("agesandtheart", "compounded_stone")
        val stone = Identifier.withDefaultNamespace("stone")
        val forNara = LearnedWordTrigger.Instance(Optional.empty(), Optional.of(nara), Optional.empty())
        val bySurvey = LearnedWordTrigger.Instance(Optional.empty(), Optional.empty(), Optional.of(LearnedBy.SURVEY))
        check(forNara.matches(nara, LearnedBy.MASTERY) && !forNara.matches(stone, LearnedBy.MASTERY))
        check(bySurvey.matches(stone, LearnedBy.SURVEY) && !bySurvey.matches(stone, LearnedBy.READING))
    }

    test("stranding yourself counts only in an Age nobody has been to, and only without a linking book") {
        val stranded = LinkedTrigger.Instance(
            Optional.empty(), Optional.of(LinkedInto.AN_AGE), Optional.empty(), Optional.of(false), Optional.of(true),
        )
        fun link(carrying: Boolean, first: Boolean) = Link(LinkedInto.AN_AGE, LinkedWith.DESCRIPTIVE_BOOK, carrying, first)
        check(stranded.matches(link(carrying = false, first = true)))
        check(!stranded.matches(link(carrying = true, first = true)))
        check(!stranded.matches(link(carrying = false, first = false)))
        check(!stranded.matches(Link(LinkedInto.THE_OVERWORLD, LinkedWith.LINKING_BOOK, false, true)))
    }

    test("an arrival passes the material it holds and refuses one it does not") {
        val volcanic = EnteredAgeTrigger.Instance(Optional.empty(), Optional.of(AgeHolding.TEMPERSTONE), Optional.empty())
        val riven = EnteredAgeTrigger.Instance(Optional.empty(), Optional.empty(), Optional.of(true))
        check(volcanic.matches(Arrival(setOf(AgeHolding.TEMPERSTONE, AgeHolding.ASTRITE), collapsing = false)))
        check(!volcanic.matches(Arrival(setOf(AgeHolding.RIME), collapsing = true)))
        check(riven.matches(Arrival(emptySet(), collapsing = true)) && !riven.matches(Arrival(emptySet(), collapsing = false)))
    }

    test("an Age of diamond is found in its rock, its surface or its features, and a struck-out one is not") {
        val diamond = Identifier.withDefaultNamespace("diamond_block")
        val labGrown = WroteAgeTrigger.Instance(Optional.empty(), listOf(diamond), emptyList())
        val plain = AgeComposition(terrains = listOf(Terrain.HILLS))
        val rock = plain.withOptions(Aspect.TERRAIN, Terrain.STONE.name, listOf(diamond.toString()))
        val skin = plain.withOptions(Aspect.SURFACE, Surface.MATERIAL.name, listOf(diamond.toString()))
        fun growing(claim: Claim) = plain.withOptionsFor(Aspect.FEATURES, 0, Features.PLACES.name, listOf(claim.spelled()))
        val obelisks = growing(Claim(OBELISKS, madeOf = diamond.toString()))
        val struckOut = growing(Claim(OBELISKS, polarity = Polarity.EXCEPT, madeOf = diamond.toString()))
        for (composition in listOf(rock, skin, obelisks)) {
            check(labGrown.matches(WrittenAge(emptyList()) { composition })) { "missed a diamond in $composition" }
        }
        check(!labGrown.matches(WrittenAge(emptyList()) { plain })) { "an Age of nothing in particular counted as diamond" }
        check(!labGrown.matches(WrittenAge(emptyList()) { struckOut })) { "diamond obelisks written out counted" }
    }

    test("an Age written with every word named passes, and one missing a word does not") {
        val words = listOf("compounded_stone", "scarab", "paper_tree_log").map { Identifier.fromNamespaceAndPath("agesandtheart", it) }
        val given = WroteAgeTrigger.Instance(Optional.empty(), emptyList(), words)
        check(given.matches(WrittenAge(words) { null }))
        check(!given.matches(WrittenAge(words.drop(1)) { null }))
    }

    test("the desk's rung counts from the bare desk, whatever order the tiers are listed in") {
        val desk = WritersDesk(emptyList(), listOf(DeskTier(6, null), DeskTier(0, 5), DeskTier(3, 20)), radius = 5)
        check(desk.rungFor(0) == 0 && desk.rungFor(2) == 0)
        check(desk.rungFor(3) == 1 && desk.rungFor(5) == 1)
        check(desk.rungFor(6) == 2 && desk.rungFor(9) == 2)
    }
}) {
    companion object {
        private const val OBELISKS = "agesandtheart:obelisks"
    }
}
