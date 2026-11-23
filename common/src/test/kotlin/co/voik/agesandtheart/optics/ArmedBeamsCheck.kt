package co.voik.agesandtheart.optics

import io.kotest.core.spec.style.FunSpec

/** What a laser armed by arc crystal hurts, by the crystal joined to its emitter (`optics/ArmedBeams`). */
class ArmedBeamsCheck : FunSpec({
    val pileSizes = 1..MOST_IN_A_PILE

    test("more crystal never hurts less, plain or charged") {
        for (blocks in pileSizes.drop(1)) {
            check(ArmedBeams.hurtFor(blocks, isCharged = false) >= ArmedBeams.hurtFor(blocks - 1, isCharged = false)) {
                "a plain pile of $blocks hurt less than one of ${blocks - 1}"
            }
            check(ArmedBeams.hurtFor(blocks, isCharged = true) >= ArmedBeams.hurtFor(blocks - 1, isCharged = true)) {
                "a charged pile worth $blocks hurt less than one worth ${blocks - 1}"
            }
        }
        check(ArmedBeams.hurtFor(2, isCharged = false) > ArmedBeams.hurtFor(1, isCharged = false)) {
            "a second plain block added nothing"
        }
    }

    test("no plain pile hurts more than the smallest charged one") {
        val leastCharged = ArmedBeams.hurtFor(CHARGED_BLOCK_IS_WORTH, isCharged = true)
        for (blocks in pileSizes) {
            check(ArmedBeams.hurtFor(blocks, isCharged = false) <= leastCharged) {
                "a plain pile of $blocks hurt more than one charged block"
            }
        }
    }
})

private const val MOST_IN_A_PILE = 512
private const val CHARGED_BLOCK_IS_WORTH = 2
