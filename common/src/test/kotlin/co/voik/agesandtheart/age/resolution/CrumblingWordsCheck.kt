package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.language.ShippedLexicon.resolved
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** Which words crumble an Age's features (`Features.CRUMBLE`), and which leave them whole. */
@Tags(NEEDS_REGISTRIES)
class CrumblingWordsCheck : FunSpec({

    fun crumbles(sentence: String, seed: Long) =
        resolved(sentence, seed).composition.optionsFor(Aspect.FEATURES).chosen.containsKey(Features.CRUMBLE.name)

    test("eroded, worn and ancient features crumble, and the land is not made eroded by it") {
        for (seed in SEEDS) {
            for (sentence in listOf("age eroded features", "age worn features", "age ancient features")) {
                check(crumbles(sentence, seed)) { "'$sentence' at $seed left the features whole" }
            }
            check(Terrain.ERODED !in resolved("age eroded features", seed).composition.terrains) {
                "'eroded features' at $seed made the land eroded"
            }
        }
    }

    test("the eroded land is the landform, and crumbles nothing") {
        for (seed in SEEDS) {
            for (sentence in listOf("age eroded landmass", "age eroded")) {
                val composition = resolved(sentence, seed).composition
                check(composition.terrains == listOf(Terrain.ERODED)) { "'$sentence' at $seed stood on ${composition.terrains}" }
                check(!crumbles(sentence, seed)) { "'$sentence' at $seed crumbled the features" }
            }
        }
    }

    test("worn laid bare crumbles the features now and then, and the land's own wear words never do") {
        check(SEEDS.any { crumbles("worn age", it) }) { "'worn age' never crumbled the features" }
        check(!SEEDS.all { crumbles("worn age", it) }) { "'worn age' crumbled the features every time" }
        for (seed in SEEDS) {
            for (sentence in listOf("pristine age", "ordered age", "uniform age", "age worn landmass")) {
                check(!crumbles(sentence, seed)) { "'$sentence' at $seed crumbled the features" }
            }
        }
    }
})

private val SEEDS = 0L..<12L
