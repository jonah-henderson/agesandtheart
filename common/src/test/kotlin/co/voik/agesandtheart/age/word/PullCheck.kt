package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Gravity
import co.voik.agesandtheart.age.aspect.SkyBodies
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That `strong` and `weak` reach the gravity and the moon through the one name `pull`, that the clause decides
 * which, and that laid bare they never touch the gravity.
 */
@Tags(NEEDS_REGISTRIES)
class PullCheck : FunSpec({

    fun bare(word: String, seed: Long): AgeComposition =
        Resolver.resolve(ShippedCorpus.vocabulary, ShippedCorpus.read(listOf(word, "age")), seed).composition

    fun aimed(seed: Long, vararg pages: String): AgeComposition = ShippedCorpus.resolved(seed, *pages).composition

    fun AgeComposition.gravity(): Double? =
        optionsFor(Aspect.GRAVITY, 0).steer(Gravity.PULL, 0L)?.let(Gravity::factorAt)

    fun AgeComposition.moonsPull(): Double = SkyBodies.pullOf(optionsFor(Aspect.MOON, 0), 0L)

    test("strong gravity is heavier than a jump can clear a block under, and weak gravity lighter") {
        for (seed in SEEDS) {
            val heavy = aimed(seed, "strong", "gravity").gravity()
            val light = aimed(seed, "weak", "gravity").gravity()
            check(heavy != null && heavy in HEAVIEST_STEP..TWICE) { "seed $seed: 'strong gravity' pulled $heavy" }
            check(light != null && light in A_THIRD..LIGHTEST_STEP) { "seed $seed: 'weak gravity' pulled $light" }
        }
    }

    test("a strong moon pulls the tide hard and leaves the gravity alone") {
        for (seed in SEEDS) {
            val age = aimed(seed, "strong", "moon")
            check(age.moonsPull() >= STRONG_TIDE) { "seed $seed: 'strong moon' pulled the tide at ${age.moonsPull()}" }
            check(age.gravity() == null) { "seed $seed: 'strong moon' reached the gravity" }
        }
    }

    test("a strong or weak Age never touches its gravity") {
        for (seed in SEEDS) {
            check(bare("strong", seed).gravity() == null) { "seed $seed: a bare 'strong' set the gravity" }
            check(bare("weak", seed).gravity() == null) { "seed $seed: a bare 'weak' set the gravity" }
        }
    }

    test("the axis' ends are strange gravity's last steps") {
        check(Gravity.factorAt(1.0) == TWICE) { "the top of the axis pulled ${Gravity.factorAt(1.0)}" }
        check(Gravity.factorAt(-1.0) == A_THIRD) { "the bottom of the axis pulled ${Gravity.factorAt(-1.0)}" }
        check(Gravity.factorAt(0.0) == 1.0) { "the middle of the axis pulled ${Gravity.factorAt(0.0)}" }
    }
})

private val SEEDS = 0L..<50L
private const val TWICE = 2.0
private const val A_THIRD = 1.0 / 3.0

/** Strange gravity's first steps either way, which a written pull starts from. */
private const val HEAVIEST_STEP = 1.25
private const val LIGHTEST_STEP = 1.0 / 1.4

/** How hard a moon `strong` describes pulls at the least, nought to one. */
private const val STRONG_TIDE = 0.6
