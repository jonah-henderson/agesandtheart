package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.Terrain
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **A word laid bare on the Age sizes no creature, and sizes the land only if it is a size word.** A `size`
 * claimed under its bare name reaches every aspect owning one, so a word whose pool drew one made a frozen Age of
 * minuscule creatures on minuscule land, and a rich one of giants.
 */
@Tags(NEEDS_REGISTRIES)
class BareSizeCheck : FunSpec({

    test("no authored word laid bare on the Age sizes its creatures, or its land unless it says a size") {
        val strays = ShippedCorpus.vocabulary.authoredWords.flatMap { word ->
            val saysASizeOfItsOwn = Terrain.SIZE.name in word.sets || Terrain.SIZE.name in word.bends
            SEEDS.flatMap { seed ->
                val composition = runCatching { ShippedCorpus.resolved(seed, word.name, "age").composition }.getOrNull()
                    ?: return@flatMap emptyList()
                val sizedCreatures = composition.optionsFor(Aspect.SPAWNS, 0).chosen.containsKey(Spawns.SIZE.name)
                val sizedLand = composition.optionsFor(Aspect.TERRAIN, 0).chosen.containsKey(Terrain.SIZE.name)
                listOfNotNull(
                    "${word.name} sized the creatures at seed $seed".takeIf { sizedCreatures },
                    "${word.name} sized the land at seed $seed".takeIf { sizedLand && !saysASizeOfItsOwn },
                )
            }.distinct().take(1)
        }
        check(strays.isEmpty()) { "laid bare on the Age: $strays" }
    }
})

/** Enough draws that a pool facet taken one time in two comes up. */
private val SEEDS = 1L..8L
