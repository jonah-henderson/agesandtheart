package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Ladder
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import io.kotest.core.annotation.Tags
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import io.kotest.core.spec.style.FunSpec

/**
 * That the table the mod actually ships is one the evaluator can use — it loads, its ids name real
 * content, and nothing it has to have an opinion about was left out.
 *
 * **Nothing here asserts a rating.** Those are meant to be retuned by play, and a check pinning one would
 * make every tuning pass a broken build; [DangerCheck] scores against a table of its own for exactly that
 * reason. What is asserted is the shape, and the two facts that only a bound registry can answer.
 */
@Tags(NEEDS_REGISTRIES)
class DangerTableCheck : FunSpec({

    test("the shipped table loads") {
        check(table !== DangerTable.NONE) { "art/danger.json did not load, so no Age can ever pay out" }
    }

    test("the lines rise in order, and the combining exponent sits between a sum and a root-sum-square") {
        val lines = table.lines
        val rising = listOf(lines.paysAt, lines.little, lines.some, lines.much, lines.immense)
        check(lines.paysAt > 0.0) { "every Age pays out, however safe" }
        check(rising.zipWithNext().all { (lower, higher) -> lower < higher }) { "the deposit lines do not rise: $rising" }
        check(lines.ruinsBelow > 0.0) { "no Age can hold ruins" }
        check(table.combining in 1.0..2.0) { "hazards combine under ${table.combining}" }
    }

    /**
     * The one thing that can rot silently: a phenomenon added later is worth nothing until somebody rates
     * it, and worth nothing is indistinguishable from *decided to be worth nothing*.
     */
    test("every phenomenon has been given a rating, even the ones worth nothing") {
        val unrated = Phenomenon.entries.map { it.key }.filterNot { it in table.ratedPhenomena }
        check(unrated.isEmpty()) { "art/danger.json says nothing about $unrated" }
    }

    test("every landform and underground it rates is one there is") {
        val landforms = Terrain.entries.map { it.key }.toSet()
        val undergrounds = Underground.entries.map { it.key }.toSet()
        val strayLandforms = table.ground.ratedLandforms.filterNot { it in landforms }
        val strayUndergrounds = table.ground.ratedUndergrounds.filterNot { it in undergrounds }
        check(strayLandforms.isEmpty()) { "art/danger.json rates landforms that do not exist: $strayLandforms" }
        check(strayUndergrounds.isEmpty()) { "art/danger.json rates undergrounds that do not exist: $strayUndergrounds" }
    }

    test("every block and creature it rates is one this pack has") {
        fun absentFrom(registry: Registry<*>, named: Set<String>) = named.filterNot { name ->
            Identifier.tryParse(name)?.let { registry.getOptional(it).isPresent } == true
        }
        val absentBlocks = absentFrom(BuiltInRegistries.BLOCK, table.ground.ratedMaterials)
        // Ours are registered only on a running server: AgeContent cannot be stood up once the registries are frozen.
        val vanillaCreatures = table.ratedCreatures.filterNot { it == MONSTERS || it.startsWith("${Constants.MOD_ID}:") }.toSet()
        val absentCreatures = absentFrom(BuiltInRegistries.ENTITY_TYPE, vanillaCreatures)
        check(absentBlocks.isEmpty()) { "art/danger.json rates blocks that do not exist: $absentBlocks" }
        check(absentCreatures.isEmpty()) { "art/danger.json rates creatures that do not exist: $absentCreatures" }
    }

    /**
     * The fallback is why the file is twenty-five lines rather than two hundred: vanilla already says which
     * creatures are monsters, so only the ones that are not *ordinarily* dangerous need an entry.
     */
    test("a monster nobody rated is worth the ordinary amount, and a cow is worth nothing") {
        val zombie = table.creature("minecraft:zombie")
        val cow = table.creature("minecraft:cow")
        check(zombie > 0.0) { "an unrated monster was worth nothing" }
        check(cow == 0.0) { "a cow was worth $cow" }
    }

    /**
     * That the template needs no special case, which is the claim [Danger] is written on: what a template
     * supplied is merged into the composition before the recipe holds it, so a hellish Age arrives at the
     * evaluator already saying it has a lava sea under a roof.
     */
    test("an Age written over the nether is dangerous, and one written over the overworld is not") {
        val infernal = scoreOver(AgeTemplate.INFERNAL)
        val ordinary = scoreOver(AgeTemplate.OVERWORLD)
        check(infernal.score > ordinary.score) {
            "the nether scored ${infernal.score} against the overworld's ${ordinary.score}"
        }
        check(infernal.from(Danger.Source.SEA).isNotEmpty()) { "a lava sea was not counted: ${infernal.hazards}" }
        check(infernal.hazards.any { it.name == "landform" }) { "the Nether's own landform was not rated: ${infernal.hazards}" }
    }

    /** An Age nobody composed has nothing to read, and none of them is a thing a writer wrote. */
    test("a bespoke Age has no composition to score and pays nothing") {
        val bespoke = AgeRecipe(AgeWorld.Bespoke(AgePreset.VANILLA), seed = SEED)
        val scored = Danger.of(bespoke, table, prices)
        check(scored.score == 0.0) { "a bespoke Age scored ${scored.score}" }
        check(!scored.paysOut) { "a bespoke Age paid out" }
    }

    /** The recipe-level entry agrees with the composition-level one it delegates to. */
    test("scoring a recipe is scoring the composition in it") {
        val composition = AgeTemplate.INFERNAL.world()
        val throughTheRecipe = scoreOver(AgeTemplate.INFERNAL)
        val direct = Danger.of(composition, Instability.NONE, SEED, table, prices)
        check(throughTheRecipe == direct) { "a recipe scored $throughTheRecipe where its composition scored $direct" }
    }
}) {
    companion object {
        private const val SEED = 42L
        private const val MONSTERS = "monsters"

        private val table by lazy { DangerTable.load(MinecraftRegistries.shippedData()) }

        private val prices = Manifestation.entries.associateWith(Ladder::ordinaryFor)

        private fun scoreOver(template: AgeTemplate): Danger = Danger.of(
            AgeRecipe(AgeWorld.Composed(template.world()), seed = SEED, template = template, authored = true),
            table,
            prices,
        )
    }
}
