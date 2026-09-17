package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.age.word.Draws
import co.voik.agesandtheart.age.word.Facets
import co.voik.agesandtheart.age.word.poolOfSingleSettings
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.preview.authoring.ui.Band
import co.voik.agesandtheart.preview.authoring.ui.Columns
import co.voik.agesandtheart.preview.authoring.ui.Gauge
import co.voik.agesandtheart.preview.authoring.ui.Step
import co.voik.agesandtheart.preview.authoring.ui.Touched
import co.voik.agesandtheart.preview.authoring.ui.Glyph
import co.voik.agesandtheart.preview.authoring.ui.Part
import co.voik.agesandtheart.preview.authoring.ui.Parts
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random
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
            pools = listOf(poolOfSingleSettings(mapOf("spacing" to "0.4..1.0"), Draws("1..2"))),
            template = "dark_void",
            mints = "minecraft:spring_water",
            unstated = "#agesandtheart:formation_substance",
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
     * **A field the tool can write must be a field the tool can reach.**
     *
     * `mints` was writable and unreachable for the whole life of the tool: the row drew itself only when
     * the word already minted, so there was no way to start; `enter` on it fell through a `when` that had
     * no branch for it; and the footer advertised both. Every pattern word in the corpus was written by
     * hand in the JSON, which is the one thing the vocabulary pass says not to do. `unstated` inherited
     * all of it the day it landed.
     */
    test("everything the makes section writes has a row to reach it") {
        val parts = Parts(Corpus.load())
        fun handlesFor(candidate: Candidate) =
            parts.rowsOf(Part.PROPERTIES, candidate, word = null, width = PANEL_WIDTH).map { it.handle }.toSet()

        check("+mints" in handlesFor(Candidate.blank("nothing"))) {
            "a word that mints nothing has no way to start: ${handlesFor(Candidate.blank("nothing"))}"
        }
        val minting = Candidate.blank("something").copy(mints = "minecraft:spring_water")
        val reachable = handlesFor(minting)
        check(setOf("mints", "flows", "+unstated").all { it in reachable }) {
            "a minting word cannot reach all of what it writes: $reachable"
        }
        check("unstated" in handlesFor(minting.copy(unstated = "minecraft:stone"))) {
            "a fallback that is set cannot be changed"
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
        for (invented in listOf(base.copy(sets = mapOf("suns" to "1")), base.copy(pools = listOf(poolOfSingleSettings(mapOf("suns" to "1"), Draws.of(1)))))) {
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

        val two = one.puttingInPool(Insistence.REQUIRED, 0, null, "rainfall", "-1.0..-0.4")
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
                poolOfSingleSettings(mapOf("sun.colour" to "red", "sun.size" to "0.7..1.0"), Draws.of(1)),
                poolOfSingleSettings(mapOf("sky.colour" to "red", "haze" to "0.4"), Draws.of(1)),
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

    /**
     * **A lean of nothing is not a lean.**
     *
     * Zero is how the screen says a member has not been leaned — it is what every row on the leaning list
     * starts at, and what enter puts one back to. Written out, it is an entry that steers nothing and
     * makes `Word.saysSomethingOf` claim the word spoke to that part of the world.
     */
    test("a lean stepped back to nothing leaves the file") {
        val leaned = Candidate.blank("leaner")
            .leaning(Aspect.SEA, "#molten", 0.7)
            .leaning(Aspect.SEA, "#frozen", 0.0)
            .leaning(null, "#bright", 0.0)
        val written = leaned.asJson()
        val biases = written.getAsJsonObject("biases")
        check(biases != null && biases.keySet() == setOf(Aspect.SEA.page)) {
            "a lean of nothing was written: $written"
        }
        check(biases.getAsJsonObject(Aspect.SEA.page).keySet() == setOf("#molten")) {
            "the sea kept a lean of nothing: $written"
        }
        val bare = Candidate.blank("bare").leaning(Aspect.SEA, "#molten", 0.0)
        check(!bare.asJson().has("biases")) { "a word leaning nothing wrote a biases field: ${bare.asJson()}" }
        check(bare.asWord().getOrThrow().saysSomethingOf(Aspect.SEA).not()) {
            "a lean of nothing made the word claim to speak to the sea"
        }
    }

    /**
     * **A lean's bar grows from the middle**, and which way is the half of it that changes what it does.
     *
     * Drawn as a plain string here rather than looked at: the styling is the terminal's business, but
     * where the filled cells sit is arithmetic and is what a reader takes the sign from.
     */
    test("a signed bar grows the way the number leans") {
        fun drawn(at: Double) = Gauge.signed(at, WIDE).inks.joinToString("") { it.text }
        val nothing = drawn(0.0)
        check(nothing == "░░░░░░│░░░░░░") { "nothing leaned should be an empty bar, and drew '$nothing'" }
        val whole = drawn(1.0)
        check(whole == "░░░░░░│██████") { "a whole lean toward should fill the right, and drew '$whole'" }
        val away = drawn(-1.0)
        check(away == "██████│░░░░░░") { "a whole lean away should fill the left, and drew '$away'" }
        check(drawn(0.5).endsWith("░░░")) { "half a lean should reach halfway, and drew '${drawn(0.5)}'" }
        check(drawn(2.0) == whole) { "a lean past the end should stop at it, and drew '${drawn(2.0)}'" }
    }

    /** An unsigned bar is empty where nothing is the most, rather than full. */
    test("a bar of nothing out of nothing is empty") {
        val drawn = Gauge.filled(0.0, 0.0, WIDE).inks.joinToString("") { it.text }
        check(drawn == Glyph.EMPTY.repeat(WIDE)) { "an empty gauge drew '$drawn'" }
    }

    /**
     * **A band is what its two ends are**, and taking an end off is what makes a floor or a ceiling.
     *
     * The screen has no list of shapes any more, so nothing else says which shape a value ends up being:
     * it falls out of how many ends the band has, and a shape nobody can reach is a shape the language
     * has lost.
     */
    test("a band's shape falls out of its ends") {
        val temperature = Aspect.CLIMATE.parameters.first { it.holds == Holds.RANGE }
        fun editing(said: String) = Band("", temperature, emptyList(), said) {}

        val whole = editing("0.5..1.0")
        check(whole.spelled == "0.5..1") { "a band did not come back as one: ${whole.spelled}" }
        whole.dropTheEnd(high = true)
        check(whole.spelled == ">0.5") { "dropping the top should leave a floor, and left ${whole.spelled}" }
        whole.dropTheEnd(high = true)
        check(whole.spelled == "0.5..1") { "putting the top back should leave a band, and left ${whole.spelled}" }
        whole.dropTheEnd(high = false)
        check(whole.spelled == "<1") { "dropping the bottom should leave a ceiling, and left ${whole.spelled}" }
        // Never both: a value with neither end says nothing, and the way to say nothing is to not take one.
        whole.dropTheEnd(high = true)
        check(whole.spelled == "<1") { "both ends came off: ${whole.spelled}" }
    }

    /**
     * **The band row says the band, whatever the cursor is on.**
     *
     * A value is one setting, so the three rows are exclusive and each has to keep saying its own — the
     * chart follows the cursor and the rows do not, or moving to `nudge` rewrote the band under it.
     */
    test("moving to a nudge leaves the band alone") {
        val temperature = Aspect.CLIMATE.parameters.first { it.holds == Holds.RANGE }
        val editing = Band("", temperature, emptyList(), "0.4..1.0") {}
        check(editing.band == "0.4..1") { "the band opened as ${editing.band}" }
        editing.move(1)
        editing.step(0.5)
        check(editing.row == Band.Row.NUDGE) { "the cursor did not reach the nudge" }
        check(editing.band == "0.4..1") { "moving to the nudge rewrote the band as ${editing.band}" }
        check(editing.spelled == "+0.5") { "the nudge said ${editing.spelled}" }
        check(editing.drawn == "+0.5") { "the chart drew ${editing.drawn} rather than the nudge" }
    }

    /**
     * **A nudge shown against a band actually moves it.**
     *
     * `settle` clamps a shift to the axis it is given, so handing it the example band as that axis gave
     * the claim nowhere to go and drew `+1.0` as no change at all — the one thing the illustration exists
     * to show.
     */
    test("an illustrated nudge lands somewhere else") {
        val moved = Setting.settle(listOf(Setting.Fixed(Band.ILLUSTRATION), Setting.Shift(1.0)))
        check(moved != null && moved != Band.ILLUSTRATION) { "a whole nudge left the band at $moved" }
        check(moved.most == Span.NATURAL_MOST) { "a whole nudge should reach the top, and reached ${moved.most}" }
        val widened = Setting.settle(listOf(Setting.Fixed(Band.ILLUSTRATION), Setting.Spread(0.5)))
        check(widened != null && widened.width > Band.ILLUSTRATION.width) { "a spread did not widen: $widened" }
    }

    /** A nudge and a spread are scalars, so they are stepped rather than drawn, and each is its own value. */
    test("a nudge is its own shape, not a band with one end") {
        val temperature = Aspect.CLIMATE.parameters.first { it.holds == Holds.RANGE }
        val nudging = Band("", temperature, emptyList(), "+0.3") {}
        check(nudging.row == Band.Row.NUDGE) { "a nudge opened on ${nudging.row}" }
        check(nudging.spelled == "+0.3") { "a nudge did not come back: ${nudging.spelled}" }
        nudging.step(-0.1)
        check(nudging.spelled == "+0.2") { "stepping a nudge gave ${nudging.spelled}" }
    }

    /**
     * **Settings that only mean anything together are drawn together.**
     *
     * An ice halo is a bow's colours, its size and how much rain it wants said at once; drawn one at a
     * time it comes out as an ordinary bow that happens to be white, which is not the thing anybody
     * meant. A group counts as **one** thing drawn, however many settings it holds — otherwise `draws 1`
     * over a halo and a glow would be a coin toss between four values rather than between two ideas.
     */
    test("a pool draws a group whole or not at all") {
        val halo = mapOf("rainbow.colour" to "white", "rainbow.size" to "<0.3", "rainbow.rain" to "-1.0")
        val pool = Facets(listOf(halo, mapOf("aurora.glow" to ">0.6")), Draws.of(1))
        check(pool.offers.size == 2) { "two offers, and the pool held ${pool.offers.size}" }
        check(pool.facets.size == 4) { "every setting it could make is four, and it said ${pool.facets.size}" }
        val drawn = (0 until 40).map { pool.drawnWith(Random(it.toLong())) }
        check(drawn.any { it.keys == halo.keys }) { "the halo never came whole" }
        check(drawn.any { it.keys == setOf("aurora.glow") }) { "the glow never came at all" }
        val broken = drawn.firstOrNull { it.keys.any(halo::containsKey) && it.keys != halo.keys }
        check(broken == null) { "the halo came in pieces: $broken" }
    }

    /**
     * **A pool of ordinary settings writes as the object it always was.**
     *
     * Every pool in the corpus is that shape, so a list of one-entry maps would have been noise added to
     * every word to serve the one that needed the room — the same bargain `Tier` strikes.
     */
    test("only a pool with a group spells its groups out") {
        val plain = poolOfSingleSettings(mapOf("haze" to "0.4", "tint" to "blue"), Draws.of(1))
        val written = Facets.CODEC.encodeStart(JsonOps.INSTANCE, plain).getOrThrow()
        check(written.asJsonObject.get("facets").isJsonObject) { "a plain pool wrote $written" }
        check(Facets.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow() == plain) { "a plain pool did not return" }

        val grouped = Facets(listOf(mapOf("a" to "1", "b" to "2"), mapOf("c" to "3")), Draws.of(1))
        val out = Facets.CODEC.encodeStart(JsonOps.INSTANCE, grouped).getOrThrow()
        check(out.asJsonObject.get("facets").isJsonArray) { "a grouped pool wrote $out" }
        check(Facets.CODEC.parse(JsonOps.INSTANCE, out).getOrThrow() == grouped) { "a grouped pool did not return" }
    }

    /**
     * **A tier is its numbers, and its name is derived from them.**
     *
     * The three the Art names still write and read as names, so nothing in the corpus moves; a word that
     * states its own writes them out and comes back the same. Without the first half every word in the
     * pack would have gained five lines it did not ask for.
     */
    test("a named tier stays a name and its own numbers stay numbers") {
        for ((named, tier) in Tier.NAMED) {
            val written = Tier.CODEC.encodeStart(JsonOps.INSTANCE, tier).getOrThrow()
            check(written.isJsonPrimitive && written.asString == named) {
                "'$named' should still write as its name, and wrote $written"
            }
        }
        val ownNumbers = Tier(cost = 6, threshold = 0.55, weight = 2, narrows = true, versatilityMultiplier = Tier.FLAT)
        check(ownNumbers.key == Tier.CUSTOM) { "a tier matching none of the three called itself '${ownNumbers.key}'" }
        val written = Tier.CODEC.encodeStart(JsonOps.INSTANCE, ownNumbers).getOrThrow()
        val read = Tier.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow()
        check(read == ownNumbers) { "its own numbers did not come back: $written became $read" }
    }

    /**
     * **What a word costs is its own to say.** A page reaching three parts of the world is dearer than one
     * reaching one — unless it says otherwise, which is what the reach switch is for and what nothing
     * could say while the multiplier was read off whether the word narrows.
     */
    test("a word may be priced flat however far it reaches") {
        val wide = Word(
            id = Identifier.fromNamespaceAndPath("test", "wide"),
            tier = Tier.RESTRICTIVE,
            aspects = setOf(Aspect.SEA, Aspect.SKY, Aspect.TERRAIN),
            restricts = mapOf(Aspect.SEA to mapOf("#molten" to 1.0)),
        )
        check(wide.price == Tier.RESTRICTIVE.cost * 3) { "reach stopped being charged: ${wide.price}" }
        val flat = wide.copy(tier = Tier.RESTRICTIVE.copy(versatilityMultiplier = Tier.FLAT))
        check(flat.price == Tier.RESTRICTIVE.cost) { "a flat price still counted the reach: ${flat.price}" }
        check(flat.tier.narrows) { "turning the reach off stopped the word narrowing" }
        // **Never below the base cost**, so no page is ever free — which is what a multiplier of zero
        // means, and what keeps the beginner's sentence the cheapest thing in the language rather than
        // the free one.
        val free = wide.copy(tier = Tier.RESTRICTIVE.copy(cost = 3, versatilityMultiplier = 0.1))
        check(free.price == 3) { "a small multiplier priced the page below its base: ${free.price}" }
        val halved = wide.copy(tier = Tier.RESTRICTIVE.copy(versatilityMultiplier = 0.5))
        check(halved.price == 3) { "half a multiplier over three aspects gave ${halved.price}" }
    }

    /**
     * **A tag's row names what it caught, not what it is.**
     *
     * "removes everything with `#flowering`" is the definition read back; what a writer is checking is
     * whether the cherry grove and the meadow are what they meant to lose. Drawn from the curated pool,
     * so it says what the Art can actually reach rather than what the registry holds.
     */
    test("a tag's panel names the members it catches") {
        val carrying = corpus.vocabulary.candidatesFor(Aspect.BIOMES)
            .filter { corpus.vocabulary.tagsOf(it).containsKey("frozen") }
        check(carrying.isNotEmpty()) { "nothing in biomes carries #frozen, so this checks nothing" }
        val said = plainly(Touched.of(Aspect.BIOMES, "#frozen", Step.REMOVE, corpus, PANEL_WIDTH))
        check(said.contains("${carrying.size}")) { "it did not count what it found: $said" }
        val named = carrying.map { it.key }.sorted().take(3)
        check(named.all { it in said }) { "it did not name what it found: $said" }
    }

    /**
     * **The heading says what the step did with them**, since one function serves all five and the list
     * alone cannot tell "kept these" from "took these out".
     */
    test("the panel says which step named them") {
        val verbs = Step.entries.associateWith { step ->
            plainly(Touched.of(Aspect.BIOMES, "#frozen", step, corpus, PANEL_WIDTH)).lines().first()
        }
        check(verbs.getValue(Step.REMOVE).contains("removed from biomes")) { "remove said ${verbs[Step.REMOVE]}" }
        check(verbs.getValue(Step.KEEP).contains("kept in biomes")) { "keep said ${verbs[Step.KEEP]}" }
        check(verbs.values.distinct().size == verbs.size) { "two steps read the same: ${verbs.values}" }
    }

    /** A tag nothing carries says so, rather than an empty list under a count of zero. */
    test("a tag nothing answers says so") {
        val said = plainly(Touched.of(Aspect.BIOMES, "#nosuchtagexists", Step.REMOVE, corpus, PANEL_WIDTH))
        check("nothing in biomes" in said) { "an unanswered tag drew '$said'" }
    }

})

/** Wide enough to have a middle and six cells either side of it. */
private const val WIDE = 13

/** Wide enough for the panel to lay a list out rather than refuse to draw. */
private const val PANEL_WIDTH = 78

/** A drawing as plain text, for a check that is about what it says rather than how it is coloured. */
private fun plainly(lines: List<co.voik.agesandtheart.preview.authoring.ui.Line>): String =
    lines.joinToString(" ") { line -> line.inks.joinToString("") { it.text } }
