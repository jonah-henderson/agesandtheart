package co.voik.agesandtheart.sky

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.resolved
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** Naming the aurora or the rainbow, with nothing said about it, is saying the Age has one. */
@Tags(NEEDS_REGISTRIES)
class SeenPhenomenaPagesCheck : FunSpec({

    fun claimed(phenomenon: Phenomenon, vararg pages: String): Boolean {
        val phenomena = resolved(SEED, *pages).composition.optionsFor(Aspect.PHENOMENA, 0)
        return Phenomena.claimFor(phenomena, phenomenon) != null
    }

    test("a bare aurora page brings an aurora") {
        check(claimed(Phenomenon.AURORA, "aurora")) { "`age aurora` described no curtain and the Age got none" }
    }

    test("a bare rainbow page brings a bow") {
        check(claimed(Phenomenon.RAINBOW, "rainbow")) { "`age rainbow` described no bow and the Age got none" }
    }

    test("the page still announces it behind a phenomenon that closes its own clause") {
        check(claimed(Phenomenon.AURORA, "tempest", "landmass", "aurora")) { "the aurora was lost behind a tempest" }
    }

    test("a page the Art laid itself does not announce anything") {
        check(!claimed(Phenomenon.RAINBOW, "tempest", "landmass", "aurora")) {
            "the Art's own filler `rainbow` page brought a bow nobody wrote"
        }
    }

    test("a phenomenon beside a trailing term reads as laid") {
        val row = listOf("age", "blue", "aurora", "tempest", "zombie")
        check(vocabulary.read(row, 0L)?.readsAsLaid == true) { "`tempest zombie` was refused and the book repaired" }
    }
}) {
    private companion object {
        const val SEED = 0x5EEDL
    }
}
