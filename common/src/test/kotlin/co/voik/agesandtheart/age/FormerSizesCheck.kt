package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.worldgen.LandSize
import co.voik.agesandtheart.worldgen.SizeScale
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * Unsaid land stands at vanilla's scale while colossal and minuscule stay where they were, and an Age written
 * before that keeps generating at the size it began at.
 */
class FormerSizesCheck : FunSpec({

    test("the land's size words fall on their factors, the ends where they were") {
        val factors = mapOf(-1.0 to 0.25, -0.5 to 0.707, null to 2.0, 0.5 to 2.83, 1.0 to 4.0)
        for ((said, wanted) in factors) {
            val factor = SizeScale.factorAt(LandSize.powerFor(said))
            check(abs(factor - wanted) < CLOSE_ENOUGH) { "size $said reads as $factor, not $wanted" }
        }
    }

    test("an Age written before the land grew is read at its former size, and a new one is not") {
        fun read(version: Int): AgeComposition {
            val recipe = AgeRecipe(AgeWorld.Composed(AgeComposition(terrains = listOf(Terrain.CANYON))), seed = SEED, generatorVersion = version)
            val saved = AgeRecipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe).getOrThrow()
            return AgeRecipe.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow().composition ?: error("no composition")
        }
        val former = read(FormerSizes.LAST_GENERATOR_BEFORE_LAND_GREW)
        val current = read(FormerSizes.LAST_GENERATOR_BEFORE_LAND_GREW + 1)
        check(former.optionsFor(Aspect.TERRAIN, 0).isTrue(Terrain.FORMER_SIZES)) { "the old Age was not marked" }
        check(!current.optionsFor(Aspect.TERRAIN, 0).isTrue(Terrain.FORMER_SIZES)) { "a new Age was marked" }

        val formerRiver = Terrain.CANYON.waterlineAt(former.optionsFor(Aspect.TERRAIN, 0), SEED)
        val asItWas = Terrain.CANYON.waterlineAt(
            Options(mapOf(Terrain.SIZE.name to listOf(Span(LAND_AS_IT_WAS, LAND_AS_IT_WAS).spelled()))), SEED,
        )
        check(formerRiver != Terrain.CANYON.waterlineAt(current.optionsFor(Aspect.TERRAIN, 0), SEED)) {
            "an unsaid canyon's river stands where it did: the land did not grow"
        }
        check(formerRiver == asItWas) { "the old Age's canyon river moved, to $formerRiver from $asItWas" }
    }
})

private const val SEED = 4242L
private const val CLOSE_ENOUGH = 0.01

/** The dial that reads as the former unsaid size, a factor of one. */
private const val LAND_AS_IT_WAS = -1.0 / 3.0
