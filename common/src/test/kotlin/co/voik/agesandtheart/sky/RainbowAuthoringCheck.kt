package co.voik.agesandtheart.sky

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Colour
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.ephemeris.sky.Rainbow
import io.kotest.core.spec.style.FunSpec

/**
 * What a sentence about a rainbow turns into — the half of it that is arithmetic over options and needs no
 * level, no client and no window.
 *
 * `red and yellow rainbow` is the sentence the aspect exists for: without a page of its own those colours
 * would land on the sky around the bow rather than on the bow. So the order and the attachment are what is
 * held here, as they are for a curtain.
 */
class RainbowAuthoringCheck : FunSpec({

    val seed = 4242L

    fun sky(vararg parts: Pair<Aspect, Options>) = Sky.PLAIN.specFor(Described(options = parts.toMap()), seed)

    fun options(vararg chosen: Pair<String, List<String>>) = Options(chosen.toMap())

    fun happens(claim: String) = Aspect.PHENOMENA to options(Phenomena.HAPPENS.name to listOf(claim))

    fun bow(vararg parts: Pair<Aspect, Options>): Rainbow =
        sky(*parts).rainbow ?: error("nothing wrote a bow into that sky")

    test("an Age that says nothing about a bow has none") {
        check(sky().rainbow == null) { "A sky nobody described wore a bow anyway" }
    }

    test("naming the phenomenon is enough") {
        check(sky(happens("rainbow")).rainbow != null) { "`rainbows` named the phenomenon and nothing appeared" }
    }

    test("describing one is enough on its own") {
        // The aspect holds nothing, so no clause can mint a member here. A dial with anything on it *is*
        // the writer saying the Age has bows, which is what `prismatic` leans on to mean anything at all.
        val described = Aspect.RAINBOW to options(Sky.RAINBOWCOLOUR.name to listOf("green"))
        check(sky(described).rainbow != null) { "`green rainbow` described a bow that was never written" }
    }

    test("the band is the colours in the order they were written") {
        val outsideIn = listOf("red", "yellow", "purple")
        val written = Aspect.RAINBOW to options(Sky.RAINBOWCOLOUR.name to outsideIn)
        val band = bow(written).colours
        check(band.size == outsideIn.size) { "Three colours became ${band.size}" }
        // Compared through the same saturation the sky applies, so this checks order and not palette.
        val expected = outsideIn.map { Colour.named(it) ?: error("no colour '$it'") }
        for (at in outsideIn.indices) {
            val nearest = expected.minByOrNull { colour ->
                Math.abs(colour.red - band[at].red) + Math.abs(colour.green - band[at].green) +
                    Math.abs(colour.blue - band[at].blue)
            }
            check(nearest == expected[at]) {
                "Position $at should be ${outsideIn[at]} and is nearest ${expected.indexOf(nearest)}"
            }
        }
    }

    test("the reverse order is a different sky") {
        val redOutside = Aspect.RAINBOW to options(Sky.RAINBOWCOLOUR.name to listOf("red", "purple"))
        val purpleOutside = Aspect.RAINBOW to options(Sky.RAINBOWCOLOUR.name to listOf("purple", "red"))
        check(bow(redOutside).colours == bow(purpleOutside).colours.reversed()) {
            "Naming two colours the other way round did not reverse the band, so the order is being lost"
        }
    }

    test("a bow nobody coloured burns the ordinary spectrum") {
        check(bow(happens("rainbow")).colours == Rainbow.ORDINARY_SPECTRUM) {
            "An undescribed bow is not the real one, which is the whole of what a default is for"
        }
    }

    test("a bow nobody measured stands where water puts it") {
        // **The one place this departs from the curtain, deliberately.** How large an aurora hangs is drawn
        // from the Age's seed so two undescribed ones differ; a bow's radius is a fact about what its light
        // is bending *through*, and an Age's rain is water until a writer says otherwise.
        check(bow(happens("rainbow")).radiusDegrees == Rainbow.WATERS_OWN) {
            "An Age nobody asked drew its own radius, so its rain is some other substance by accident"
        }
        val wider = Aspect.RAINBOW to options(Sky.RAINBOWSIZE.name to listOf("1.0..1.0"))
        check(bow(wider).radiusDegrees > Rainbow.WATERS_OWN) { "Asking for a wide bow did not widen it" }
    }

    test("a bow nobody spoke to about the weather waits for rain") {
        check(bow(happens("rainbow")).needsRain == Rainbow.ORDINARY_RAIN_NEEDED) {
            "An undescribed bow does not want rain, which is not what a bow is"
        }
    }

    test("`prismatic` severs the bow from the weather, and asserts one by doing it") {
        // The dial the walk asked for: an Age whose air splits light on its own. Setting it is also the
        // whole of how the word says the Age has bows at all.
        val prismatic = Aspect.RAINBOW to options(Sky.RAINBOWRAIN.name to listOf("-1.0..-1.0"))
        val written = sky(prismatic).rainbow
        check(written != null) { "`prismatic` set a dial and no bow appeared" }
        check(written.needsRain == 0.0f) { "`prismatic` left the bow needing ${written.needsRain} rain" }
        check(written.wetEnoughAt(0.0f) == 1.0f) { "A prismatic bow still waits for rain that never falls" }
    }

    test("the phenomenon insists on rain, where a curtain insists on nothing") {
        // The one phenomenon for which a weather *floor* is the right shape: an inferno wants a dry Age and
        // a curtain a clear one, so neither could ask for anything, and a bow wants exactly what a floor
        // can give. It cannot be said in the word — `rainbows` would be repriced as a word about weather.
        check(!Phenomenon.RAINBOW.insistsOn.saysNothing) {
            "An Age told to have bows is not told to have the weather that makes them"
        }
        check(Phenomenon.AURORA.insistsOn.saysNothing) { "A curtain started asking for weather" }
    }
})
