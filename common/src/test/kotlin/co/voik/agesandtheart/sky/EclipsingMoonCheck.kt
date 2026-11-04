package co.voik.agesandtheart.sky

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.EclipsingMoons
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.ephemeris.sky.Eclipse
import co.voik.ephemeris.sky.SkySpec
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * `eclipsing moon`: once a month, at noon, a minute of totality — and the size and pace words still say
 * something about it.
 */
class EclipsingMoonCheck : FunSpec({

    val day = 24000L
    val seed = 42L

    fun options(vararg chosen: Pair<String, String>) = Options(chosen.associate { (name, value) -> name to listOf(value) })

    val eclipsing = "path" to EclipsingMoons.ECLIPSING

    fun sky(moon: Options, moons: Int = 1, sun: Options = Options.NONE): SkySpec = Sky.specFor(
        Described(options = mapOf(Aspect.MOON to moon, Aspect.SUN to sun), cast = mapOf(Aspect.MOON to moons)),
        seed,
    )

    fun passages(spec: SkySpec, days: Long) = Eclipse.passagesBetween(spec, from = 0L, until = days * day)


    test("an eclipsing moon covers the sun once a month, at noon, on the game's new-moon day") {
        val spec = sky(options(eclipsing))
        val seen = passages(spec, days = 17)

        check(seen.size == 2) { "Seventeen days held ${seen.size} eclipses: $seen" }
        check(seen.all { it.isTotal }) { "An eclipsing moon the sun's size left it showing: $seen" }
        val days = seen.map { it.deepestAt / day }
        check(days.all { it % 8 == 4L }) { "The eclipses fell on days $days, not the game's new-moon days" }
        val sunHeights = seen.map { spec.bodies[it.sun].path.altitudeAt(it.deepestAt) }
        check(sunHeights.all { it > 85.0f }) { "The sun stood at $sunHeights at totality, not overhead" }
    }

    test("totality lasts a minute or so, and a colossal moon holds it far longer") {
        val seconds = passages(sky(options(eclipsing)), days = 9).map { it.totalTicks / 20.0 }
        check(seconds.all { it in 40.0..120.0 }) { "Totality lasted $seconds seconds" }

        val colossal = passages(sky(options(eclipsing, "size" to "1.0..1.0")), days = 9).map { it.totalTicks / 20.0 }
        check(colossal.all { it > 3 * 60.0 }) { "A colossal eclipsing moon held the dark for only $colossal seconds" }
    }

    test("every eclipsing moon of several keeps its own month") {
        val spec = sky(options(eclipsing), moons = 3)
        val byMoon = passages(spec, days = 24).groupBy { it.coveredBy.single() }

        check(byMoon.size == 3) { "Only moons ${byMoon.keys} eclipsed the sun in three months" }
        check(byMoon.values.all { it.size == 3 }) { "Each moon should eclipse three times in 24 days: $byMoon" }
    }

    test("a small eclipsing moon leaves a ring, and a swift one comes twice as often") {
        val annular = passages(sky(options(eclipsing, "size" to "-0.5..-0.5")), days = 9).single()
        check(!annular.isTotal && annular.greatest in 0.6f..0.9f) { "A small eclipsing moon gave $annular" }

        val swift = passages(sky(options(eclipsing, "pace" to "1.0..1.0")), days = 17)
        check(swift.size == 4) { "A swift eclipsing moon gave ${swift.size} eclipses in seventeen days, not four" }
    }

    test("frequent eclipses come every few days, and sporadic ones about once a month of thirty") {
        val frequent = passages(sky(options(eclipsing, "frequency" to "0.8..0.8")), days = 24)
        check(frequent.size in 7..9) { "A frequent eclipsing moon gave ${frequent.size} eclipses in 24 days" }

        val sporadic = passages(sky(options(eclipsing, "frequency" to "-1.0..-1.0")), days = 64)
        check(sporadic.size == 2) { "A sporadic eclipsing moon gave ${sporadic.size} eclipses in 64 days" }
    }

    test("a slow sun is still eclipsed at its noon") {
        val spec = sky(options(eclipsing), sun = options("pace" to "-1.0..-1.0"))
        val seen = passages(spec, days = 34)

        check(seen.isNotEmpty() && seen.all { it.isTotal }) { "A slow sun's eclipses: $seen" }
        val sunHeights = seen.map { spec.bodies[it.sun].path.altitudeAt(it.deepestAt) }
        check(sunHeights.all { it > 85.0f }) { "A slow sun stood at $sunHeights at totality" }
    }

    test("a moon in a sky with no sun is left as drawn") {
        val sunless = sky(options(eclipsing), sun = options("absent" to "true"))
        val asDrawn = sky(Options.NONE, sun = options("absent" to "true"))

        check(sunless == asDrawn) { "An eclipsing moon with no sun to eclipse was moved anyway" }
    }

    test("one Age gives the same eclipses every time it is built") {
        check(sky(options(eclipsing), moons = 3) == sky(options(eclipsing), moons = 3)) { "Two builds disagreed" }
    }

    test("a constant eclipsing moon covers the sun at every hour, and a frequent one only now and then") {
        val constant = sky(options(eclipsing, "frequency" to "1.0..1.0"))
        val sun = constant.bodies.indexOfFirst { it.phase == null }
        val uncovered = (0L..<3 * day step 500L).filter { (Eclipse.of(constant, sun, it)?.coverage ?: 0.0f) < 0.999f }
        check(uncovered.isEmpty()) { "A constant eclipsing moon left the sun showing at ${uncovered.take(5)}" }

        val frequent = sky(options(eclipsing, "frequency" to "0.6..1.0"))
        val coveredHours = (0L..<3 * day step 500L).count { (Eclipse.of(frequent, sun, it)?.coverage ?: 0.0f) > 0.5f }
        check(coveredHours < 10) { "A frequent eclipsing moon covered the sun at $coveredHours of 144 moments" }
    }

    test("a lit moon is new while it eclipses") {
        val spec = sky(options(eclipsing))
        val moon = spec.bodies.first { it.phase != null }
        val crossing = passages(spec, days = 9).single().deepestAt
        val shape = moon.phase?.stepAt(crossing, moon.path, spec.bodies.first { it.phase == null }.path)

        check(shape == NEW_MOON) { "The moon showed shape $shape as it covered the sun, not new" }
        check(abs((moon.phase?.periodTicks ?: 0) - 8 * day) < day) { "The moon's own month moved" }
    }
}) {
    private companion object {
        const val NEW_MOON = 4
    }
}
