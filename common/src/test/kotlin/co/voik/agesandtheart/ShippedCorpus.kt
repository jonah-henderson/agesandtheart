package co.voik.agesandtheart

import co.voik.agesandtheart.age.language.Reading
import co.voik.agesandtheart.age.resolution.Resolution
import co.voik.agesandtheart.age.word.Vocabulary

/**
 * The vocabulary we ship, loaded once per JVM for every spec that reads it.
 *
 * Everything here is `by lazy` or a function, so a spec that imports it pays for the registries only when a
 * test runs — a spec using it still needs `@Tags(NEEDS_REGISTRIES)`.
 */
object ShippedCorpus {

    /** The shipped corpus over vanilla's worldgen registries, with nothing having failed to load. */
    val vocabulary: Vocabulary by lazy {
        Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen).also {
            check(it.problems.isEmpty()) { "the corpus would not load: ${it.problems}" }
        }
    }

    /** A book, read — failing where the row has no `age` page, which is a fixture bug. */
    fun read(pages: List<String>, seed: Long = ANY_SEED): Reading =
        vocabulary.read(pages, seed) ?: error("not a book: $pages")

    /** For a book read where nothing depends on the draw. */
    const val ANY_SEED = 0L

    /**
     * A book of [pages], resolved at [seed] — with an `age` page after them, unless [pages] lays its own, so a
     * fixture's pages are taken as they would be in a book: `frozen climate` aimed, `beautiful` laid bare.
     */
    fun resolved(seed: Long, vararg pages: String): Resolution {
        val book = if (NUCLEUS in pages) pages.toList() else listOf(*pages, NUCLEUS)
        return vocabulary.resolve(book, seed) ?: error("not a book: $book")
    }

    private const val NUCLEUS = "age"
}
