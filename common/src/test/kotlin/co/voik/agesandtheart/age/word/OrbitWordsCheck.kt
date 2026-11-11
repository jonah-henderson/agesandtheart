package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.age.phenomena.Deluge
import co.voik.ephemeris.sky.Orbit
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A point of the compass before `rising` or `setting` says which horizon a sun or a moon comes up over or goes down
 * under, and nothing else; `rising` alone before a body says nothing, and before the sea it climbs.
 */
@Tags(NEEDS_REGISTRIES)
class OrbitWordsCheck : FunSpec({

    fun composed(vararg pages: String): AgeComposition = ShippedCorpus.resolved(SEED, "age", *pages).composition

    fun AgeComposition.body(aspect: Aspect, parameter: Parameter): String =
        optionsFor(aspect, 0).of(parameter)

    test("a point before rising or setting aims the body after them, and only that body") {
        val sun = composed("north", "rising", "sun")
        check(sun.body(Aspect.SUN, SkyBodies.RISING) == "north") { "'north rising sun' rose ${sun.body(Aspect.SUN, SkyBodies.RISING)}" }
        check(sun.body(Aspect.MOON, SkyBodies.RISING) != "north") { "'north rising sun' moved the moon" }
        val moon = composed("north", "rising", "east", "setting", "moon")
        check(moon.body(Aspect.MOON, SkyBodies.RISING) == "north") { "'north rising east setting moon' rose ${moon.body(Aspect.MOON, SkyBodies.RISING)}" }
        check(moon.body(Aspect.MOON, SkyBodies.SETTING) == "east") { "'north rising east setting moon' set ${moon.body(Aspect.MOON, SkyBodies.SETTING)}" }
    }

    test("a point before rising says nothing of the land or the wind") {
        val composition = composed("north", "rising", "sun")
        check(composition.optionsFor(Aspect.AIR).of(Atmosphere.FROM) != "north") { "'north rising sun' turned the wind" }
    }

    test("a body set under one horizon rises over the opposite one") {
        val sun = Sky.specFor(composed("west", "setting", "sun"), SEED).bodies.first { it.phase == null }
        val node = (sun.path as? Orbit)?.ascendingNodeDegrees
        check(node == Orbit.risingAt(EAST).ascendingNodeDegrees) { "'west setting sun' rose at node $node" }
    }

    test("rising alone before a body says nothing, and the sea does not climb") {
        val composition = composed("rising", "sun")
        check(composition.body(Aspect.SUN, SkyBodies.RISING) == "default") { "'rising sun' aimed the sun" }
        check(!composition.anyMemberSays(Aspect.SEA, Sea.CLIMBING)) { "'rising sun' raised the sea" }
    }

    test("a rising sea climbs without rain") {
        val composition = composed("rising", "sea")
        check(composition.anyMemberSays(Aspect.SEA, Sea.CLIMBING)) { "'rising sea' did not climb" }
        val rising = Deluge.risingIn(composition, SEED, Spending.NOTHING)
        check(rising != null) { "'rising sea' gave the sea no rise" }
        check(rising?.onlyInTheRain == false) { "a rising sea waited for rain" }
        check(rising?.rainShare == 0.0) { "a rising sea asked for rain: ${rising?.rainShare}" }
    }
})

private const val SEED = 20261111L
private const val EAST = 90.0f
