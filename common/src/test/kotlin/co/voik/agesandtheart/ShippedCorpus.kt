package co.voik.agesandtheart

import co.voik.agesandtheart.age.word.Resolution
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Sentence

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

    /** A book, read — null being a row that forgot the `age` page, which is a fixture bug (§4.3.1). */
    fun read(pages: List<String>): Sentence = Grammar.read(vocabulary, pages) ?: error("not a book: $pages")

    /**
     * A book of [pages], resolved at [seed] — after an `age` page, unless [pages] lays its own, which is how
     * a fixture writes a word bare on the Age (`"beautiful", "age"`) rather than after it.
     */
    fun resolved(seed: Long, vararg pages: String): Resolution {
        val book = if (NUCLEUS in pages) pages.toList() else listOf(NUCLEUS, *pages)
        return Resolver.resolve(vocabulary, read(book), seed)
    }

    private const val NUCLEUS = "age"
}
