package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Word
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier

/**
 * The word-authoring tool's own checks — **the pure half of it, which is all of it that matters.**
 *
 * The editor is a terminal and is not asserted on. What is asserted is that the tool cannot lie: that the
 * file it writes reads back as the same word, that the fields it knows are the fields the codec reads,
 * and that its refusals are the ones the corpus checks hold.
 */
@Tags(NEEDS_REGISTRIES)
class AuthoringCheck : FunSpec({

    val corpus by lazy { Corpus.load() }

    /**
     * **Every field the codec reads is a field the writer writes.**
     *
     * The same guard `GrammarSources` carries, for the same reason: the tool lays JSON out by hand
     * because the codec drops `_comment` and would freeze a derived reach into the file. A field added to
     * `Word` and not here would go missing from every word the tool ever wrote, in silence.
     */
    test("the writer knows every field the codec reads") {
        val everything = Word(
            id = Identifier.fromNamespaceAndPath("agesandtheart", "probe"),
            tier = Tier.EXACT,
            aspects = setOf(Aspect.SKY),
            everywhere = mapOf("solid" to 1.0),
            queries = mapOf(Aspect.SKY to mapOf("bright" to 1.0)),
            meansExactly = mapOf(Aspect.TERRAIN to "hills"),
            sets = mapOf("stone" to "minecraft:stone"),
            pool = mapOf("spacing" to "0.4..1.0"),
            draws = 1,
            weights = mapOf(Aspect.BIOMES to mapOf("minecraft:plains" to 1.0)),
            template = "dark_void",
            mints = "minecraft:spring_water",
            mintsSomethingThatFlows = true,
        )
        val encoded = Word.mapCodec(everything.id).codec()
            .encodeStart(JsonOps.INSTANCE, everything)
            .getOrThrow { complaint -> IllegalStateException(complaint) }
            .asJsonObject
        val unknown = encoded.keySet() - Candidate.KNOWN_FIELDS
        check(unknown.isEmpty()) {
            "Word's codec writes ${unknown.joinToString()}, which Candidate.asJson would drop in silence"
        }
    }

    /**
     * **Every authored word survives being read and written.**
     *
     * The round trip is the whole promise of the tool owning the file text: open a word, change nothing,
     * write it, and the bytes are the bytes. `_comment` is the part at risk — the codec ignores it, so
     * nothing but this holds it.
     */
    test("every authored word round-trips through the writer") {
        for (name in WordFile.authoredNames()) {
            val candidate = WordFile.read(name).getOrElse { failure ->
                error("'$name' would not read: ${failure.message}")
            }
            val written = WordFile.textOf(candidate)
            val original = WordFile.fileFor(name).readText()
            check(written == original) {
                "rewriting '$name' unchanged would alter its file — the writer is not preserving something"
            }
        }
    }

    /**
     * **A word the tool writes is the word the pack loads.** Not the same statement as the round trip
     * above: that one is about bytes, this one is about meaning, and a writer that emitted the *derived*
     * aspects would pass one and fail the other.
     */
    test("what the tool writes loads as what it showed") {
        for (name in WordFile.authoredNames()) {
            val candidate = WordFile.read(name).getOrThrow()
            val fromTheTool = candidate.asWord().getOrElse { failure ->
                error("'$name' would not load as a word: ${failure.message}")
            }
            val fromThePack = corpus.vocabulary.word(name)
                ?: error("the corpus has no word called '$name'")
            check(fromTheTool == fromThePack) {
                "'$name' reads differently through the tool than through the pack"
            }
        }
    }

    /**
     * The whole corpus clears the tool's own refusals.
     *
     * A refusal is a check that would fail, so this and `VocabularyCheck` must agree — and it is how a
     * rule written here too strictly is caught, rather than by a writer being told their word is wrong.
     */
    test("nothing the pack ships is refused") {
        for (name in WordFile.authoredNames()) {
            val candidate = WordFile.read(name).getOrThrow()
            val refused = Verdict.refusals(Verdict.on(candidate, corpus))
            check(refused.isEmpty()) {
                "'$name' is refused by the authoring tool: " +
                    refused.joinToString("; ") { "${it.says} (${it.because})" }
            }
        }
    }

    /**
     * **A parameter no aspect owns is refused, wherever it is written.**
     *
     * `inferno` carried a pooled `suns` for as long as the corpus check read `sets` alone, so it was a
     * page paid for that set nothing and said so nowhere. Written against both maps, because the pool is
     * the half that was not being asked.
     */
    test("a parameter nothing turns is refused, in the core and in the pool") {
        val base = Candidate(name = "probe", tier = Tier.EXACT, )
        for (invented in listOf(base.copy(sets = mapOf("suns" to "1")), base.copy(pool = mapOf("suns" to "1"), draws = 1))) {
            val said = Verdict.refusals(Verdict.on(invented, corpus))
            check(said.any { it.says.contains("suns") }) {
                "a parameter no aspect owns was not refused: ${said.joinToString { it.says }}"
            }
        }
    }

    /** An aspect prefix naming nothing is a parameter nobody will ever read — `VocabularyCheck` holds it too. */
    test("a parameter qualified with a part of the world that does not exist is refused") {
        val invented = Candidate(
            name = "probe",
            tier = Tier.EXACT,
            sets = mapOf("firmament.colour" to "red"),
        )
        val said = Verdict.refusals(Verdict.on(invented, corpus))
        check(said.any { it.says.contains("firmament") }) {
            "a prefix naming no aspect was not refused: ${said.joinToString { it.says }}"
        }
    }

    /** A value the parameter does not take, which is the fault a writer cannot see from the file. */
    test("a value the parameter refuses is refused") {
        val invented = Candidate(
            name = "probe",
            tier = Tier.EXACT,
            sets = mapOf("rising" to "widdershins"),
        )
        val said = Verdict.refusals(Verdict.on(invented, corpus))
        check(said.any { it.says.contains("widdershins") }) {
            "a value outside the parameter's options was not refused: ${said.joinToString { it.says }}"
        }
    }

    /** A tag nothing carries and no antonym opposes — the misspelling catcher, as `TagCoverageCheck` has it. */
    test("a tag nothing carries is refused") {
        val invented = Candidate(
            name = "probe",
            tier = Tier.EVOCATIVE,
            everywhere = mapOf("wondrous" to 1.0),
        )
        val said = Verdict.refusals(Verdict.on(invented, corpus))
        check(said.any { it.says.contains("wondrous") }) {
            "a tag nothing carries was not refused: ${said.joinToString { it.says }}"
        }
    }

    /**
     * **A rock has to hold somebody up.** The one refusal that is about the world rather than the schema:
     * a world of signs is one a player falls out of the bottom of.
     */
    test("a rock you would fall through is refused") {
        val invented = Candidate(
            name = "probe",
            tier = Tier.EXACT,
            sets = mapOf("stone" to "minecraft:oak_sign"),
        )
        val said = Verdict.refusals(Verdict.on(invented, corpus))
        check(said.any { it.says.contains("oak_sign") }) {
            "a block nothing can stand on was accepted as rock: ${said.joinToString { it.says }}"
        }
    }

    /**
     * A word file is read for what it *declares*, never for what a loaded word derives.
     *
     * `red` declares nothing and reaches eight aspects. Writing the derived set back would pin today's
     * reach into the file and stop the next aspect with a colour from ever being painted by it.
     */
    test("a word's reach is derived and never written down") {
        val red = WordFile.read("red").getOrThrow()
        val reached = red.asWord().getOrThrow().aspects
        check(reached.size > 1) { "'red' should reach every aspect with a colour, and reached $reached" }
        check("\"aspects\"" !in WordFile.textOf(red)) { "the reach was written back into the file" }
        check(Candidate.COMMENT !in WordFile.textOf(red.copy(comment = null))) {
            "a word with no comment should not be written one"
        }
    }

    /**
     * **Renaming a word moves it, rather than copying it.**
     *
     * Saving under a new name used to write the new file and leave the old one, so the corpus grew a
     * second word — and the rarity, the ink and the display name stayed under a name nothing would look
     * up again. Those three belong to the word rather than to what it is called.
     */
    test("a rename takes the word's rarity, ink and display with it") {
        val from = "probe_before_rename"
        val to = "probe_after_rename"
        try {
            WordFile.write(Candidate.blank(from).copy(sets = mapOf("colour" to "red")))
            WordFile.list("rarity", from, "rare")
            WordFile.setDisplay(from, "Probed")

            WordFile.renameWord(from, to)

            check(!WordFile.exists(from)) { "the old file is still there" }
            check(WordFile.exists(to)) { "the new file was not made" }
            check(WordFile.listingFor(from).rarity == null) { "a rarity was left under the old name" }
            check(WordFile.listingFor(to).rarity == "rare") { "the rarity did not move" }
            check(WordFile.displayOf(from) == null) { "a display name was left under the old name" }
            check(WordFile.displayOf(to) == "Probed") { "the display name did not move" }
        } finally {
            WordFile.deleteWord(from)
            WordFile.deleteWord(to)
        }
    }

    /** And deleting one takes all four with it, or the corpus keeps entries naming nothing. */
    test("deleting a word leaves nothing behind") {
        val name = "probe_to_delete"
        WordFile.write(Candidate.blank(name).copy(sets = mapOf("colour" to "red")))
        WordFile.list("rarity", name, "rare")
        WordFile.list("ink", name, "fine")
        WordFile.setDisplay(name, "Probed")

        WordFile.deleteWord(name)

        check(!WordFile.exists(name)) { "the file is still there" }
        check(WordFile.listingFor(name).rarity == null) { "a rarity was left behind" }
        check(WordFile.listingFor(name).ink == null) { "an ink was left behind" }
        check(WordFile.displayOf(name) == null) { "a display name was left behind" }
    }

    /** The comment is the part the codec drops, so it is the part worth a check of its own. */
    test("the reasoning survives an edit") {
        val written = WordFile.textOf(
            Candidate.blank("probe").commenting(listOf("One line.", "", "And another.")),
        )
        val read = Candidate.read("probe", JsonParser.parseString(written).asJsonObject).getOrThrow()
        check(read.commentLines == listOf("One line.", "", "And another.")) {
            "the comment came back as ${read.commentLines}"
        }
        val single = Candidate.blank("probe").commenting(listOf("Just the one."))
        check(!WordFile.textOf(single).contains("[")) {
            "a one-line comment should be written as a string, as the corpus spells it"
        }
    }
})
