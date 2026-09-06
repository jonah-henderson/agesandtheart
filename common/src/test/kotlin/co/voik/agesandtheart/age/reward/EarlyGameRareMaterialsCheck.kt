package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Sea
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
    }
}
