package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Sky
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
 * Where the blocks actually land is a walk; the visual backlog carries it.
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
        check(EarlyGameRareMaterials.growsArcCrystal(charged(), SEED, Spending.NOTHING, emptyMap())) {
            "an Age with both halves of the gate put nothing in its sky"
        }
    }

    test("storms with an ordinary curtain grow nothing") {
        val stormy = plain().withOptions(Aspect.PHENOMENA, HAPPENS, listOf(TEMPEST, AURORA))
        check(!EarlyGameRareMaterials.growsArcCrystal(stormy, SEED, Spending.NOTHING, emptyMap())) {
            "a curtain nobody leaned on counted as charged, so `tempests auroral` is the whole gate"
        }
    }

    test("curtains with no storms grow nothing") {
        val quiet = plain().withOptions(Aspect.PHENOMENA, HAPPENS, listOf(AURORA)).underFierceCurtains()
        check(!EarlyGameRareMaterials.growsArcCrystal(quiet, SEED, Spending.NOTHING, emptyMap())) {
            "an unstormy Age grew arc crystal, which makes the tempest half of the gate decorative"
        }
    }

    test("an Age nobody said anything about grows nothing") {
        check(!EarlyGameRareMaterials.growsArcCrystal(plain(), SEED, Spending.NOTHING, emptyMap())) {
            "a silent sky counted as charged"
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

        private val HAPPENS = Phenomena.HAPPENS.name
        private val TEMPEST = Phenomenon.TEMPEST.key
        private val AURORA = Phenomenon.AURORA.key

        /** What `electromagnetic` asks of the curtain, said as the dials rather than as the word. */
        private fun AgeComposition.underFierceCurtains(): AgeComposition = this
            .withOptions(Aspect.AURORA, Sky.AURORAFREQUENCY.name, listOf("0.75..1"))
            .withOptions(Aspect.AURORA, Sky.AURORAGLOW.name, listOf("0.6..1"))

        private fun charged(): AgeComposition = plain()
            .withOptions(Aspect.PHENOMENA, HAPPENS, listOf(TEMPEST, AURORA))
            .underFierceCurtains()
    }
}
