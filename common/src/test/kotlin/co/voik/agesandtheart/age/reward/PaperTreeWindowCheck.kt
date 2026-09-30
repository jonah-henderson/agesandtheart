package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.ephemeris.sky.Orbit
import co.voik.ephemeris.sky.SkySpec
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That only a polar sun opens the paper tree's window: one circling at a twilight height, never setting and
 * never climbing (design §7.1.2).
 */
@Tags(NEEDS_REGISTRIES)
class PaperTreeWindowCheck : FunSpec({

    fun withTheSunOn(path: Orbit): SkySpec =
        SkySpec.VANILLA.copy(bodies = SkySpec.VANILLA.bodies.map { if (it.phase == null) it.copy(path = path) else it })

    test("vanilla's sun sets, and is not polar") {
        check(!PaperTreeWindow.isPolar(SkySpec.VANILLA)) { "vanilla's day read as a polar sun" }
    }

    test("a sun circling just over the horizon is polar") {
        val twilight = Orbit.VANILLA_SUN.copy(inclinationDegrees = POLAR, liftDegrees = JUST_OVER_THE_HORIZON)
        check(PaperTreeWindow.isPolar(withTheSunOn(twilight))) { "a twilight circle was refused" }
    }

    test("a midnight sun riding high is not twilight") {
        val high = Orbit.VANILLA_SUN.copy(inclinationDegrees = POLAR, liftDegrees = WELL_UP)
        check(!PaperTreeWindow.isPolar(withTheSunOn(high))) { "a sun well up the sky read as twilight" }
    }

    test("a sky with no sun has no twilight") {
        val sunless = SkySpec.VANILLA.copy(bodies = SkySpec.VANILLA.bodies.filter { it.phase != null })
        check(!PaperTreeWindow.isPolar(sunless)) { "a sunless sky read as polar" }
    }
})

private const val POLAR = 90.0f
private const val JUST_OVER_THE_HORIZON = 3.0f
private const val WELL_UP = 25.0f
