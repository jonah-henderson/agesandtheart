package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.resolution.Resolution
import co.voik.agesandtheart.age.resolution.WorldFacts

/** Books written in the words we ship, as the reader and the resolver make them. Needs `@Tags(NEEDS_REGISTRIES)`. */
object ShippedBooks {

    val world: WorldFacts get() = ShippedCorpus.vocabulary

    /** A book of [pages], spaced, read and resolved at [seed]. */
    fun resolved(pages: String, seed: Long = 0L): Resolution = written(pages, seed).resolution

    /** A book of [pages], spaced, read, drawn and resolved at [seed]. */
    fun written(pages: String, seed: Long = 0L): Written = ShippedCorpus.vocabulary.written(pages.split(" "), seed)
}
