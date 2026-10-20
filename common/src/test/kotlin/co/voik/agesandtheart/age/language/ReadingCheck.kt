package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.language.ShippedLexicon.read
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.age.resolution.Members
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** A row of pages read into what lands where, by the world model's own examples (`the-world-model.md` §3). */
@Tags(NEEDS_REGISTRIES)
class ReadingCheck : FunSpec({

    fun Reading.fateOf(page: Int): Fate = pages[page].fate

    fun Reading.landedBy(page: Int): List<Landing> = landings.filter { it.page == page }

    fun Landing.sets(parameter: String) = effects.any { it is Effect.Sets && it.parameter.name == parameter }

    test("a book with no `age` page does not read, and a page that is no word is unread") {
        check(Reader.read(ShippedLexicon.lexicon, listOf("frozen", "climate"), 0L) == null) { "a book read with no age" }
        check(read("age xyzzy").fateOf(1) == Fate.Unread) { "xyzzy was read as something" }
    }

    test("a page looks right for the first word that takes any of its meanings, and lands nothing elsewhere") {
        val book = read("age strong moon strong monsters")
        check(book.fateOf(1) == Fate.TakenBy(2)) { "the first strong went ${book.fateOf(1)}\n${book.readout}" }
        check(book.fateOf(3) == Fate.TakenBy(4)) { "the second strong went ${book.fateOf(3)}\n${book.readout}" }
        val tides = book.landedBy(1)
        check(tides.isNotEmpty() && tides.all { it.place.aspect == Aspect.MOON }) { "strong moon landed ${tides.map { it.place }}" }
        check(tides.any { it.sets("pull") } && tides.none { it.sets("strength") }) { "strong moon is not a tide\n${book.readout}" }
        val monsters = book.landedBy(3)
        check(monsters.all { it.place.aspect == Aspect.SPAWNS } && monsters.any { it.sets("strength") }) { book.readout }
    }

    test("a waiting page stops at the first word that takes anything, and one with nothing to assert is laid on the Age") {
        val book = read("age colossal cyan waters sunless trees")
        check(book.fateOf(2) == Fate.TakenBy(3)) { "cyan went ${book.fateOf(2)}\n${book.readout}" }
        check(book.fateOf(1) == Fate.Moved) { "colossal went ${book.fateOf(1)}\n${book.readout}" }
        check(book.fateOf(4) == Fate.AssertedAlone) { "sunless went ${book.fateOf(4)}\n${book.readout}" }
        check(book.landedBy(4).all { it.place.aspect == Aspect.SUN }) { "sunless said something besides the sun" }
        check(book.moved.map { it.written } == listOf("colossal")) { "the pages moved are ${book.moved}" }
    }

    test("several pages wait together, and a minting takes a substance as what it is made of") {
        val book = read("age colossal gold_block obelisks")
        check(book.fateOf(1) == Fate.TakenBy(3) && book.fateOf(2) == Fate.TakenBy(3)) { book.readout }
        val madeOf = book.landedBy(2).flatMap { it.effects }.toSet()
        check(madeOf == setOf(Effect.MadeOf("minecraft:gold_block"))) { "the obelisks are made of $madeOf" }
        check(book.landedBy(1).single().sets("size")) { "the obelisks are not colossal\n${book.readout}" }
    }

    test("joined members take what waits before them together, under one join") {
        val book = read("age colossal trees and monsters")
        val sized = book.landedBy(1)
        check(book.fateOf(1) == Fate.TakenBy(2)) { "colossal went ${book.fateOf(1)}\n${book.readout}" }
        check(sized.map { it.place.aspect }.toSet() == setOf(Aspect.FEATURES, Aspect.SPAWNS)) { book.readout }
        check(read("age colossal trees and villages").fateOf(1) == Fate.TakenBy(2)) { "villages, which take no size, let it fall" }
        check(sized.map { it.joined }.distinct().singleOrNull() != null) { "the two sizes are not joined: $sized" }
    }

    test("`in` sites the last word that took pages and what it took, and nothing before it") {
        val book = read("frozen age beautiful plants in jungle")
        val beautiful = book.landedBy(2)
        check(beautiful.isNotEmpty() && beautiful.all { it.sitedIn?.path == "jungle" }) { "beautiful plants are not sited\n${book.readout}" }
        check(book.landedBy(0).none { it.sitedIn != null }) { "the frozen Age was sited in the jungle" }
    }

    test("a deserted village is abandoned, and not struck out by what deserted says of other structures") {
        val book = read("age deserted villages")
        val deserted = book.landedBy(1)
        check(deserted.any { it.sets("abandoned") }) { "deserted villages are not abandoned\n${book.readout}" }
        check(deserted.none { landing -> landing.effects.any { it is Effect.Excludes } }) { "deserted struck something" }
    }

    test("a member named on its own is asked for, and one described is not, unless an amount is asked or it must be brought") {
        fun namedBy(book: Reading, page: Int) =
            book.landedBy(page).any { landing -> landing.effects.any { it is Effect.Chooses || it is Effect.Restricts } }
        check(namedBy(read("age villages"), 1)) { "villages named alone asks for none" }
        check(!namedBy(read("age deserted villages"), 2)) { "deserted villages asks for more villages" }
        check(namedBy(read("age teeming villages"), 2)) { "teeming villages asks for none" }
        check(namedBy(read("age frequent tempest"), 2)) { "frequent tempest does not bring the tempest" }
    }

    test("a quantifier lands on what counts, and `only` leads the member after it") {
        val stars = read("age teeming stars").landedBy(1)
        check(stars.any { landing -> Effect.Abundance(4.0) in landing.effects }) { "teeming stars counted nothing" }
        val only = read("age only villages").landedBy(2)
        check(only.isNotEmpty() && only.all { it.polarity == Polarity.ONLY }) { "only villages came out as $only" }
        check(read("age and").fateOf(1) == Fate.Impossible) { "an `and` with nothing either side bound something" }
    }

    test("each page a population takes is a body of its own") {
        val book = read("age red sun colossal sun")
        val bodies = book.landings.filter { it.place.aspect == Aspect.SUN }.map { it.place.members }.toSet()
        check(bodies == setOf(Members.Body(0), Members.Body(1))) { "the suns are $bodies\n${book.readout}" }
    }

    test("a word laid bare lands at least once, never past a dial only an aimed word turns, and varies with the seed") {
        val sizedPlaces = (1L..40L).map { seed ->
            val book = read("colossal age", seed)
            val sized = book.landedBy(0).filter { it.sets("size") }
            check(sized.isNotEmpty()) { "colossal age at seed $seed is not colossal anywhere\n${book.readout}" }
            check(sized.none { it.place.aspect == Aspect.SPAWNS }) { "colossal age made giants at seed $seed" }
            sized.map { it.place.aspect }.toSet()
        }
        check(sizedPlaces.distinct().size > 1) { "every seed made the same things colossal" }
        check(read("colossal age", 7L) == read("colossal age", 7L)) { "the same book at the same seed read differently" }
    }
})
