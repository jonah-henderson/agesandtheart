package co.voik.agesandtheart.sky

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Colour
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.AuroraAspect
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.ephemeris.sky.Aurora
import io.kotest.core.spec.style.FunSpec

/**
 * What a sentence about an aurora turns into — the half of it that is arithmetic over options and needs no
 * level, no client and no window.
 *
 * The ordering is the point. A ramp's order is the one thing about an aurora a writer states outright, and
 * it passes through a sentence, a composition, a codec and a texture on its way to the sky. Every one of
 * those is somewhere it can be reversed without anything failing.
 */
class AuroraAuthoringCheck : FunSpec({

    val seed = 4242L

    fun sky(vararg parts: Pair<Aspect, Options>) = Sky.specFor(Described(options = parts.toMap()), seed)

    fun options(vararg chosen: Pair<String, List<String>>) = Options(chosen.toMap())

    fun happens(claim: String) = Aspect.PHENOMENA to options(Phenomena.HAPPENS.name to listOf(claim))

    fun curtain(vararg parts: Pair<Aspect, Options>): Aurora =
        sky(*parts).aurora ?: error("nothing hung a curtain in that sky")

    test("an Age that says nothing about an aurora has none") {
        check(sky().aurora == null) { "A sky nobody described wore a curtain anyway" }
    }

    test("naming the phenomenon is enough") {
        check(sky(happens("aurora")).aurora != null) { "`auroral` named the phenomenon and nothing appeared" }
    }

    test("describing one is enough on its own") {
        // The aspect holds nothing, so no clause can mint a member here the way a clause mints a sun. A dial
        // with anything on it *is* the writer saying the Age has one, and this is what holds that.
        val described = Aspect.AURORA to options(AuroraAspect.AURORACOLOUR.name to listOf("green"))
        check(sky(described).aurora != null) { "`green aurora` described a curtain that was never hung" }
    }

    test("a curtain nobody described is held to the snow line") {
        // An aurora belongs where the snow does, unless a writer has said otherwise.
        check(curtain(happens("aurora")).warmestGround == Aurora.SNOW_LINE) {
            "An Age's own aurora is not held to the ground, so it plays over deserts"
        }
    }

    /**
     * **And a writer can loosen that**, which is the whole of why the rule became a ceiling: the cold was
     * only ever a proxy for a latitude an Age does not carry, and a charged Age has no reason to be cold.
     */
    test("a curtain can be asked to stand over warmer ground") {
        val warm = Aspect.AURORA to options(AuroraAspect.AURORAWARMTH.name to listOf("0.9..1"))
        val asked = curtain(happens("aurora"), warm)
        val ordinary = curtain(happens("aurora")).warmestGround ?: 0.0f
        check((asked.warmestGround ?: 0.0f) > ordinary) {
            "asking for warmth left the curtain at ${asked.warmestGround} against an ordinary $ordinary"
        }
    }

    test("the ramp is the colours in the order they were written") {
        val crownToHem = listOf("red", "green", "purple")
        val written = Aspect.AURORA to options(AuroraAspect.AURORACOLOUR.name to crownToHem)
        val ramp = curtain(written).colours
        check(ramp.size == crownToHem.size) { "Three colours became ${ramp.size}" }
        // Compared through the same saturation the sky applies, so this checks the order and not the palette.
        val expected = crownToHem.map { Colour.named(it) ?: error("no colour '$it'") }
        for ((at, name) in crownToHem.withIndex()) {
            val nearest = expected.minByOrNull { colour ->
                Math.abs(colour.red - ramp[at].red) + Math.abs(colour.green - ramp[at].green) +
                    Math.abs(colour.blue - ramp[at].blue)
            }
            check(nearest == expected[at]) { "Position $at should be $name and is nearest ${expected.indexOf(nearest)}" }
        }
    }

    test("the reverse order is a different sky") {
        val crownRed = Aspect.AURORA to options(AuroraAspect.AURORACOLOUR.name to listOf("red", "purple"))
        val crownPurple = Aspect.AURORA to options(AuroraAspect.AURORACOLOUR.name to listOf("purple", "red"))
        check(curtain(crownRed).colours != curtain(crownPurple).colours) {
            "Naming two colours the other way round gave the same ramp, so the order is being thrown away"
        }
        check(curtain(crownRed).colours == curtain(crownPurple).colours.reversed()) {
            "The two orders are not each other's reverse, so something beyond order changed"
        }
    }

    test("a ramp of one is a curtain of one colour") {
        val one = Aspect.AURORA to options(AuroraAspect.AURORACOLOUR.name to listOf("blue"))
        check(curtain(one).colours.size == 1) { "One colour became ${curtain(one).colours.size}" }
    }

    test("a curtain nobody coloured burns the ordinary ramp") {
        check(curtain(happens("aurora")).colours == Aurora.ORDINARY_RAMP) {
            "An undescribed curtain is not the real one, which is the whole of what a default is for"
        }
    }

    test("the rung is how hard it comes") {
        val ordinary = curtain(happens("aurora"))
        val teeming = curtain(happens("aurora[amount=${Rung.spelled(Rung.ORDINARY * 2)}]"))
        check(teeming.frequency > ordinary.frequency) {
            "A teeming aurora comes on ${teeming.frequency} of nights against an ordinary ${ordinary.frequency}"
        }
        check(teeming.glow > ordinary.glow) { "A teeming aurora is no brighter than an ordinary one" }
    }

    test("a curtain reproduces from its Age's seed") {
        val said = arrayOf(happens("aurora"))
        val first = Sky.specFor(Described(options = said.toMap()), 99L).aurora
        val again = Sky.specFor(Described(options = said.toMap()), 99L).aurora
        val elsewhere = Sky.specFor(Described(options = said.toMap()), 100L).aurora
        check(first == again) { "One Age gave two curtains" }
        check(first?.bearingDegrees != elsewhere?.bearingDegrees) { "Two Ages' curtains cross the same way" }
    }

    test("a phenomenon struck out hangs nothing") {
        // `except aurora` is the writer saying outright to leave it alone, and `Skew` drops it before this
        // ever looks — so what holds here is that describing nothing else brings nothing back.
        check(sky(happens("aurora[except]")).aurora == null) { "A struck-out aurora was hung anyway" }
    }

    test("every phenomenon is answered for") {
        // The enum drives an exhaustive `when` in `Happenings`, so this is really about the data beside it:
        // a phenomenon with no tags is invisible to every evocative word.
        check(Phenomenon.entries.map { it.key }.containsAll(listOf("tempest", "inferno", "aurora"))) {
            "The phenomena are ${Phenomenon.entries.map { it.key }}"
        }
    }
})
