package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Prism
import co.voik.agesandtheart.age.language.Fate
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * `rainbow` brings bows, alone or before `phenomena`, and old books' `rainbows` with it; said of anything whose
 * colours may turn it means them turning, and brings no bows.
 */
@Tags(NEEDS_REGISTRIES)
class RainbowWordsCheck : FunSpec({

    fun composed(vararg pages: String): AgeComposition = ShippedCorpus.resolved(SEED, "age", *pages).composition

    fun bowsIn(composition: AgeComposition) = Phenomena.claimFor(composition.optionsFor(Aspect.PHENOMENA, 0), Phenomenon.RAINBOW) != null

    test("rainbow brings bows, alone and before phenomena, and rainbows reads as it") {
        for (row in listOf(listOf("rainbow"), listOf("rainbow", "phenomena"), listOf("rainbows"), listOf("rainbows", "phenomena"))) {
            check(bowsIn(composed(*row.toTypedArray()))) { "'${row.joinToString(" ")}' brought no bows" }
        }
        val fate = ShippedCorpus.read(listOf("age", "rainbow", "phenomena")).pages[2].fate
        check(fate == Fate.Names(1)) { "'phenomena' after 'rainbow' came to $fate, not naming its part" }
    }

    test("rainbow said of what may turn its colours turns them, and brings no bows") {
        for ((page, aspect) in listOf("sky" to Aspect.SKY, "aurora" to Aspect.AURORA, "clouds" to Aspect.CLOUD)) {
            val composition = composed("rainbow", page)
            check(composition.optionsFor(aspect).isTrue(Prism.COLOURS_CYCLING)) { "'rainbow $page' did not turn its colours" }
            check(!bowsIn(composition)) { "'rainbow $page' brought bows" }
        }
    }
})

private const val SEED = 20261111L
