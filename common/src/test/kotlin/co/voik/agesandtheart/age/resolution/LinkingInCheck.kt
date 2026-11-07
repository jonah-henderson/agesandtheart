package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.resolved
import co.voik.agesandtheart.age.Register
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A `link in` what the Age plainly cannot hold is charged, against the words that ruled it out; one in what may be
 * there is not, whether or not a search would find it.
 */
@Tags(NEEDS_REGISTRIES)
class LinkingInCheck : FunSpec({

    fun anchorOf(book: List<String>) = book[book.indexOf("link") + 2]

    /** The incoherence charged against [book]'s anchor, at each seed. */
    fun linkFlaws(book: List<String>) = SEEDS.map { seed ->
        resolved(seed, *book.toTypedArray()).instability.flaws
            .filter { it.register == Register.INCOHERENT && anchorOf(book) in it.words }
    }

    /** Each book, with the word that ruled its anchor out. */
    val ruledOut = listOf(
        listOf("only", "desert", "biomes", "link", "in", "jungle") to "desert",
        listOf("gentle", "landmass", "link", "in", "cratered") to "gentle",
        listOf("empty", "sea", "link", "in", "sea") to "empty",
        listOf("no", "villages", "structures", "link", "in", "villages") to "villages",
        listOf("no", "zombie", "link", "in", "zombie") to "zombie",
    )

    val mayBeThere = listOf(
        listOf("only", "desert", "biomes", "link", "in", "desert"),
        listOf("gentle", "landmass", "link", "in", "gentle"),
        listOf("overworld", "landmass", "link", "in", "sea"),
        listOf("overworld", "landmass", "link", "in", "villages"),
        listOf("overworld", "landmass", "link", "in", "jungle"),
    )

    for ((book, rulingWord) in ruledOut) {
        test("a link in ${anchorOf(book)} is charged in '${book.joinToString(" ")}'") {
            val flaws = linkFlaws(book)
            check(flaws.all { it.size == 1 }) { "the link went uncharged, or was charged twice: $flaws" }
            check(flaws.all { rulingWord in it.single().words }) {
                "the charge did not name what ruled it out: $flaws"
            }
        }
    }

    for (book in mayBeThere) {
        test("a link in ${anchorOf(book)} is not charged in '${book.joinToString(" ")}'") {
            val flaws = linkFlaws(book)
            check(flaws.all { it.isEmpty() }) { "a link in what may be there was charged: $flaws" }
        }
    }
}) {
    private companion object {
        val SEEDS = listOf(1L, 2L, 3L)
    }
}
