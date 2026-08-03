package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.word.Vocabulary
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Whether the parse says itself back honestly (design §4.3.1). The prose is the only thing that makes the
 * single deterministic reading *checkable by the writer*, so what these ask is mostly the same question in
 * different shapes: does the sentence you get back show where each page actually landed?
 *
 * What they deliberately do **not** ask is whether it reads gracefully. The corpus has no nouns to hang a
 * sentence on yet — an aiming page is the nearest thing — so "landmass floating of basalt" is as good as
 * the words allow, and making it prettier is a vocabulary pass rather than a rendering one.
 *
 * Offline the corpus is blocks and the authored words; biomes and structure sets arrive with a server's
 * dynamic registries, so every book here is written out of what a check can reach.
 */
@Tags(NEEDS_REGISTRIES)
class ReadoutCheck : FunSpec({

    val vocabulary by lazy {
        Vocabulary.load(MinecraftRegistries.shippedData()).also {
            check(it.problems.isEmpty()) { "the corpus would not load: ${it.problems}" }
        }
    }

    /**
     * A book, as it reads. **The nucleus is supplied here** rather than written into every fixture: every
     * book has an `age`, and what these check is the reading of what follows it.
     */
    fun readingOf(vararg pages: String): String = Readout.of(Grammar.read(vocabulary, listOf("age") + pages))

    /**
     * The particle a writer was spared, restored to show the position it was inferred into — which is the
     * whole trick (§4.3.1). A material belongs to the section it sits in, and `of` is what says so.
     */
    test("a material is attached to what it is aimed at") {
        val reading = readingOf("landmass", "floating", "basalt")
        check(reading == "landmass floating of basalt.") { "read back as '$reading'" }
    }

    /**
     * **The failure the readout exists to catch.** If the parser attached a material to the wrong section,
     * the prose says so at once — before a drop of ink is spent. Here the sea's own material must arrive
     * under the sea and not under the land, and the sea must be placed *over* against the land.
     */
    test("each section keeps its own material") {
        val reading = readingOf("landmass", "floating", "basalt", "sea", "molten", "lava")
        check(reading == "landmass floating of basalt, over sea molten of lava.") {
            "the sections blurred into each other: '$reading'"
        }
    }

    /**
     * `and` is the one page whose whole meaning is that both words stay — so a reading that flattened a
     * joined run into juxtaposition would hide the only thing it changed (§3.2).
     */
    test("a joined run stays joined") {
        val joined = readingOf("landmass", "basalt", "and", "slate")
        check(joined == "landmass of basalt and slate.") { "'basalt and slate' read back as '$joined'" }

        val apart = readingOf("landmass", "basalt", "slate")
        check(" and " !in apart) { "unjoined words were read back as joined: '$apart'" }
    }

    /**
     * A word naming a preset is a claim on the aspect, not a property of another word — "riddled *of*
     * flooded" would read as one made out of the other, which is not what either page says.
     */
    test("a claim on an aspect takes no particle") {
        val reading = readingOf("depths", "riddled", "flooded")
        check(reading == "depths riddled flooded.") { "read back as '$reading'" }
    }

    /** `only` and `except` are pages the writer laid down, and the reading has to show them. */
    test("only and except are said out loud") {
        val singled = readingOf("landmass", "only", "blackstone")
        check("only blackstone" in singled) { "'only' vanished from the reading: '$singled'" }

        val struck = readingOf("landmass", "except", "blackstone")
        check("except blackstone" in struck) { "'except' vanished from the reading: '$struck'" }
    }

    /** A rung is bound to one term, so the reading has to put it back on that term and no other. */
    test("a rung is said against the thing it counts") {
        val reading = readingOf("landmass", "basalt", "and", "teeming", "slate")
        check(reading == "landmass of basalt and teeming slate.") { "read back as '$reading'" }
    }

    /**
     * **It prettifies; it never launders** (§4.3.1). Prose that quietly smoothed over a page the Art could
     * not read would be the exact "wrong world, no signal" failure that rejecting ambiguity exists to
     * prevent — so an unreadable page must be absent from the prose and present in [Sentence.unreadable],
     * where the caller shows it struck through.
     */
    test("an unread page never reaches the prose") {
        val read = Grammar.read(vocabulary, listOf("age", "landmass", "zzzznotaword", "basalt"))
        val reading = Readout.of(read)
        check("zzzznotaword" !in reading) { "an unreadable page was laundered into the prose: '$reading'" }
        check("zzzznotaword" in read.unreadable) { "an unreadable page went unreported: ${read.unreadable}" }
        check(reading == "landmass of basalt.") { "the rest of the book did not survive: '$reading'" }
    }

    /**
     * Design §2: the pen never refuses, so neither may the reading of it. Every shape of nonsense the
     * parser accepts has to come back as *something* rather than throwing on the way to the writer.
     */
    test("the readout never refuses") {
        val nonsense = listOf(
            emptyList(),
            listOf("and"),
            listOf("only"),
            listOf("teeming"),
            listOf("zzzz", "yyyy"),
            listOf("basalt", "and"),
            listOf("and", "and", "and"),
            listOf("except", "and", "only"),
            listOf("teeming", "and", "scarce"),
            listOf("landmass", "sea", "sky", "climate"),
            listOf("floating", "and", "and", "basalt"),
        )
        for (pages in nonsense) {
            runCatching { Readout.of(Grammar.read(vocabulary, pages)) }
                .getOrElse { failure -> error("the reading refused '${pages.joinToString(" ")}': $failure") }
        }
    }

    /** A book that aims at nothing has nothing to be `of` — its first word simply stands there. */
    test("a book that aims at nothing reads as itself") {
        check(readingOf("basalt") == "basalt.") { "'basalt' alone read back as '${readingOf("basalt")}'" }
        check(readingOf("floating", "basalt") == "floating of basalt.") {
            "an unaimed book read back as '${readingOf("floating", "basalt")}'"
        }
    }

    /**
     * **The two readings are one sentence.** What a book says and what `/age write` prints differ in
     * exactly one thing — what a page is *called* — because a word is a name a pack translates where the
     * prose around it is English by design (§4.1). Anything else drifting apart would mean a writer's book
     * and their command disagreed about their own book.
     *
     * Both come off the same columns, which is the point: a book sets those columns one above the other,
     * so a difference here would be a difference a reader could see on the page.
     */
    test("what a book says is the same sentence, in the reader's own words") {
        val books = listOf(
            listOf("landmass", "floating", "basalt"),
            listOf("landmass", "packed_ice", "and", "slate", "sea", "molten"),
            listOf("beautiful", "landmass", "only", "basalt"),
            listOf("landmass", "starless"),
        )
        for (pages in books) {
            val sentence = Grammar.read(vocabulary, listOf("age") + pages)
            val printed = Readout.of(sentence)
            // Offline there is no language file, so a translatable name falls back to its title-cased id.
            // Casing and the `_` a name loses are the whole of the difference, and normalising them away
            // is what leaves the sentence itself to be compared.
            val spoken = Readout.asProse(Readout.columnsOf(sentence)).string
            check(spoken.plainly() == printed.plainly()) {
                "'${pages.joinToString(" ")}' reads as '$printed' and says '$spoken'"
            }
        }
    }

    /**
     * **One page, one column** — which is the whole of what a book teaches by. A reader sees a word they
     * cannot read set over a word they can, so a column holding two of the writer's pages, or a page
     * holding no column, is a lesson in something that is not the language.
     *
     * The particles the Art supplies get columns too, and that is deliberate: a reader who never saw `of`
     * written could never learn it, and it is the commonest word on the page.
     */
    test("every page the writer laid gets a column of its own") {
        val pages = listOf("landmass", "packed_ice", "and", "slate", "sea", "molten")
        val sentence = Grammar.read(vocabulary, listOf("age") + pages)
        val columns = Readout.columnsOf(sentence)
        val stood = columns.map { it.written.trimEnd(',', '.') }
        for (page in pages) {
            check(stood.count { it == page } == 1) { "'$page' stands in ${stood.count { it == page }} columns: $stood" }
        }
        // And the reading side is one column per column, in step, since they are drawn one above the other.
        check(columns.none { it.read.string.isBlank() }) { "a column had nothing to say: $columns" }
    }
})

/** A reading with nothing left of it but the words and their order. */
private fun String.plainly(): List<String> =
    lowercase().replace('_', ' ').split(Regex("[\\s,.]+")).filter { it.isNotBlank() }
