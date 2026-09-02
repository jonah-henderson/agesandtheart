package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.age.word.Draws
import co.voik.agesandtheart.age.word.Facets
import co.voik.agesandtheart.preview.authoring.ui.Columns
import co.voik.agesandtheart.preview.authoring.ui.Insistence
import co.voik.agesandtheart.preview.authoring.ui.addingAPool
import co.voik.agesandtheart.preview.authoring.ui.drawing
import co.voik.agesandtheart.preview.authoring.ui.poolsOn
import co.voik.agesandtheart.preview.authoring.ui.puttingInPool
import co.voik.agesandtheart.preview.authoring.ui.withoutInPool
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
            leansEverywhere = mapOf("#solid" to 1.0),
            restricts = mapOf(Aspect.SKY to mapOf("bright" to 1.0)),
            chooses = mapOf(Aspect.TERRAIN to "hills"),
            admits = mapOf(Aspect.BIOMES to setOf("minecraft:plains")),
            excludes = mapOf(Aspect.SEA to setOf("#watery")),
            biases = mapOf(Aspect.BIOMES to mapOf("minecraft:plains" to 1.0)),
            sets = mapOf("stone" to "minecraft:stone"),
            pools = listOf(Facets(mapOf("spacing" to "0.4..1.0"), Draws("1..2"))),
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
        for (invented in listOf(base.copy(sets = mapOf("suns" to "1")), base.copy(pools = listOf(Facets(mapOf("suns" to "1"), Draws.of(1)))))) {
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
            leansEverywhere = mapOf("#wondrous" to 1.0),
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
    /**
     * The two ways a meaning goes wrong, both refused rather than merely shown — see `Verdict.meaningFaults`
     * and the two `VocabularyCheck` tests it names.
     */
    test("a meaning only a narrowing word could carry is refused") {
        val leaning = Candidate(
            name = "probe",
            tier = Tier.EVOCATIVE,
            chooses = mapOf(Aspect.CARVERS to "caves"),
        )
        val said = Verdict.refusals(Verdict.on(leaning, corpus))
        check(said.any { it.says.contains("evocative") }) {
            "an evocative word meaning a preset outright was not refused: ${said.joinToString { it.says }}"
        }
    }

    test("meaning a preset another page already means is refused") {
        val second = Candidate(
            name = "probe",
            tier = Tier.EXACT,
            chooses = mapOf(Aspect.TERRAIN to "alps"),
        )
        val said = Verdict.refusals(Verdict.on(second, corpus))
        check(said.any { it.says.contains("alps") }) {
            "a second page meaning the alps was not refused: ${said.joinToString { it.says }}"
        }
    }

    /**
     * **A pool and its count move together**, so neither of the two shapes `Verdict` has to refuse — a
     * pool drawing none of itself, a count over an empty pool — can be reached by editing at all.
     */
    test("a pool carries its own count in and out") {
        val empty = Candidate(name = "probe", tier = Tier.EXACT)
        val one = empty.addingAPool(Insistence.REQUIRED, "temperature", "0.5..1.0")
        check(one.poolsOn(Insistence.REQUIRED).single().draws.most == 1) { "a first facet left the pool drawing none" }

        val two = one.puttingInPool(Insistence.REQUIRED, 0, "rainfall", "-1.0..-0.4")
            .drawing(Insistence.REQUIRED, 0, Draws.of(2))
        val fewer = two.withoutInPool(Insistence.REQUIRED, 0, "rainfall")
        check(fewer.poolsOn(Insistence.REQUIRED).single().draws.most == 1) { "the count outran the pool" }
        check(fewer.withoutInPool(Insistence.REQUIRED, 0, "temperature").poolsOn(Insistence.REQUIRED).isEmpty()) {
            "an emptied pool stayed, and the heading it is edited on is gone"
        }
    }

    /**
     * **A pool per thing being varied**, which is what one flat pool could never say: an inferno drawing
     * three of five could roll every sun facet and no sky at all.
     */
    test("pools are drawn one at a time and never decide each other") {
        val word = Word(
            id = Identifier.fromNamespaceAndPath("agesandtheart", "probe"),
            tier = Tier.RESTRICTIVE,
            aspects = setOf(Aspect.SUN),
            pools = listOf(
                Facets(mapOf("sun.colour" to "red", "sun.size" to "0.7..1.0"), Draws.of(1)),
                Facets(mapOf("sky.colour" to "red", "haze" to "0.4"), Draws.of(1)),
            ),
        )
        for (draw in 0L..<40L) {
            val drawn = word.setsDrawnAt(draw)
            check(drawn.keys.count { it.startsWith("sun.") } == 1) { "the sun pool drew ${drawn.keys} at $draw" }
            check(drawn.size == 2) { "a pool took another's turn at $draw: $drawn" }
        }
    }

    /**
     * **Not every derived word is a registry entry**, which the ink screen had assumed: a landform's page
     * is minted from the landform and has no id in any registry to hang a tag on, so asking for one said
     * "nothing in the game has the id agesandtheart:alps" and its ink could not be set at all.
     */
    test("a page minted from one of our designs takes its ink by name") {
        val alps = corpus.vocabulary.words.distinct().firstOrNull { it.name == "alps" }
        checkNotNull(alps) { "no page means the alps — is DerivedWords.designs running?" }
        val page = Candidate.of(alps)
        check(page.isDerived) { "the alps page is not derived, so this check is testing nothing" }
        check(!page.inkedByTag) { "the alps page would be inked by tagging '${page.id}', which is in no registry" }

        val ice = corpus.vocabulary.words.distinct().firstOrNull { it.name == "ice" }
        checkNotNull(ice) { "no derived word for ice" }
        check(Candidate.of(ice).inkedByTag) { "a block's word must be inked by tagging the block" }
    }

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

    /**
     * **A column is as wide as what is in it**, which is the whole of what one shared layout buys: every
     * list in the tool declared its own widths and one worked the last out from the canvas by hand, so a
     * value longer than somebody's guess was cut on every terminal and a short one left a gutter.
     */
    test("columns are measured off their contents") {
        val rows = listOf(listOf("sea", "minecraft:water"), listOf("landmass", "vanilla"))
        val plain = listOf(Columns.Column(), Columns.Column())
        val roomy = Columns.widths(rows, plain, room = 60, gap = 2)
        check(roomy == listOf("landmass".length, "minecraft:water".length)) {
            "a column with room to spare should fit its widest entry exactly, and gave $roomy"
        }
    }

    /**
     * **The room nobody wanted goes to whoever said they would take it**, so a table on a wide terminal
     * spends the difference on the column holding a sentence rather than leaving it blank at the edge.
     */
    test("spare room goes to the growing column") {
        val rows = listOf(listOf("sea", "it fills what the shapes leave"))
        val columns = listOf(Columns.Column(), Columns.Column(grows = true))
        val widths = Columns.widths(rows, columns, room = 80, gap = 2)
        check(widths.sum() + 2 == 80) { "the spare room went nowhere: $widths" }
        check(widths.first() == "sea".length) { "the fixed column grew as well: $widths" }
    }

    /**
     * **The widest columns give the room up, not every column equally.**
     *
     * A flat share would take the same off a column of two-character counts as off one holding an id,
     * which cuts the readable column to nothing to save four characters on the unreadable one.
     */
    test("a narrow pane levels the widest columns down") {
        val rows = listOf(listOf("ab", "a very long value indeed that will not fit"))
        val columns = listOf(Columns.Column(), Columns.Column())
        val widths = Columns.widths(rows, columns, room = 20, gap = 2)
        check(widths.sum() + 2 <= 20) { "the columns overran the room: $widths" }
        check(widths.first() == 2) { "the short column was cut to pay for the long one: $widths" }
    }

    /** Nothing is drawn below the least it asked for, however little room there is. */
    test("a column is never drawn below its least") {
        val rows = listOf(listOf("a name that is long", "another that is long"))
        val columns = listOf(Columns.Column(least = 12), Columns.Column(least = 12))
        val widths = Columns.widths(rows, columns, room = 10, gap = 2)
        check(widths == listOf(12, 12)) { "a least was given up: $widths" }
    }
})
