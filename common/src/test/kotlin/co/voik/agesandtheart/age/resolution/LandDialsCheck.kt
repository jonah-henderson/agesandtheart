package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.AspectOptions
import co.voik.agesandtheart.age.FormerLeans
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.language.ShippedLexicon.resolved
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The words that turn the land's dials, and that a dial a word leaned is drawn where it leaned. */
@Tags(NEEDS_REGISTRIES)
class LandDialsCheck : FunSpec({

    fun terrainOptions(row: String): Options = resolved(row, seed = SEED).composition.optionsFor(Aspect.TERRAIN, 0)

    /** Where half of the Ages written with [row] stand on [parameter], over many salts. */
    fun medianOf(row: String, parameter: Parameter): Double {
        val options = terrainOptions(row)
        val drawn = (0L..<DRAWS).map { salt -> options.steer(parameter, salt) ?: 0.0 }.sorted()
        return drawn[drawn.size / 2]
    }

    test("a word's lean on a land's dial is where half of its Ages fall") {
        for ((row, parameter) in listOf(
            "age worn gentle landmass" to Terrain.WEAR,
            "age varied gentle landmass" to Terrain.VARIETY,
            "age stepped gentle landmass" to Terrain.TERRACING,
        )) {
            val median = medianOf(row, parameter)
            check(median >= LEANED_UP) { "'$row' stands at $median on ${parameter.name}, in ${terrainOptions(row)}" }
        }
        val pristine = medianOf("age pristine gentle landmass", Terrain.WEAR)
        check(pristine <= -LEANED_UP) { "'pristine' stands at $pristine on wear" }
    }

    test("uniform holds the variety at none") {
        val uniform = terrainOptions("age uniform pillared landmass").steer(Terrain.VARIETY, SEED)
        check(uniform == -1.0) { "uniform set the variety to $uniform" }
    }

    test("an Age written before its leans were drawn keeps the even draw it had, and its climate keeps its lean") {
        val leaned = "-1..1~0.85"
        fun read(version: Int): AgeComposition {
            val options = AspectOptions()
                .with(Aspect.TERRAIN, listOf(Options(mapOf(Terrain.WEAR.name to listOf(leaned)))))
                .with(Aspect.CLIMATE, listOf(Options(mapOf("temperature" to listOf(leaned)))))
            val composed = AgeComposition(terrains = listOf(Terrain.HILLS), options = options)
            val recipe = AgeRecipe(AgeWorld.Composed(composed), seed = SEED, generatorVersion = version)
            val saved = AgeRecipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe).getOrThrow()
            return AgeRecipe.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow().composition ?: error("no composition")
        }
        fun said(composition: AgeComposition, aspect: Aspect, name: String) =
            composition.optionsFor(aspect, 0).allSpelled(name)
        val former = read(FormerLeans.LAST_GENERATOR_OF_THE_EVEN_DRAW)
        check(said(former, Aspect.TERRAIN, Terrain.WEAR.name) == setOf("-1..1")) { "the old Age's wear reads ${said(former, Aspect.TERRAIN, "wear")}" }
        check(said(former, Aspect.CLIMATE, "temperature") == setOf(leaned)) { "the old Age's climate lost its lean" }
        val current = read(FormerLeans.LAST_GENERATOR_OF_THE_EVEN_DRAW + 1)
        check(said(current, Aspect.TERRAIN, Terrain.WEAR.name) == setOf(leaned)) { "a new Age's lean was straightened" }
    }
})

private const val SEED = 7L
private const val DRAWS = 201L
private const val LEANED_UP = 0.4
