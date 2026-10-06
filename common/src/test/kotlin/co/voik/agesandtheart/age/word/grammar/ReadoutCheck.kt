package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.read
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

    /**
     * A book, as it reads **after the page it opens with**. The nucleus is supplied here rather than written
     * into every fixture, and taken back off the front of the answer for the same reason: every book has an
     * `age` and every reading says so, and what these check is the reading of what follows it. The head is
     * pinned by two tests of its own below.
     */
    fun readingOf(vararg pages: String): String =
        Readout.of(read(listOf("age") + pages)).removePrefix("age: ")

    /**
     * The same for a book with no aiming page in it — **the beginner's book, which now closes with `age`
     * rather than opening with it** (§4.3.1). Its words are the nucleus's own modifiers and modifiers
     * lead, so "a floating, basalt Age" is written in that order. The reading still opens with the head,
     * `Readout` putting it back at the front wherever the page was laid.
     */
    fun unaimedReadingOf(vararg pages: String): String =
        Readout.of(read(pages.toList() + "age")).removePrefix("age: ")

    /**
     * **A book opens with the page it opens with.** The nucleus carries no constraint and so reaches no
     * clause, which is exactly how it came to be missing: the reading is what the script is set from, so a
     * head left out of it meant the glyph for the one page every book must have was drawn nowhere in the
     * game, and a player learning the language by reading found books never met it.
     */
    test("the page a book opens with is in its reading") {
        val reading = Readout.of(read(listOf("age", "basalt", "landmass")))
        check(reading == "age: basalt landmass.") { "the book did not open with its own head: '$reading'" }
    }

    /**
     * The particle a writer was spared, restored to show the position it was inferred into — which is the
     * whole trick (§4.3.1). A material belongs to the section it sits in, and `of` is what says so.
     */
    test("a material is attached to what it is aimed at") {
        val reading = readingOf("floating", "basalt", "landmass")
        check(reading == "floating, basalt landmass.") { "read back as '$reading'" }
    }

    /**
     * **The failure the readout exists to catch.** If the parser attached a material to the wrong section,
     * the prose says so at once — before a drop of ink is spent. Here the sea's own material must arrive
     * under the sea and not under the land, and the sea must be placed *over* against the land.
     */
    test("each section keeps its own material") {
        val reading = readingOf("floating", "basalt", "landmass", "molten", "lava", "sea")
        check(reading == "floating, basalt landmass, over molten, lava sea.") {
            "the sections blurred into each other: '$reading'"
        }
    }

    /**
     * `and` is the one page whose whole meaning is that both words stay — so a reading that flattened a
     * joined run into juxtaposition would hide the only thing it changed (§3.2).
     */
    test("a joined run stays joined") {
        val joined = readingOf("basalt", "and", "deepslate", "landmass")
        check(joined == "basalt and deepslate landmass.") { "'basalt and deepslate' read back as '$joined'" }

        val apart = readingOf("basalt", "deepslate", "landmass")
        check(" and " !in apart) { "unjoined words were read back as joined: '$apart'" }
    }

    /**
     * A word naming a preset is a claim on the aspect, not a property of another word — "riddled *of*
     * flooded" would read as one made out of the other, which is not what either page says.
     */
    test("a claim on an aspect takes no particle") {
        val reading = readingOf("riddled", "flooded", "rock")
        check(reading == "riddled, flooded rock.") { "read back as '$reading'" }
    }

    /**
     * The same rule one layer down, and the shape it was found in: `frozen arid` read back as "frozen of
     * arid", a climate made out of another climate.
     *
     * A preset-naming word is caught by steering nothing at all, but these two steer — `frozen` and `arid`
     * set the *same* two parameters, temperature and humidity, which makes them rivals rather than one
     * describing the other. What a particle claims is that the run says something about what came before
     * it; two words turning one dial say something about each other's chances instead.
     */
    test("words contending for one dial take no particle") {
        val reading = unaimedReadingOf("frozen", "arid")
        check(reading == "frozen, arid.") { "two claims on the climate read as composition: '$reading'" }
    }

    /**
     * The control for it: a material still attaches across an aiming page that turns no dial of its own,
     * which is the ordinary case the rule above must not reach.
     */
    test("a material still attaches to what turns no dial") {
        val reading = readingOf("sheer", "basalt", "landmass")
        check(reading == "sheer, basalt landmass.") { "read back as '$reading'" }
    }

    /** `only` and `except` are pages the writer laid down, and the reading has to show them. */
    test("only and no are said out loud") {
        val singled = readingOf("only", "blackstone", "landmass")
        check("only blackstone" in singled) { "'only' vanished from the reading: '$singled'" }

        val struck = readingOf("no", "blackstone", "landmass")
        check("no blackstone" in struck) { "'no' vanished from the reading: '$struck'" }
    }

    /** A rung is bound to one term, so the reading has to put it back on that term and no other. */
    test("a rung is said against the thing it counts") {
        val reading = readingOf("basalt", "and", "teeming", "deepslate", "landmass")
        check(reading == "basalt and teeming deepslate landmass.") { "read back as '$reading'" }
    }

    /**
     * **It prettifies; it never launders** (§4.3.1). Prose that quietly smoothed over a page the Art could
     * not read would be the exact "wrong world, no signal" failure that rejecting ambiguity exists to
     * prevent — so an unreadable page must be absent from the prose and present in [Sentence.unreadable],
     * where the caller shows it struck through.
     */
    test("an unread page never reaches the prose") {
        val read = read(listOf("age", "zzzznotaword", "basalt", "landmass"))
        val reading = Readout.of(read)
        check("zzzznotaword" !in reading) { "an unreadable page was laundered into the prose: '$reading'" }
        check("zzzznotaword" in read.unreadable) { "an unreadable page went unreported: ${read.unreadable}" }
        check(reading == "age: basalt landmass.") { "the rest of the book did not survive: '$reading'" }
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
            listOf("no", "and", "only"),
            listOf("teeming", "and", "scarce"),
            listOf("landmass", "sea", "firmament", "atmosphere"),
            listOf("floating", "and", "and", "basalt"),
        ).map { pages -> listOf("age") + pages }
        for (pages in nonsense) {
            runCatching { Readout.of(read(pages)) }
                .getOrElse { failure -> error("the reading refused '${pages.joinToString(" ")}': $failure") }
        }
    }

    /** A book that aims at nothing has nothing to be `of` — its first word simply stands there. */
    test("a book that aims at nothing reads as itself") {
        check(unaimedReadingOf("basalt") == "basalt.") { "'basalt' alone read back as '${unaimedReadingOf("basalt")}'" }
        check(unaimedReadingOf("floating", "basalt") == "floating, basalt.") {
            "an unaimed book read back as '${unaimedReadingOf("floating", "basalt")}'"
        }
    }
})
