package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.ORDINARY_SHARE
import co.voik.agesandtheart.age.aspect.Phenomenon
import io.kotest.core.spec.style.FunSpec

/**
 * What a walk may ask an Age's sky to do (`/age weather`).
 *
 * **The list is derived, never written**, which is the only interesting thing about it: a phenomenon
 * belongs here exactly when it has an opinion about weather, and [Phenomenon.insistsOn] already *is* that
 * opinion. So a new weather-like phenomenon appears in the command by existing, and one that merely lives
 * in the weather stays out of it — with no second list to fall out of step with the first.
 */
class AgeWeatherCheck : FunSpec({

    test("the three vanilla skies can be asked for") {
        val asked = AgeWeather.asked()
        for (name in listOf("clear", "rain", "thunder")) {
            check(name in asked) { "'$name' cannot be asked for: ${asked.keys}" }
        }
    }

    /** Rain is wet and not stormy, and thunder is both — or the two names mean the same thing. */
    test("the three skies differ from each other") {
        val asked = AgeWeather.asked()
        val clear = asked.getValue("clear")
        val rain = asked.getValue("rain")
        val thunder = asked.getValue("thunder")
        check(rain.rainfall > clear.rainfall) { "rain is no wetter than clear" }
        check(thunder.thunder > rain.thunder) { "thunder is no stormier than rain" }
        check(rain.rainfall > ORDINARY_SHARE) { "asking for rain would not rain" }
    }

    /**
     * The derivation itself: every phenomenon with an opinion is offered, and every one without is not.
     * `tempest` is the first of the first kind and `inferno` the first of the second.
     */
    test("a phenomenon is asked for exactly when it has an opinion about weather") {
        val asked = AgeWeather.asked()
        for (phenomenon in Phenomenon.entries) {
            val hasAnOpinion = !phenomenon.insistsOn.saysNothing
            check((phenomenon.key in asked) == hasAnOpinion) {
                "${phenomenon.key} ${if (hasAnOpinion) "wants weather but cannot be asked for" else "is offered as weather and has no opinion about it"}"
            }
        }
        check("tempest" in asked) { "a tempest is weather and is not offered" }
        check("inferno" !in asked) { "an inferno is not weather and is offered" }
    }

    /** And what a phenomenon asks for is its own insistence, not a name that happens to match. */
    test("asking for a phenomenon asks for what that phenomenon needs") {
        check(AgeWeather.asked()["tempest"] == Phenomenon.TEMPEST.insistsOn) {
            "'tempest' does not ask for what a tempest insists on"
        }
    }
})
