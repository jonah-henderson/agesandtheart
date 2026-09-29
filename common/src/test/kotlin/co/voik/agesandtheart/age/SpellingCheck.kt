package co.voik.agesandtheart.age

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll

/**
 * **Does the spelling read back as what it wrote?** `/age list` output is documented as pasting into
 * `/age compose`, and until now nothing held it to that: the codec had `RecipeCheck` and the text format,
 * which is a hand-written grammar with its own punctuation, had nothing.
 *
 * It is not a theoretical worry. `PARAMETER_MARK` was the same character as `LIST_MARK`, so
 * `[stone=copper,tuff]` — the documented spelling for two materials mingled — could not be read back at all,
 * and it took a person noticing to find it.
 *
 * The recipe for a composition is generated rather than the composition, because that is what shrinks and
 * what prints legibly when it fails (the same reason `SpansCheck` does).
 */
@Tags(NEEDS_REGISTRIES)
class SpellingCheck : FunSpec({

    /**
     * An index set by hand survives the spelling, and an Age with none says nothing about it.
     *
     * The second half is what keeps every existing spelling reading as it always did — `unstable=0` in
     * front of every coherent Age would be noise in the one place a person reads a composition.
     */
    test("a forced instability round-trips through the spelling") {
        val composition = AgeComposition(terrains = listOf(Terrain.HILLS))
        val doomed = CompositionSpelling.Written(composition, instability = Instability.forced(FORCED_INDEX))
        val spelled = CompositionSpelling.spell(doomed)
        check("unstable=$FORCED_INDEX" in spelled) { "the index was not spelled: $spelled" }
        val read = CompositionSpelling.read(spelled).getOrThrow()
        check(read.instability.index == FORCED_INDEX) {
            "the index came back as ${read.instability.index}, not $FORCED_INDEX"
        }

        val coherent = CompositionSpelling.Written(composition)
        check("unstable" !in CompositionSpelling.spell(coherent)) {
            "a coherent Age spells an instability it does not have"
        }
    }

    test("an instability that is not a number is refused rather than ignored") {
        val bad = CompositionSpelling.read("unstable=badly landmass=gentle")
        check(bad.isFailure) { "'unstable=badly' was accepted" }
    }


    /** What a generated Age is made of — enough shapes to reach every branch of the spelling. */
    val landforms = Arb.element(Terrain.entries.filter { it.availableToBroadWords })
    val seas = Arb.element(Sea.WATER, Sea.LAVA, Sea.NONE)
    val undergrounds = Arb.element(Carvers.entries.toList())
    val materials = Arb.of("minecraft:andesite", "minecraft:copper_block", "minecraft:tuff")

    test("every composition reads back as what it spelled") {
        checkAll(
            Arb.list(landforms, 1..3),
            Arb.list(seas, 1..2),
            undergrounds,
            Arb.list(materials, 0..2),
            Arb.int(0..3),
            Arb.boolean(),
        ) { shapes, water, below, stone, suns, divided ->
            var written = AgeComposition(
                terrains = shapes,
                seas = water,
                carvers = listOf(below),
            )
            // Mingled materials on the first territory, which is the case the punctuation bug broke.
            if (stone.isNotEmpty()) {
                written = written.withOptionsFor(Aspect.TERRAIN, 0, Terrain.STONE.name, stone)
            }
            // A cast, which nothing walked before it was spelled out.
            if (suns > 0) written = written.withCastOf(Aspect.SUN, suns)
            // And a divided climate, which spells itself per member only once there are two.
            if (divided) {
                written = written
                    .withOptionsFor(Aspect.CLIMATE, 0, ClimateAxis.TEMPERATURE.key, listOf("-0.9..-0.4"))
                    .withOptionsFor(Aspect.CLIMATE, 1, ClimateAxis.TEMPERATURE.key, listOf("0.5..0.9"))
            }

            val spelling = written.toString()
            val read = AgeComposition.parse(spelling).getOrElse {
                error("'$spelling' would not read back: ${it.message}")
            }
            check(read == written) { "'$spelling' read back as '$read'" }
        }
    }

    /**
     * **And the world it was written over survives too**, which the composition alone cannot say: the
     * template is the recipe's, and the rock may be another world's, so only this says which world the
     * book was written over. Without the token, no hand-composed Age could ever be infernal.
     */
    test("the world an Age was written over reads back as well") {
        for (template in AgeTemplate.entries) {
            val written = CompositionSpelling.Written(template.world().seamed(SPELLING_SEED), template)
            val spelling = CompositionSpelling.spell(written)
            val read = CompositionSpelling.read(spelling).getOrElse {
                error("'$spelling' would not read back: ${it.message}")
            }
            check(read.template == template) { "'$spelling' came back on ${read.template}, not $template" }
            check(read.composition == written.composition) { "'$spelling' read back as '${read.composition}'" }
        }
    }

    /** A spelling that names no landform is refused, which is the one thing every Age must say. */
    test("a composition with no landform is refused") {
        val problem = CompositionSpelling.read("sea=water").exceptionOrNull()
        check(problem != null) { "a composition with no landform was read" }
        check(Aspect.TERRAIN.key in problem.message.orEmpty()) {
            "the refusal does not say what is missing: ${problem.message}"
        }
    }
})

/** One seed, since what is under check does not vary with it. */
private const val SPELLING_SEED = 4242L

/** High enough to buy every step of every register, which is what a raid needs. */
private const val FORCED_INDEX = 400
