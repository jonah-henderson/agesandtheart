package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.age.reward.PaperTreeWindow
import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That a word reads by where it is laid — the readings spike's one claim, through the real parser and
 * resolver (world model §3). `polar` laid bare only nudges the Age cold; aimed at the climate it claims the
 * temperature; aimed at the sun it sets the sun's path, and that path is the paper tree's sky.
 */
@Tags(NEEDS_REGISTRIES)
class ReadingsCheck : FunSpec({

    fun path(vararg pages: String) =
        ShippedCorpus.resolved(SEED, *pages).composition.optionsFor(Aspect.SUN, 0).of(SkyBodies.PATH)

    fun temperature(vararg pages: String) =
        ShippedCorpus.resolved(SEED, *pages).composition.optionsFor(Aspect.CLIMATE, 0)
            .of(ClimateAxis.TEMPERATURE.parameter)

    /** Laid bare is before the `age` page, where a word is written on the Age itself. */
    fun bare(word: String) =
        Resolver.resolve(ShippedCorpus.vocabulary, ShippedCorpus.read(listOf(word, "age")), SEED).composition

    test("polar laid bare leaves the sun on its own path, and only leans the climate") {
        val composition = bare("polar")
        val sunPath = composition.optionsFor(Aspect.SUN, 0).of(SkyBodies.PATH)
        check(sunPath != POLAR) { "a bare 'polar' pinned the sun's path" }
        val leaned = composition.optionsFor(Aspect.CLIMATE, 0).of(ClimateAxis.TEMPERATURE.parameter)
        check(leaned != COLD) { "a bare 'polar' claimed the temperature outright, where it may only lean it" }
    }

    test("polar sun sets the sun's path, and not the climate") {
        check(path("polar", "sun") == POLAR) { "'polar sun' left the path at ${path("polar", "sun")}" }
        check(temperature("polar", "sun") == temperature()) { "'polar sun' reached the climate" }
    }

    test("polar climate claims the temperature, and not the sun") {
        check(temperature("polar", "climate") == COLD) {
            "'polar climate' left the temperature at ${temperature("polar", "climate")}"
        }
        check(path("polar", "climate") != POLAR) { "'polar climate' reached the sun" }
    }

    test("a polar sun is the paper tree's sky") {
        val composition = ShippedCorpus.resolved(SEED, "polar", "sun").composition
        check(PaperTreeWindow.isPolar(Sky.specFor(composition, SEED))) { "'polar sun' did not read as polar" }
    }

    test("aimed, polar costs more than laid bare, and the page costs its dearest reading") {
        val polar = ShippedCorpus.vocabulary.word("polar") ?: error("no 'polar'")
        val aimed = polar.readingFor(setOf(Aspect.SUN))
        check(aimed.price > polar.price) { "aimed ${aimed.price} is no dearer than bare ${polar.price}" }
        check(polar.pagePrice == aimed.price) { "the page costs ${polar.pagePrice}, not its dearest reading" }
    }
})

private const val SEED = 20260929L
private const val POLAR = "polar"
private const val COLD = "-0.95..-0.45"
