package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.resolved
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A pace said beside turning colours goes where it stands: `swift prismatic sun` turns its colours faster and leaves the
 * sun's pace alone, `prismatic swift sun` is a fast sun turning at the usual pace, and `swift sun` is both.
 */
@Tags(NEEDS_REGISTRIES)
class PaceBesideColoursCheck : FunSpec({

    fun sunOf(book: String): Options = resolved(SEED, *book.split(" ").toTypedArray()).composition.optionsFor(Aspect.SUN, 0)

    fun pacesItself(sun: Options) = sun.allSpelled(PACE).isNotEmpty()

    fun pacesItsColours(sun: Options) = sun.allSpelled(COLOUR_PACE).isNotEmpty()

    test("swift before prismatic paces the colours alone") {
        val sun = sunOf("swift prismatic sun")
        check(pacesItsColours(sun) && !pacesItself(sun)) { "swift prismatic sun: ${sun.chosen}" }
    }

    test("swift after prismatic paces the sun alone") {
        val sun = sunOf("prismatic swift sun")
        check(pacesItself(sun) && !pacesItsColours(sun)) { "prismatic swift sun: ${sun.chosen}" }
    }

    test("swift with no colours turning paces both") {
        val sun = sunOf("swift sun")
        check(pacesItself(sun) && pacesItsColours(sun)) { "swift sun: ${sun.chosen}" }
    }
}) {
    private companion object {
        const val SEED = 3L
        const val PACE = "pace"
        const val COLOUR_PACE = "colour_cycle_pace"
    }
}
