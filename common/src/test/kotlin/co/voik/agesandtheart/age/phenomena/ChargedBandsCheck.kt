package co.voik.agesandtheart.age.phenomena

import io.kotest.core.spec.style.FunSpec

/**
 * **That a body put in the highest band is still there a second later.**
 *
 * This is the arithmetic behind the fault worth remembering from this feature: a drifting body forgets
 * itself when no player is within a radius, the radius is measured in three dimensions, and the top band
 * sits at the build limit — so a plausible-sounding number quietly deleted the richest tier a second after
 * it spawned, which is the tier the whole ladder climbs towards. Nothing in a screenshot says so; the sky
 * simply has two bands in it and the third looks unlucky.
 *
 * Two numbers chosen in different files for different reasons, and only their relationship is wrong.
 */
class ChargedBandsCheck : FunSpec({

    test("a body in the top band is not forgotten by a player standing under it") {
        val fromTheGround = ChargedBands.HIGHEST - ChargedBands.SEA_LEVEL
        check(ChargedBands.FORGOTTEN_BEYOND >= fromTheGround) {
            "the top band is $fromTheGround above sea level and a body is forgotten past " +
                "${ChargedBands.FORGOTTEN_BEYOND}, so the richest tier deletes itself on the first look"
        }
    }

    /**
     * And with room to walk about in. A player has to be able to leave the spot directly beneath a body
     * without it vanishing, or the top band exists only for somebody standing perfectly still under it.
     */
    test("and there is room to move about under it") {
        val fromTheGround = ChargedBands.HIGHEST - ChargedBands.SEA_LEVEL
        val sideways = ChargedBands.FORGOTTEN_BEYOND * ChargedBands.FORGOTTEN_BEYOND - fromTheGround * fromTheGround
        check(sideways >= ROOM_TO_WALK * ROOM_TO_WALK) {
            "a player may stray only ${Math.sqrt(sideways)} blocks from under the top band before a body " +
                "there is forgotten, which is not enough to explore with"
        }
    }
}) {
    private companion object {
        /** Far enough that looking up, walking over and looking again is the same body. */
        private const val ROOM_TO_WALK = 64.0
    }
}
