package co.voik.agesandtheart.age.readout

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.reading.Trace
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.reading.Writing
import co.voik.agesandtheart.age.words.Headings
import co.voik.agesandtheart.age.words.ShippedWords
import co.voik.agesandtheart.location
import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier

/**
 * A book's readout — how things turned out, each thing said of the part it was said of and only what held — written in
 * English off this pack's language file, over the words we ship (`notes/the-word-rework.md`, step 7). With the trace the
 * instruments print, and the headings the word list is ordered under.
 */
@Tags(NEEDS_REGISTRIES)
class ReadoutCheck : FunSpec({

    val language by lazy {
        val file = ReadoutCheck::class.java.getResourceAsStream("/assets/agesandtheart/lang/en_us.json")
            ?: error("the language file is not on the classpath")
        JsonParser.parseString(file.reader().readText()).asJsonObject
    }
    val words by lazy { ShippedWords.with() }
    val writing by lazy { Writing(words, ShippedCorpus.vocabulary, WorldMembership(ShippedCorpus.vocabulary)) { false } }

    fun readAs(row: String): String {
        val written = writing.written(row.split(" "), 0L)
        val clauses = Readout.of(written.reading, written.drawn, written.resolution, words)
        return ReadoutWriting.sentencesOf(clauses) { key -> language.get(key)?.asString }.joinToString(" ")
    }

    for ((row, expected) in BOOKS) {
        test("`$row` reads out as \"$expected\"") {
            val said = readAs(row)
            check(said == expected) { "`$row` read out as \"$said\"" }
        }
    }

    test("a dangling page is said where it landed: `sheep fliers` may be herds of sheep") {
        val herdsOfSheep = (0L..<60L).map { seed ->
            val written = writing.written("sheep fliers age".split(" "), seed)
            written.drawn.dangling.single().drawnOnto to Readout.of(written.reading, written.drawn, written.resolution, words)
        }.firstOrNull { (onto, _) -> onto == "herds" } ?: error("sheep were never drawn onto herds")
        val said = ReadoutWriting.sentencesOf(herdsOfSheep.second) { key -> language.get(key)?.asString }.joinToString(" ")
        check("Herds of sheep" in said) { "the sheep drawn onto herds read out as \"$said\"" }
    }

    test("the trace says what became of each page, and the flaws") {
        val written = writing.written("sheep fliers north rising sun age".split(" "), 0L)
        val trace = Trace.of(written.reading, written.drawn)
        check(trace.first().startsWith("1. sheep: dangles")) { "sheep traced as ${trace.first()}" }
        check(trace.any { it.startsWith("4. rising: said of what took it") }) { "rising traced as ${trace.getOrNull(3)}" }
        check(trace.any { "nothing could read 'sheep'" in it }) { "the flaw went untraced: $trace" }
    }

    test("the headings: a describing word under what takes it, a creature under its part's page") {
        val applies = Headings.appliesTo(words)
        fun ours(name: String) = name.location()
        val red = applies[ours("red")].orEmpty()
        check(ours("sky") in red && ours("trees") in red) { "red applies to $red" }
        check(applies[Identifier.parse("minecraft:zombie")] == listOf(ours("inhabitants"))) { "zombie is under ${applies[Identifier.parse("minecraft:zombie")]}" }
        check(ours("sky") in Headings.parts(words) && Identifier.parse("minecraft:zombie") in Headings.members(words))
    }
}) {
    private companion object {
        val BOOKS = listOf(
            "age large insular landmass tunnelled underground" to
                "An Age. Its landmass is large and insular. Its underground is tunnelled.",
            "age large spider large beasts" to "An Age. Its spiders are large. Its beasts are large.",
            "age teeming large spider" to "An Age. Its spiders are teeming and large.",
            "age insular and pillared landmass" to "An Age. Its landmass is insular and pillared.",
            "age herds of warden" to "An Age. Herds of wardens dwell in it.",
            "age no zombie" to "An Age. No zombies dwell in it.",
            "no snow age insular landmass" to "An Age without snow. Its landmass is insular.",
            "homely age gentle landmass" to "A homely Age. Its landmass is gentle.",
            "age no features" to "An Age. Nothing grows in it.",
            "age no inhabitants" to "An Age. Nothing dwells in it.",
            "age north rising east setting sun" to "An Age. Its sun is rising in the north and setting in the east.",
            "age north wind" to "An Age. Its air is from the north and windy.",
            "age rising sea" to "An Age. Its sea is rising.",
            "age rainbow" to "An Age. Its phenomena include rainbows.",
            "age aurora" to "An Age. Its phenomena include auroras.",
            "age rainbow sky" to "An Age. Its sky is rainbow.",
        )
    }
}
