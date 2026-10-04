package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.read
import co.voik.agesandtheart.age.aspect.Aspect
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.locale.Language

/**
 * Whether a book reads as an account of its Age: one sentence per thing said, in plain English, with every
 * id made legible — and with the parse's two rules kept, so nothing the Art laid is said and nothing the
 * writer laid is hidden.
 */
@Tags(NEEDS_REGISTRIES)
class ProseCheck : FunSpec({

    val ours: Map<String, String> by lazy {
        val stream = ProseCheck::class.java.getResourceAsStream("/assets/agesandtheart/lang/en_us.json")
            ?: error("our language file is not on the classpath")
        JsonParser.parseReader(stream.reader()).asJsonObject.entrySet().associate { it.key to it.value.asString }
    }

    /** Our language file over vanilla's, as a client loads them. */
    val english: (String) -> String? = { key ->
        ours[key] ?: Language.getInstance().takeIf { it.has(key) }?.getOrDefault(key)
    }

    fun prose(vararg pages: String): String =
        ProseWriting.sentencesOf(Prose.of(read(pages.toList())), english).joinToString(" ")

    /** The book the readout was redesigned around, and the shape every other book follows. */
    test("a book reads as several sentences, each about one part of the world") {
        val said = prose(
            "beautiful", "ancient", "age", "teeming", "jungle_temples", "structures",
            "only", "zombie", "spawns", "in", "dark_forest",
        )
        val expected = "A beautiful, ancient Age. It features teeming jungle temples. " +
            "Only zombies dwell in its dark forests."
        check(said == expected) { "read as '$said'" }
    }

    test("a material is said as what a part of the world is made of") {
        val said = prose("age", "floating", "basalt", "landmass", "molten", "lava", "sea")
        check(said == "An Age. Its land is floating and made of basalt. Its sea is molten and made of lava.") {
            "read as '$said'"
        }
    }

    test("an Age with nothing said of it is still an Age") {
        check(prose("age") == "An Age.") { "a bare book read as '${prose("age")}'" }
    }

    test("what is laid on the Age itself stands in front of it, and what it is full of follows") {
        check(prose("frozen", "arid", "age") == "A frozen, arid Age.") { "read as '${prose("frozen", "arid", "age")}'" }
        check(prose("basalt", "zombie", "age") == "An Age of basalt and zombies.") {
            "read as '${prose("basalt", "zombie", "age")}'"
        }
    }

    /** Two suns are two bodies, so the second is never said as though it were the first again. */
    test("a population's members are told apart") {
        val said = prose("age", "large", "red", "sun", "small", "blue", "sun")
        check(said == "An Age. Its first sun is large and red. Its second sun is small and blue.") { "read as '$said'" }
    }

    test("only and except are said once over the run they govern") {
        val only = prose("age", "only", "wolf", "and", "witch", "spawns")
        check(only == "An Age. Only wolves and witches dwell in it.") { "read as '$only'" }

        val except = prose("age", "except", "villages", "structures")
        check(except == "An Age. It features no villages.") { "read as '$except'" }
    }

    test("a quality and a population in one clause are two sentences") {
        val said = prose("age", "savage", "beasts", "spawns")
        check(said == "An Age. Its creatures are savage. Beasts dwell in it.") { "read as '$said'" }
    }

    test("a widening is said") {
        val said = prose("age", "teeming", "cat", "everywhere")
        check(said == "An Age. Teeming cats dwell everywhere.") { "read as '$said'" }
    }

    /** **It never launders** (§4.3.1): a page the Art moved is said where it went, not where it was laid. */
    test("a re-homed page is said in the sentence about where it landed") {
        val said = prose("age", "landmass", "starless")
        check("land" !in said) { "the page was said where it was laid rather than where it went: '$said'" }
        check("starless" in said) { "the re-homed page vanished: '$said'" }
    }

    test("an unread page never reaches the prose") {
        val said = prose("age", "zzzznotaword", "basalt", "landmass")
        check("zzzz" !in said) { "an unreadable page was laundered into the prose: '$said'" }
        check(said == "An Age. Its land is made of basalt.") { "the rest of the book did not survive: '$said'" }
    }

    test("no id reaches the prose with its underscores") {
        val said = prose("age", "ore_diamond_buried", "trees_plains", "oak", "amethyst_geode", "features")
        check('_' !in said) { "an id was said as it is spelled: '$said'" }
        check(said == "An Age. It holds diamond deposits, plains trees, oak trees and amethyst geodes.") {
            "read as '$said'"
        }
    }

    /** The pen never refuses, so neither may the reading of it (design §2). */
    test("the prose never refuses") {
        val nonsense = listOf(
            listOf("and"), listOf("only"), listOf("teeming"), listOf("zzzz", "yyyy"), listOf("basalt", "and"),
            listOf("except", "and", "only"), listOf("teeming", "and", "scarce"),
            listOf("landmass", "sea", "firmament", "atmosphere"), listOf("floating", "and", "and", "basalt"),
        ).map { pages -> listOf("age") + pages }
        for (pages in nonsense) {
            runCatching { prose(*pages.toTypedArray()) }
                .getOrElse { failure -> error("the prose refused '${pages.joinToString(" ")}': $failure") }
        }
    }

    /** A frame missing for a part of the world would print its key into a book. */
    test("every part of the world has a sentence to be said in") {
        val missing = Aspect.entries.flatMap { aspect ->
            listOf("is", "has").map { "prose.agesandtheart.${aspect.key}.$it" }.filter { it !in ours }
        }
        check(missing.isEmpty()) { "no frame for: $missing" }
    }

    test("a book's clauses survive being saved") {
        val clauses = Prose.of(read(listOf("beautiful", "age", "only", "zombie", "spawns", "in", "dark_forest")))
        val saved = ProseClause.CODEC.listOf().encodeStart(JsonOps.INSTANCE, clauses).getOrThrow()
        val loaded = ProseClause.CODEC.listOf().parse(JsonOps.INSTANCE, saved).getOrThrow()
        check(loaded == clauses) { "saved $clauses and loaded $loaded" }
    }
})

/** The two guesses English needs, which run on every name a book says. */
class EnglishCheck : FunSpec({
    test("plurals") {
        val cases = mapOf(
            "zombie" to "zombies", "witch" to "witches", "wolf" to "wolves", "dark forest" to "dark forests",
            "plains" to "plains", "allay" to "allays", "firefly" to "fireflies", "glow lichen" to "glow lichen",
            "tropical fish" to "tropical fish", "volcano" to "volcanoes", "fox" to "foxes", "enderman" to "endermen",
        )
        for ((one, many) in cases) check(English.plural(one) == many) { "'$one' made '${English.plural(one)}'" }
    }

    test("articles") {
        check(English.takesAn("ancient")) { "'an ancient'" }
        check(English.takesAn("Age")) { "'an Age'" }
        check(!English.takesAn("beautiful")) { "'a beautiful'" }
        check(!English.takesAn("uniform")) { "'a uniform'" }
    }

    test("feature ids are made legible") {
        val cases = mapOf(
            "ore_diamond_buried" to "diamond deposits", "trees_old_growth_pine_taiga" to "old growth pine trees",
            "seagrass_deep_warm" to "seagrass", "red_mushroom_old_growth" to "red mushroom",
            "trees_swamp" to "swamp trees", "monster_room_deep" to "monster room",
        )
        for ((id, said) in cases) {
            check(FeatureNames.legible(id) == said) { "'$id' read as '${FeatureNames.legible(id)}'" }
        }
    }
})
