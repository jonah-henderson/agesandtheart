package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.resolved
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Terrain
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.BuiltInRegistries

/**
 * **A colour aimed at the land or the surface builds it of that colour** — `blue landmass` is a world of blue
 * stone and `blue surface` one dressed in blue, for every colour a writer can say, and a colour laid bare only
 * now and then.
 */
@Tags(NEEDS_REGISTRIES)
class ColouredStoneCheck : FunSpec({

    fun stoneOf(seed: Long, vararg pages: String): String? =
        resolved(seed, *pages).composition.optionsFor(Aspect.TERRAIN, 0).materialsOf(Terrain.STONE)
            .firstOrNull()?.let { BuiltInRegistries.BLOCK.getKey(it.block).toString() }

    test("every colour aimed at the land builds it of a stone of that colour") {
        val missing = COLOURS.flatMap { colour ->
            SEEDS.mapNotNull { seed ->
                val stone = stoneOf(seed, colour, Aspect.TERRAIN.page)
                val carries = stone != null && vocabulary.materials.carriersOf(colour).any { it.first == stone }
                if (carries) null else "`$colour landmass` at seed $seed was built of $stone"
            }
        }
        check(missing.isEmpty()) { missing.joinToString("\n") }
    }

    fun surfaceOf(seed: Long, vararg pages: String): String? =
        resolved(seed, *pages).composition.optionsFor(Aspect.SURFACE, 0).materialsOf(Surface.MATERIAL)
            .firstOrNull()?.let { BuiltInRegistries.BLOCK.getKey(it.block).toString() }

    test("every colour aimed at the surface dresses it in a block of that colour") {
        val missing = COLOURS.flatMap { colour ->
            SEEDS.mapNotNull { seed ->
                val surface = surfaceOf(seed, colour, Aspect.SURFACE.page)
                val carries = surface != null && vocabulary.materials.carriersOf(colour).any { it.first == surface }
                if (carries) null else "`$colour surface` at seed $seed was dressed in $surface"
            }
        }
        check(missing.isEmpty()) { missing.joinToString("\n") }
    }

    test("a colour aimed elsewhere leaves the rock alone") {
        val painted = SEEDS.mapNotNull { seed -> stoneOf(seed, "blue", Aspect.SKY.page) }
            .filter { stone -> vocabulary.materials.carriersOf("blue").any { it.first == stone } }
        check(painted.isEmpty()) { "`blue sky` built the land of $painted" }
    }

    test("a colour laid bare builds of it only now and then") {
        val coloured = BARE_SEEDS.count { seed ->
            val stone = stoneOf(seed, "blue", "age")
            vocabulary.materials.carriersOf("blue").any { it.first == stone }
        }
        check(coloured in 1..<BARE_SEEDS.count() / 3) { "`blue age` was blue stone in $coloured of ${BARE_SEEDS.count()}" }
    }
}) {
    private companion object {
        val COLOURS = listOf("black", "blue", "cyan", "green", "grey", "magenta", "orange", "purple", "red", "indigo", "white", "yellow")
        val SEEDS = listOf(1L, 7L, 42L)
        val BARE_SEEDS = 1L..60L
    }
}
