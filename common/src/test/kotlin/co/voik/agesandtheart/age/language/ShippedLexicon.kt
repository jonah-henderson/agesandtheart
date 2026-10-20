package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.resolution.Resolution
import co.voik.agesandtheart.age.resolution.WorldFacts

/** The shipped vocabulary as the reading and the resolver see it. Needs `@Tags(NEEDS_REGISTRIES)`. */
object ShippedLexicon {

    val lexicon: Lexicon get() = ShippedCorpus.vocabulary.lexicon

    val world: WorldFacts get() = ShippedCorpus.vocabulary

    /** A book of [pages] read and resolved at [seed], which must read. */
    fun resolved(pages: String, seed: Long = 0L): Resolution =
        requireNotNull(Writing.resolve(lexicon, world, pages.split(" "), seed)) { "'$pages' does not read" }

    /** A book of [pages] read at [seed], which must read. */
    fun read(pages: String, seed: Long = 0L): Reading =
        requireNotNull(Reader.read(lexicon, pages.split(" "), seed)) { "'$pages' does not read" }
}
