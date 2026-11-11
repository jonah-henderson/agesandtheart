package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Terrain
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The geometric landform has no page of its own, and each word for one of its shapes sets it in that shape. The words
 * for an arrangement lay out its shapes, or a formation's, and `sparse` and `dense` reach the layout through them.
 */
@Tags(NEEDS_REGISTRIES)
class GeometricWordsCheck : FunSpec({

    fun composed(vararg pages: String): AgeComposition = ShippedCorpus.resolved(SEED, "age", *pages).composition

    fun landSays(composition: AgeComposition, parameter: Terrain.() -> String): List<String>? {
        val geometric = composition.terrains.indexOf(Terrain.GEOMETRIC)
        return composition.optionsFor(Aspect.TERRAIN, geometric).chosen[Terrain.GEOMETRIC.parameter()]
    }

    fun spacingOf(composition: AgeComposition): Double? =
        composition.optionsFor(Aspect.TERRAIN, composition.terrains.indexOf(Terrain.GEOMETRIC)).steer(Terrain.SPACING, SEED)

    test("each shape word sets the geometric landform in its configuration") {
        val shapes = mapOf(
            "pyramidal" to "pyramids", "spiked" to "spikes", "arched" to "arches", "ringed" to "rings",
            "pitted" to "pits", "bouldered" to "boulders", "spired" to "obelisks", "hoodooed" to "pedestals",
        )
        for ((word, formation) in shapes) {
            val composition = composed(word, "landmass")
            check(composition.terrains == listOf(Terrain.GEOMETRIC)) { "'$word landmass' seated ${composition.terrains}" }
            val shape = landSays(composition) { Terrain.SHAPE.name }
            check(shape == listOf("agesandtheart:$formation")) { "'$word landmass' set the shape $shape" }
        }
    }

    test("two shape words joined by and build one plain of both shapes") {
        val composition = composed("pyramidal", "and", "spired", "landmass")
        check(composition.terrains == listOf(Terrain.GEOMETRIC)) { "'pyramidal and spired landmass' seated ${composition.terrains}" }
        val shapes = composition.optionsFor(Aspect.TERRAIN, 0).allOf(Terrain.SHAPE).toSet()
        check(shapes == setOf("agesandtheart:pyramids", "agesandtheart:obelisks")) { "'pyramidal and spired landmass' set $shapes" }
    }

    test("the geometric landform has no word of its own") {
        check(Terrain.GEOMETRIC.writtenWordFor == null) { "the geometric landform has a page of its own" }
    }

    test("gridded, concentric and scattered lay the shapes out") {
        for ((word, arrangement) in listOf("gridded" to "grid", "concentric" to "rings", "scattered" to "varied")) {
            val laid = landSays(composed(word, "pyramidal", "landmass")) { Terrain.ARRANGEMENT.name }
            check(laid == listOf(arrangement)) { "'$word pyramidal landmass' laid the pyramids out $laid" }
        }
    }

    test("sparse and dense reach a grid through the word for it") {
        val sparse = spacingOf(composed("sparse", "gridded", "pyramidal", "landmass"))
        val dense = spacingOf(composed("dense", "concentric", "pyramidal", "landmass"))
        check(sparse != null && sparse > 0.0) { "a sparse grid of pyramids was spaced $sparse" }
        check(dense != null && dense < 0.0) { "dense rings of pyramids were spaced $dense" }
    }

    test("a formation takes its arrangement and spacing in its clause") {
        val minted = composed("sparse", "gridded", "obelisks").optionsFor(Aspect.FEATURES, 0)
            .allSpelled(Features.PLACES.name).map(Claim::read).single { it.value == "agesandtheart:obelisks" }
        check(minted.arrangement == "grid") { "sparse gridded obelisks were laid out ${minted.arrangement}" }
        check((minted.spacing ?: 0.0) > 0.0) { "sparse gridded obelisks were spaced ${minted.spacing}" }
        check(Claim.read(minted.spelled()).spelled() == minted.spelled()) { "the claim did not read back as itself: ${minted.spelled()}" }
    }

    test("sparse and dense argue") {
        val flaws = ShippedCorpus.resolved(SEED, "age", "sparse", "dense", "gridded", "pyramidal", "landmass").instability.flaws
        check(flaws.isNotEmpty()) { "a sparse dense grid came out coherent" }
    }
})

private const val SEED = 7L
