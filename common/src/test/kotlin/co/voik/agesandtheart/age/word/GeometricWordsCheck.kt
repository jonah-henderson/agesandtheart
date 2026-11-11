package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Terrain
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The geometric landform has no page of its own, and each word for one of its shapes sets it in that shape. */
@Tags(NEEDS_REGISTRIES)
class GeometricWordsCheck : FunSpec({

    test("pyramidal sets the geometric landform in its pyramid configuration") {
        val composition = ShippedCorpus.resolved(SEED, "age", "pyramidal", "landmass").composition
        check(composition.terrains == listOf(Terrain.GEOMETRIC)) { "'pyramidal landmass' seated ${composition.terrains}" }
        val shape = composition.optionsFor(Aspect.TERRAIN, 0).chosen[Terrain.SHAPE.name]
        check(shape == listOf("agesandtheart:pyramids")) { "'pyramidal landmass' set the shape $shape" }
    }

    test("the geometric landform has no word of its own") {
        check(Terrain.GEOMETRIC.writtenWordFor == null) { "the geometric landform has a page of its own" }
    }
})

private const val SEED = 7L
