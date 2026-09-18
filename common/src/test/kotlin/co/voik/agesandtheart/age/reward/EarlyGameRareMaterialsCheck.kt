package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.AuroraAspect
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import net.minecraft.resources.Identifier
import io.kotest.core.spec.style.FunSpec

/**
 * That an early material is grown only where its Age earns it.
 *
 * The gates are pure functions of the recipe, which is what lets the desk's survey answer before the Age
 * is opened — so they are askable here with no server and no registries.
 *
 * Where the blocks actually land is a walk.
 */
class EarlyGameRareMaterialsCheck : FunSpec({

    test("a hot Age standing in lava bakes temperstone") {
        check(EarlyGameRareMaterials.bakesTemperstone(hot().inLava())) {
            "an Age with both halves of the gate baked nothing"
        }
    }

    test("heat with no lava bakes nothing") {
        check(!EarlyGameRareMaterials.bakesTemperstone(hot())) {
            "a hot Age with a water sea baked temperstone against nothing"
        }
    }

    test("lava with no heat bakes nothing") {
        check(!EarlyGameRareMaterials.bakesTemperstone(cold().inLava())) {
            "a frozen Age with a lava sea baked temperstone, which makes the heat gate decorative"
        }
    }

    test("an Age nobody said anything about the temperature of bakes nothing") {
        check(!EarlyGameRareMaterials.bakesTemperstone(plain().inLava())) {
            "an unstated climate counted as hot, so every lava Age would pay"
        }
    }

    /**
     * The charged sky's two halves, and the word written for it clears both.
     *
     * The gate reads the aurora off the spec a client is sent rather than off the dials behind it, so what
     * a writer is promised at the desk and what they look up at are the same curtain.
     */
    test("an Age with tempests and constant fierce curtains grows arc crystal") {
        check(EarlyGameRareMaterials.growsArcCrystal(charged(), SEED, Spending.NOTHING)) {
            "an Age with both halves of the gate put nothing in its sky"
        }
    }

    test("storms with an ordinary curtain grow nothing") {
        val stormy = plain().withOptions(Aspect.PHENOMENA, HAPPENS, listOf(TEMPEST, AURORA))
        check(!EarlyGameRareMaterials.growsArcCrystal(stormy, SEED, Spending.NOTHING)) {
            "a curtain nobody leaned on counted as charged, so `tempests auroral` is the whole gate"
        }
    }

    test("curtains with no storms grow nothing") {
        val quiet = plain().withOptions(Aspect.PHENOMENA, HAPPENS, listOf(AURORA)).underFierceCurtains()
        check(!EarlyGameRareMaterials.growsArcCrystal(quiet, SEED, Spending.NOTHING)) {
            "an unstormy Age grew arc crystal, which makes the tempest half of the gate decorative"
        }
    }

    test("an Age nobody said anything about grows nothing") {
        check(!EarlyGameRareMaterials.growsArcCrystal(plain(), SEED, Spending.NOTHING)) {
            "a silent sky counted as charged"
        }
    }

    /** The deep-ocean material's two halves (design §7.1.2): an abyss to stand a vent in, and a deluge. */
    test("a deep water sea under a deluge vents its abyss") {
        check(EarlyGameRareMaterials.ventsTheAbyss(abyssal().drowning(), SEED, Spending.NOTHING)) {
            "an Age with both halves of the gate grew no vents"
        }
    }

    test("an abyss with no deluge vents nothing") {
        check(!EarlyGameRareMaterials.ventsTheAbyss(abyssal(), SEED, Spending.NOTHING)) {
            "an abyss alone grew vents, which makes the deluge half of the gate decorative"
        }
    }

    test("a deluge over an ordinary sea vents nothing") {
        check(!EarlyGameRareMaterials.ventsTheAbyss(plain().inWater().drowning(), SEED, Spending.NOTHING)) {
            "a sea nobody deepened counted as an abyss"
        }
    }

    test("a deep sea of anything but water vents nothing") {
        check(!EarlyGameRareMaterials.ventsTheAbyss(abyssal().inLava().drowning(), SEED, Spending.NOTHING)) {
            "a deep lava sea counted as an abyss, where deep water only ever replaces water"
        }
    }

    /** A deluge the Age fell into is still a sea rising over you, as an inflicted blizzard still freezes. */
    test("a deluge instability inflicted counts") {
        val inflicted = Spending(
            steps = mapOf(Manifestation.DELUGE to mapOf(Manifestation.RISE_RATE to 1)),
            ceilings = mapOf(Manifestation.DELUGE to Manifestation.DELUGE.dials.associateWith { ONE_STEP }),
        )
        check(EarlyGameRareMaterials.ventsTheAbyss(abyssal(), SEED, inflicted)) {
            "an abyss under an inflicted deluge grew no vents"
        }
    }

    /**
     * One warm corner is not a warm world.
     *
     * The mirror of rime's rule, and the one a territory-by-territory reading gets wrong: a writer who
     * divided the climate should not earn the reward on the strength of the half that qualifies.
     */
    test("an Age hot in one territory and temperate in another bakes nothing") {
        val divided = AgeComposition(terrains = listOf(Terrain.HILLS, Terrain.HILLS))
            .withOptions(Aspect.CLIMATE, TEMPERATURE, listOf(SCORCHING))
            .withOptionsFor(Aspect.CLIMATE, member = 1, parameter = TEMPERATURE, chosen = listOf(MILD))
            .inLava()
        check(!EarlyGameRareMaterials.bakesTemperstone(divided)) {
            "a half-hot Age baked temperstone, so breadth could be bought with one territory"
        }
    }
}) {
    companion object {
        private val TEMPERATURE = ClimateAxis.TEMPERATURE.parameter.name

        /** Above the desert landmark, which is where the gate sits. */
        private const val SCORCHING = "0.6..0.9"
        private const val MILD = "-0.1..0.1"
        private const val FREEZING = "-0.9..-0.6"

        private fun plain(): AgeComposition = AgeComposition(terrains = listOf(Terrain.HILLS))

        private fun hot(): AgeComposition = plain().withOptions(Aspect.CLIMATE, TEMPERATURE, listOf(SCORCHING))

        private fun cold(): AgeComposition = plain().withOptions(Aspect.CLIMATE, TEMPERATURE, listOf(FREEZING))

        private fun AgeComposition.inLava(): AgeComposition =
            copy(seas = List(seas.size.coerceAtLeast(1)) { Sea(Identifier.withDefaultNamespace("lava")) })

        private const val SEED = 4242L

        private const val ONE_STEP = 1

        private fun AgeComposition.inWater(): AgeComposition =
            copy(seas = List(seas.size.coerceAtLeast(1)) { Sea(Identifier.withDefaultNamespace("water")) })

        /** A water sea raised as `deep` raises it, said as the dial rather than as the word. */
        private fun abyssal(): AgeComposition =
            plain().inWater().withOptions(Aspect.SEA, Sea.DEPTH.name, listOf("0.85..0.98"))

        private fun AgeComposition.drowning(): AgeComposition =
            withOptions(Aspect.PHENOMENA, HAPPENS, listOf(Phenomenon.DELUGE.key))

        private val HAPPENS = Phenomena.HAPPENS.name
        private val TEMPEST = Phenomenon.TEMPEST.key
        private val AURORA = Phenomenon.AURORA.key

        /** What `electromagnetic` asks of the curtain, said as the dials rather than as the word. */
        private fun AgeComposition.underFierceCurtains(): AgeComposition = this
            .withOptions(Aspect.AURORA, AuroraAspect.AURORAFREQUENCY.name, listOf("0.75..1"))
            .withOptions(Aspect.AURORA, AuroraAspect.AURORAGLOW.name, listOf("0.6..1"))

        private fun charged(): AgeComposition = plain()
            .withOptions(Aspect.PHENOMENA, HAPPENS, listOf(TEMPEST, AURORA))
            .underFierceCurtains()
    }
}
