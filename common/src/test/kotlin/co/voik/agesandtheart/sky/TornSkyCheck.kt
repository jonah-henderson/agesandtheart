package co.voik.agesandtheart.sky

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.consequence.TearStyle
import co.voik.agesandtheart.age.consequence.TornSky
import co.voik.agesandtheart.client.TearShapes
import co.voik.ephemeris.client.SkyBlend
import io.kotest.core.spec.style.FunSpec
import kotlin.math.asin

/** How many tears an Age's sky is torn with, where they hang, how they seal, and which way each style is drawn. */
class TornSkyCheck : FunSpec({

    fun bought(steps: Int) = Spending(
        mapOf(Manifestation.SKY_TEARS to mapOf(Manifestation.TEARS_MANY to steps)),
        mapOf(Manifestation.SKY_TEARS to mapOf(Manifestation.TEARS_MANY to 3)),
    )

    test("ten tears for the first step, then twenty-five, then fifty, and none for none") {
        val counts = (0..3).map { TornSky.countFor(bought(it)) }
        check(counts == listOf(0, 10, 25, 50)) { "steps tore the sky ${counts.joinToString()} times" }
        check(TornSky.countFor(Spending.NOTHING) == 0) { "nothing bought still tore the sky" }
    }

    test("the same seed lays the same tears, every one high in the sky") {
        check(TearShapes.layout(7L, 25) == TearShapes.layout(7L, 25)) { "one seed laid its tears two ways" }
        for (seed in 0L..<20L) {
            for (tear in TearShapes.layout(seed, 50)) {
                val elevation = Math.toDegrees(asin(tear.centre.y.toDouble()))
                check(elevation >= 29.0) { "seed $seed hung a tear's middle at %.1f° up".format(elevation) }
            }
        }
    }

    test("a tear is open most of the time, and shut for a while once in each of its cycles") {
        val tear = TearShapes.layout(3L, 1).single()
        val samples = (0L..<tear.cycleTicks step 20L).map { TearShapes.openness(tear, it.toDouble()) }
        val shut = samples.count { it <= 0.0 }
        val open = samples.count { it >= 1.0 }
        check(shut > 0 && open > shut * 5) { "a tear was shut ${shut} and open ${open} samples of ${samples.size}" }
    }

    test("each style is drawn with its own blend, and every style draws something") {
        val tears = TearShapes.layout(11L, 10)
        val glowing = TearShapes.quadsAt(tears, 0.0, TearStyle.GLOWING, 1f)
        val dark = TearShapes.quadsAt(tears, 0.0, TearStyle.DARK, 1f)
        val starfield = TearShapes.quadsAt(tears, 0.0, TearStyle.STARFIELD, 1f)
        check(glowing.keys == setOf(SkyBlend.LIGHT)) { "glowing tears were drawn ${glowing.keys}" }
        check(dark.keys == setOf(SkyBlend.SHADE)) { "dark tears were drawn ${dark.keys}" }
        check(starfield.keys == setOf(SkyBlend.STARFIELD, SkyBlend.LIGHT)) { "starfield tears were drawn ${starfield.keys}" }
    }

    test("every style turns up for some Age") {
        val styles = (0L..<60L).map(TornSky::styleFor).toSet()
        check(styles == TearStyle.entries.toSet()) { "sixty Ages only ever tore their skies $styles" }
    }
})
