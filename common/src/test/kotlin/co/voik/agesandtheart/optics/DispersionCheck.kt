package co.voik.agesandtheart.optics

import co.voik.agesandtheart.content.RimeColour
import net.minecraft.core.BlockPos
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/** How a dispersion lens spreads light (`optics/DispersionLens`): its apertures, and how far each reaches. */
class DispersionCheck : FunSpec({
    fun fed(percent: Int) = Light(percent, RimeColour.WHITE)
    fun reachOn(aperture: Int, percent: Int) = Dispersion.reach(aperture, Dispersion.dispersed(fed(percent)))
    val narrowest = 0
    val widest = Dispersion.APERTURES - 1

    test("there are four apertures, one for each layer of a carousel's glass, each half again as wide as the last") {
        check(Dispersion.APERTURES == CAROUSEL_LAYERS) { "${Dispersion.APERTURES} apertures" }
        for (aperture in 1..widest) {
            val widening = Dispersion.spreadAt(aperture) / Dispersion.spreadAt(aperture - 1)
            check(abs(widening - EACH_LAYER) < CLOSE_ENOUGH) { "aperture $aperture widens by $widening" }
        }
    }

    test("full light in reaches 24 blocks at the narrowest, 8 at the widest, and twice as far on twice the light") {
        check(abs(reachOn(narrowest, Light.FULL) - NARROWEST_REACH) < CLOSE_ENOUGH) { "${reachOn(narrowest, Light.FULL)}" }
        check(abs(reachOn(widest, Light.FULL) - WIDEST_REACH) < CLOSE_ENOUGH) { "${reachOn(widest, Light.FULL)}" }
        for (aperture in narrowest..widest) {
            check(abs(reachOn(aperture, 2 * Light.FULL) - 2 * reachOn(aperture, Light.FULL)) < CLOSE_ENOUGH) { "aperture $aperture" }
        }
        check(reachOn(narrowest, 5 * Light.FULL) == reachOn(narrowest, 3 * Light.FULL)) { "light past three times full reached further" }
    }

    test("the aperture turns round, wider and narrower") {
        check(Dispersion.nextAperture(widest, wider = true) == narrowest)
        check(Dispersion.nextAperture(narrowest, wider = false) == widest)
    }

    test("light mixes where lenses meet, toward white, and lights nothing coloured where none") {
        check(Light.mixed(listOf(RimeColour.RED, RimeColour.BLUE)) == RimeColour.MAGENTA)
        check(Light.mixed(listOf(RimeColour.RED, RimeColour.GREEN)) == RimeColour.YELLOW)
        check(Light.mixed(listOf(RimeColour.RED, RimeColour.GREEN, RimeColour.BLUE)) == RimeColour.WHITE)
        check(Light.mixed(emptyList()) == RimeColour.WHITE)
    }

    test("a spread armed laser hurts half, and never more than a heart") {
        check(Dispersion.hurtFor(Arc(burns = false, hurts = 2f, armedBy = BlockPos.ZERO)) == 1f)
        check(Dispersion.hurtFor(Arc(burns = true, hurts = 8f, armedBy = BlockPos.ZERO)) == 2f)
    }
})

private const val CAROUSEL_LAYERS = 4
private const val EACH_LAYER = 1.5
private const val NARROWEST_REACH = 24.0
private const val WIDEST_REACH = 8.0
private const val CLOSE_ENOUGH = 1e-9
