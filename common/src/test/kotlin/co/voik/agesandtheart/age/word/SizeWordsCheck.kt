package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.read
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.Register
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **Two sizes in one book are two sizes** where they are said of different things (Jonah, 2026-09-29):
 * `size` is one name every sized aspect owns, so `small landmass` and `colossal rainbow` bound different ones
 * and never meet. Said of the same thing they still argue, which is the half that must not be lost.
 */
@Tags(NEEDS_REGISTRIES)
class SizeWordsCheck : FunSpec({

    fun opposed(vararg pages: String) = Resolver.resolve(vocabulary, read(listOf("age", *pages)), SEED)
        .instability.flaws.filter { it.register == Register.OPPOSED }

    test("sizes said of different things coexist") {
        val apart = opposed("small", "insular", "landmass", "rainbows", "phenomena", "colossal", "rainbow")
        check(apart.isEmpty()) { "a small island under a colossal rainbow was charged: $apart" }
    }

    test("sizes said of one thing still argue") {
        val together = Resolver.resolve(vocabulary, read(listOf("age", "small", "colossal", "insular", "landmass")), SEED)
        check(together.instability.flaws.isNotEmpty()) { "a small colossal island came out coherent" }
    }
})

private const val SEED = 7L
