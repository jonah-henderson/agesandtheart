package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Motes
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.ephemeris.sky.LevelLook
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That the words for what instability inflicts write the same thing on purpose (`notes/strange-words-plan.md`
 * §4), through the real parser and resolver.
 */
@Tags(NEEDS_REGISTRIES)
class StrangeWordsCheck : FunSpec({

    fun aimed(seed: Long, vararg pages: String): AgeComposition = ShippedCorpus.resolved(seed, *pages).composition

    fun AgeComposition.cloudsAtY(): Float? =
        Atmosphere.lookIn(this, 0L).ceiling?.let {
            Atmosphere.LOWEST_CLOUD + it * (Atmosphere.HIGHEST_CLOUD - Atmosphere.LOWEST_CLOUD)
        }

    test("fog brings the fog") {
        for (seed in SEEDS) {
            val befalls = Phenomena.claimFor(aimed(seed, "fog").optionsFor(Aspect.PHENOMENA, 0), Phenomenon.FOG)
            check(befalls != null) { "seed $seed: 'fog' brought no fog" }
        }
    }

    test("low clouds come down to where people walk") {
        for (seed in SEEDS) {
            val height = aimed(seed, "low", "clouds").cloudsAtY()
            check(height != null && height <= AMONG_THE_LAND) { "seed $seed: 'low clouds' sat at $height" }
        }
    }

    test("unfading stars shine by day as at midnight, and the sky alone leaves them to set") {
        for (seed in SEEDS) {
            val kept = Sky.specFor(aimed(seed, "unfading", "stars"), seed).stars.byDay
            val left = Sky.specFor(aimed(seed, "unfading", "sky"), seed).stars.byDay
            check(kept == AS_AT_MIDNIGHT) { "seed $seed: 'unfading stars' kept them at $kept by day" }
            check(left == NONE) { "seed $seed: 'unfading sky' kept the stars at $left by day" }
        }
    }

    test("colourless air drains the Age, and a colourless sun does not") {
        for (seed in SEEDS) {
            val drained = Atmosphere.filtersIn(aimed(seed, "colourless", "air"))
            val bleached = Atmosphere.filtersIn(aimed(seed, "colourless", "sun"))
            check(drained == listOf(LevelLook.MONOCHROME)) { "seed $seed: 'colourless air' was seen through $drained" }
            check(bleached.isEmpty()) { "seed $seed: 'colourless sun' drained the Age" }
        }
    }

    test("torn tears the sky or the land, whichever it is laid on") {
        for (seed in SEEDS) {
            val sky = aimed(seed, "torn", "sky")
            val land = aimed(seed, "torn", "landmass")
            check(sky.optionsFor(Aspect.SKY).isTrue(Sky.TORN)) { "seed $seed: 'torn sky' left the sky whole" }
            check(!sky.anyMemberSays(Aspect.TERRAIN, Terrain.TORN)) { "seed $seed: 'torn sky' tore the land" }
            check(land.anyMemberSays(Aspect.TERRAIN, Terrain.TORN)) { "seed $seed: 'torn landmass' left the land whole" }
            check(!land.optionsFor(Aspect.SKY).isTrue(Sky.TORN)) { "seed $seed: 'torn landmass' tore the sky" }
        }
    }

    test("incongruous land is foreign in patches") {
        for (seed in SEEDS) {
            val land = aimed(seed, "incongruous", "landmass")
            check(land.anyMemberSays(Aspect.TERRAIN, Terrain.INCONGRUOUS)) { "seed $seed: 'incongruous landmass' did nothing" }
        }
    }

    test("a uniform landmass is laid out on a plan, or flat") {
        for (seed in SEEDS) {
            val seated = aimed(seed, "uniform", "landmass").terrains
            check(seated.all { it in UNIFORM_LANDFORMS }) { "seed $seed: 'uniform landmass' seated $seated" }
        }
    }

    test("uniform and bare features stamp and strip what grows, and motionless spawns root what lives") {
        for (seed in SEEDS) {
            check(aimed(seed, "uniform", "features").optionsFor(Aspect.FEATURES).isTrue(Features.UNIFORM)) {
                "seed $seed: 'uniform features' stamped nothing"
            }
            check(aimed(seed, "bare", "features").optionsFor(Aspect.FEATURES).isTrue(Features.BARE)) {
                "seed $seed: 'bare features' left the leaves on"
            }
            check(aimed(seed, "motionless", "spawns").optionsFor(Aspect.SPAWNS).isTrue(Spawns.STILL)) {
                "seed $seed: 'motionless spawns' rooted nothing"
            }
        }
    }

    test("uncanny air hangs uncanny motes, and they are not handed to vanilla's particles") {
        for (seed in SEEDS) {
            val air = aimed(seed, "uncanny", "air")
            check(air.optionsFor(Aspect.AIR).of(Atmosphere.MOTES) == Motes.UNCANNY) { "seed $seed: 'uncanny air' hung nothing" }
            check(Atmosphere.lookIn(air, seed).motes == null) { "seed $seed: the uncanny motes reached vanilla's" }
        }
    }
})

private val SEEDS = 0L..<20L
private val UNIFORM_LANDFORMS = setOf(Terrain.FLATLANDS, Terrain.PILLARS, Terrain.PYRAMIDS)

/** A few blocks over the sea, where the low-clouds step puts them. */
private const val AMONG_THE_LAND = 84f
private const val AS_AT_MIDNIGHT = 1f
private const val NONE = 0f
