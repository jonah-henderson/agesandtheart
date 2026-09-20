package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier

/**
 * The two scarab conditions a recipe can answer on its own (design §7.1.2).
 *
 * The other two — a jungle to stand in and mud with sand beside it — are questions about a world and are
 * walked rather than checked here.
 */
class ScarabHabitatCheck : FunSpec({

    test("an Age nobody bent the climate of is warm enough somewhere") {
        check(ScarabHabitat.warmthOf(plain()) == ScarabHabitat.Warmth.SUITS) {
            "an unstated climate counted as unlivable, so only a written climate could ever hold a colony"
        }
    }

    test("a frozen Age is too cold") {
        check(ScarabHabitat.warmthOf(climate(FREEZING)) == ScarabHabitat.Warmth.TOO_COLD) {
            "a world that never thaws suited a beetle"
        }
    }

    test("an Age pinned to the top of the scale is too hot") {
        check(ScarabHabitat.warmthOf(climate(SEARING)) == ScarabHabitat.Warmth.TOO_HOT) {
            "a furnace suited a beetle"
        }
    }

    /**
     * The condition exists for exactly this Age: the jungle is named rather than grown, so nothing else
     * would have caught the cold.
     */
    test("a tropical Age suits") {
        check(ScarabHabitat.warmthOf(climate(TROPICAL)) == ScarabHabitat.Warmth.SUITS) {
            "the climate a jungle actually lives in was refused"
        }
    }

    /**
     * The mirror of rime's rule, deliberately the other way round: a colony lives in one place, so one warm
     * territory is a home.
     */
    test("an Age frozen in one territory and tropical in another suits") {
        val divided = AgeComposition(terrains = listOf(Terrain.HILLS, Terrain.HILLS))
            .withOptions(Aspect.CLIMATE, TEMPERATURE, listOf(FREEZING))
            .withOptionsFor(Aspect.CLIMATE, member = 1, parameter = TEMPERATURE, chosen = listOf(TROPICAL))
        check(ScarabHabitat.warmthOf(divided) == ScarabHabitat.Warmth.SUITS) {
            "a warm half of a divided world was refused, so a colony would need the whole Age to be warm"
        }
    }

    test("an Age nobody wrote torchflowers into grows none") {
        check(ScarabHabitat.torchflowersIn(plain(), ::anyJungle) == ScarabHabitat.Torchflowers.NONE) {
            "a world with no flowers in its recipe counted as holding them"
        }
    }

    test("torchflowers written Age-wide reach the jungle") {
        val everywhere = growing(TORCHFLOWERS)
        check(ScarabHabitat.torchflowersIn(everywhere, ::anyJungle) == ScarabHabitat.Torchflowers.WILD_IN_THE_JUNGLE) {
            "flowers grown across the whole Age were read as missing from the part of it that is jungle"
        }
    }

    test("torchflowers confined to the jungle reach it") {
        val inTheJungle = growing("$TORCHFLOWERS[in=$JUNGLE]")
        check(ScarabHabitat.torchflowersIn(inTheJungle, ::anyJungle) == ScarabHabitat.Torchflowers.WILD_IN_THE_JUNGLE) {
            "`torchflowers features in jungle` — the phrasing §7.1.2 names first — did not satisfy it"
        }
    }

    test("torchflowers confined somewhere else never meet the colony") {
        val inTheDesert = growing("$TORCHFLOWERS[in=$DESERT]")
        val verdict = ScarabHabitat.torchflowersIn(inTheDesert, ::anyJungle)
        check(verdict == ScarabHabitat.Torchflowers.AWAY_FROM_THE_JUNGLE) {
            "flowers pinned to a desert counted as the jungle's, so the confinement bought the condition"
        }
    }

    test("torchflowers struck out grow none") {
        val struckOut = growing("$TORCHFLOWERS[except]")
        check(ScarabHabitat.torchflowersIn(struckOut, ::anyJungle) == ScarabHabitat.Torchflowers.NONE) {
            "a book that said `except torchflowers` was read as having asked for them"
        }
    }
}) {
    companion object {
        private val TEMPERATURE = ClimateAxis.TEMPERATURE.parameter.name

        /** Words of the corpus, said as the spans they set rather than as the words. */
        private const val FREEZING = "-0.95..-0.45"
        private const val TROPICAL = "0.45..0.9"
        private const val SEARING = "0.9..1.0"

        private val TORCHFLOWERS = ScarabHabitat.TORCHFLOWERS.toString()
        private const val JUNGLE = "minecraft:jungle"
        private const val DESERT = "minecraft:desert"

        private fun anyJungle(biome: Identifier): Boolean = biome.path.contains("jungle")

        private fun plain(): AgeComposition = AgeComposition(terrains = listOf(Terrain.HILLS))

        private fun climate(span: String): AgeComposition =
            plain().withOptions(Aspect.CLIMATE, TEMPERATURE, listOf(span))

        /** An Age over one landform asking for exactly [placed] to be grown in it. */
        private fun growing(vararg placed: String): AgeComposition =
            plain().withOptionsFor(Aspect.FEATURES, 0, Features.PLACES.name, placed.toList())
    }
}
