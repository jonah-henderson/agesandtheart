package co.voik.agesandtheart.sky

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.ephemeris.sky.Look
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * What an Age with no light of its own is *painted* as, which is the half of darkness that is not the
 * dimension type.
 *
 * Apart from [SkyCheck] because these read [AgeTemplate], and a template holds a `NoiseGeneratorSettings`
 * — so the registries have to be standing. Everything in [SkyCheck] is a pure function of integers and
 * options and stays in the suite `-Pfast` runs.
 */
@Tags(NEEDS_REGISTRIES)
class UnlitLookCheck : FunSpec({

    beforeSpec { MinecraftRegistries.ensureStoodUp() }

    /**
     * **An Age is dark two ways, and everything that reads the fact has to know both.** Sealed overhead is
     * one; nothing shining on it is the other. The dimension type and the skylight knew both while the
     * paint knew only the seal, so a `sunless` Age was held pitch dark by the game and painted broad
     * daylight — the walked bug of 2026-08-05, arriving a second time by a second route.
     */
    test("a world nothing shines on is painted as dark as it is held") {
        val ordinary = Options()
        val sealed = Options(mapOf(Sky.SEALED.name to listOf(Sky.ALWAYS)))
        val sunless = Options(mapOf(Sky.ABSENT.name to listOf(Sky.ALWAYS)))
        fun overOrdinary(sky: Options, sun: Options) =
            Atmosphere.unlitLook(Described(mapOf(Aspect.SKY to sky, Aspect.SUN to sun)), AgeTemplate.OVERWORLD)

        check(!Sky.isLightless(ordinary, ordinary)) { "an ordinary Age came out lightless" }
        check(overOrdinary(ordinary, ordinary) == Look.NOTHING) {
            "an ordinary Age was painted dark: ${overOrdinary(ordinary, ordinary)}"
        }

        val dark = listOf("sealed" to (sealed to ordinary), "sunless" to (ordinary to sunless), "both" to (sealed to sunless))
        for ((described, options) in dark) {
            val (sky, sun) = options
            check(Sky.isLightless(sky, sun)) { "a $described Age is lit" }
            check(overOrdinary(sky, sun).sky != null) { "a $described Age kept its blue sky" }
        }
    }

    /**
     * **A stand-in only stands in where nothing is standing.** Our near-black is what an unlit Age has
     * instead of the blue the overworld's biomes would paint it; over a world already dark it has one
     * colour where the template has real ones, and it flattened the nether's crimson, warped and soul-sand
     * fog into a single grey.
     *
     * The overcast is the other half and is not a stand-in: nothing overhead means no cloud, whichever
     * world the book started from.
     */
    test("a template already dark paints itself, and only loses its clouds") {
        val sealed = Described(mapOf(Aspect.SKY to Options(mapOf(Sky.SEALED.name to listOf(Sky.ALWAYS)))))
        val overNether = Atmosphere.unlitLook(sealed, AgeTemplate.INFERNAL)
        check(overNether.fog == null && overNether.sky == null && overNether.tint == null) {
            "the nether's own air was painted over: $overNether"
        }
        check(overNether.cloud != null) { "a sealed Age kept its overcast: $overNether" }

        val overOverworld = Atmosphere.unlitLook(sealed, AgeTemplate.OVERWORLD)
        check(overOverworld.fog != null && overOverworld.sky != null) {
            "a sealed overworld was left its daylight: $overOverworld"
        }
    }
})
