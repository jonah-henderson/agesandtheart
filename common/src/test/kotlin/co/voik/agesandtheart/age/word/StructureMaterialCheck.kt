package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Structures
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** A block said of the structures is what they are built of, and of the members named alone where any are. */
@Tags(NEEDS_REGISTRIES)
class StructureMaterialCheck : FunSpec({

    fun structuresOf(vararg pages: String) = ShippedCorpus.resolved(SEED, *pages).composition.optionsFor(Aspect.STRUCTURES)

    test("nether bricks said of the structures build every one of them of it") {
        val structures = structuresOf("nether_bricks", "structures")
        check(structures.of(Structures.MATERIAL, member = Structures.VILLAGES) == NETHER_BRICKS) {
            "villages were built of ${structures.of(Structures.MATERIAL, member = Structures.VILLAGES)}"
        }
    }

    test("nether bricks said of the villages leave the rest as they were") {
        val structures = structuresOf("nether_bricks", "villages", "structures")
        check(structures.of(Structures.MATERIAL, member = Structures.VILLAGES) == NETHER_BRICKS) { "the villages were not built of it" }
        check(structures.of(Structures.MATERIAL, member = PYRAMIDS) == Parameter.UNCHANGED) { "the pyramids were built of it too" }
    }

    test("blue structures are built of a block drawn from the blue ones") {
        val built = structuresOf("blue", "structures").of(Structures.MATERIAL, member = Structures.VILLAGES)
        check(built != Parameter.UNCHANGED && !built.startsWith("#")) { "blue structures were built of '$built'" }
    }
})

private const val SEED = 4242L
private const val NETHER_BRICKS = "minecraft:nether_bricks"
private const val PYRAMIDS = "minecraft:desert_pyramids"
