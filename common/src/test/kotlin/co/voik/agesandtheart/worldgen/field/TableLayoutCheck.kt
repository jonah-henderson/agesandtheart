package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.FormerLayout
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Terrain
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec

/** The table gives each landform about its share of the ground, however near their homes lie. */
class TableLayoutCheck : FunSpec({

    fun coverage(terrains: List<Terrain>, seed: Long): List<Double> {
        val layout = TableLayout(TerrainTable(seed), terrains.map { it.home }, emptyList(), seed)
        val won = IntArray(terrains.size)
        for (row in 0..<SAMPLES_ACROSS) for (column in 0..<SAMPLES_ACROSS) {
            won[layout.winnerAt(column * SAMPLE_SPACING - REACH, row * SAMPLE_SPACING - REACH)]++
        }
        return won.map { it.toDouble() / (SAMPLES_ACROSS * SAMPLES_ACROSS) }
    }

    test("landforms whose homes lie close still share the ground") {
        val mixtures = listOf(
            listOf(Terrain.PILLARS, Terrain.ISLANDS),
            listOf(Terrain.CLIFFS, Terrain.PILLARS, Terrain.ISLANDS),
            listOf(Terrain.CLIFFS, Terrain.CANYON, Terrain.PILLARS, Terrain.ISLANDS, Terrain.HILLS),
        )
        for (terrains in mixtures) for (seed in SEEDS) {
            val even = 1.0 / terrains.size
            val covered = coverage(terrains, seed)
            check(covered.all { it in even * LEAST_OF_EVEN..even * MOST_OF_EVEN }) {
                "$terrains at seed $seed covered ${covered.map { "%.2f".format(it) }}"
            }
        }
    }

    test("an Age written before the layout settled keeps the one it had, and a new one does not") {
        fun read(version: Int): AgeComposition {
            val composed = AgeComposition(terrains = listOf(Terrain.PILLARS, Terrain.ISLANDS))
            val recipe = AgeRecipe(AgeWorld.Composed(composed), seed = SEEDS.first(), generatorVersion = version)
            val saved = AgeRecipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe).getOrThrow()
            return AgeRecipe.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow().composition ?: error("no composition")
        }
        check(read(FormerLayout.LAST_GENERATOR_OF_THE_FORMER_LAYOUT).anyMemberSays(Aspect.TERRAIN, Terrain.FORMER_LAYOUT)) {
            "the old Age's layout was not kept"
        }
        check(!read(FormerLayout.LAST_GENERATOR_OF_THE_FORMER_LAYOUT + 1).anyMemberSays(Aspect.TERRAIN, Terrain.FORMER_LAYOUT)) {
            "a new Age kept the old layout"
        }
    }
})

private val SEEDS = listOf(7L, 4242L, -91L)
private const val SAMPLES_ACROSS = 48
private const val SAMPLE_SPACING = 173
private const val REACH = SAMPLES_ACROSS / 2 * SAMPLE_SPACING
private const val LEAST_OF_EVEN = 0.5
private const val MOST_OF_EVEN = 1.6
