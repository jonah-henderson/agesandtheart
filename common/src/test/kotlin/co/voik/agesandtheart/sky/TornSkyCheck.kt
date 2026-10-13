package co.voik.agesandtheart.sky

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.consequence.TearStyle
import co.voik.agesandtheart.age.consequence.TornSky
import co.voik.agesandtheart.client.TearShapes
import co.voik.ephemeris.client.SkyBlend
import io.kotest.core.spec.style.FunSpec
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs

/** How many tears an Age's sky is torn with, where they hang, how they seal, and which way each style is drawn. */
class TornSkyCheck : FunSpec({

    fun bought(steps: Int) = Spending(
        mapOf(Manifestation.SKY_TEARS to mapOf(Manifestation.TEARS_MANY to steps)),
        mapOf(Manifestation.SKY_TEARS to mapOf(Manifestation.TEARS_MANY to 3)),
    )

    test("twenty tears for the first step, then sixty, then a hundred and fifty, and none for none") {
        val counts = (0..3).map { TornSky.countFor(bought(it)) }
        check(counts == listOf(0, 20, 60, 150)) { "steps tore the sky ${counts.joinToString()} times" }
        check(TornSky.countFor(Spending.NOTHING) == 0) { "nothing bought still tore the sky" }
    }

    test("the same seed lays the same tears, spread evenly over the whole sphere") {
        check(TearShapes.layout(7L, 25) == TearShapes.layout(7L, 25)) { "one seed laid its tears two ways" }
        val heights = (0L..<40L).flatMap { seed -> TearShapes.layout(seed, 150).map { it.centre.y.toDouble() } }
        // Evenly over the sphere is evenly in height, so each fifth of the heights holds a fifth of the tears.
        val perBand = heights.groupingBy { ((it + 1) / 2 * BANDS).toInt().coerceAtMost(BANDS - 1) }.eachCount()
        val even = heights.size / BANDS
        check((0..<BANDS).all { abs((perBand[it] ?: 0) - even) < even / 10 }) { "tears by height band: $perBand" }
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
        fun blendsOf(style: TearStyle) = TearShapes.shapesAt(tears, 0.0, style, 1f, Quaternionf())
            .onEach { check(it.quads.isNotEmpty()) { "$style drew an empty group" } }
            .map { it.blend }
        check(blendsOf(TearStyle.GLOWING) == listOf(SkyBlend.LIGHT, SkyBlend.LIGHT)) { "glowing: ${blendsOf(TearStyle.GLOWING)}" }
        check(blendsOf(TearStyle.DARK) == listOf(SkyBlend.SHADE, SkyBlend.SHADE)) { "dark: ${blendsOf(TearStyle.DARK)}" }
        // Every rim before any window, so crossing windows run together rather than a rim lying over one.
        check(blendsOf(TearStyle.STARFIELD) == listOf(SkyBlend.LIGHT, SkyBlend.STARFIELD)) { "starfield: ${blendsOf(TearStyle.STARFIELD)}" }
    }

    test("tears turn with the stars, keeping their shape") {
        val tears = TearShapes.layout(5L, 4)
        val still = TearShapes.shapesAt(tears, 0.0, TearStyle.GLOWING, 1f, Quaternionf()).last().quads.first().first.direction
        val quarter = Quaternionf().rotateX((PI / 2).toFloat())
        val turned = TearShapes.shapesAt(tears, 0.0, TearStyle.GLOWING, 1f, quarter).last().quads.first().first.direction
        val expected = quarter.transform(Vector3f(still))
        check(Vector3f(turned).distance(expected) < 1e-4f) { "a turned tear stood at $turned, not $expected" }
    }

    test("every style turns up for some Age") {
        val styles = (0L..<60L).map(TornSky::styleFor).toSet()
        check(styles == TearStyle.entries.toSet()) { "sixty Ages only ever tore their skies $styles" }
    }
}) {
    private companion object {
        const val BANDS = 5
    }
}
