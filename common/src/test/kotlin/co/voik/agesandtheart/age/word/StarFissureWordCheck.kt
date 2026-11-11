package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Structures
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** `star_fissure` is the fissures' own word: `no` strikes them, a rung says how many, and nothing else reaches them. */
@Tags(NEEDS_REGISTRIES)
class StarFissureWordCheck : FunSpec({

    fun built(vararg pages: String) =
        ShippedCorpus.resolved(SEED, "age", *pages).composition.optionsFor(Aspect.STRUCTURES).skewOn(Structures.BUILT)

    test("no star_fissure strikes the fissures, and teeming star_fissure asks for more of them") {
        check(FISSURE in built("no", "star_fissure").struck) { "'no star_fissure' struck ${built("no", "star_fissure").struck}" }
        val asked = built("teeming", "star_fissure").wanted.firstOrNull { it.id.toString() == FISSURE }
        check(asked != null && asked.density > 1.0) { "'teeming star_fissure' asked for $asked" }
    }

    test("nothing said of the structures at large names the fissures") {
        for (row in listOf(listOf("no", "structures"), listOf("only", "villages"))) {
            val said = built(*row.toTypedArray())
            check(FISSURE !in said.struck && said.wanted.none { it.id.toString() == FISSURE }) {
                "'${row.joinToString(" ")}' named the fissures: $said"
            }
        }
    }
})

private const val SEED = 20261111L
private const val FISSURE = "agesandtheart:star_fissure"
