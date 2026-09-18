package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Price
import co.voik.agesandtheart.age.aspect.Phenomenon
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import kotlin.math.abs

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

    test("the weights are a division of one whole") {
        val weights = table.weights
        val total = weights.materials + weights.spawns + weights.phenomena + weights.lighting +
            weights.features
        check(abs(total - ONE_WHOLE) < A_ROUNDING) {
            "the contributors share $total of the score rather than one"
        }
    }

    test("the threshold is reachable and not free") {
        check(table.paysAbove > 0.0) { "every Age pays out, however safe" }
        check(table.paysAbove < ONE_WHOLE) { "the threshold is above what a wholly dangerous Age scores" }
    }

    /**
     * The one thing that can rot silently: a phenomenon added later is worth nothing until somebody rates
     * it, and worth nothing is indistinguishable from *decided to be worth nothing*.
     */
    test("every phenomenon has been given a rating, even the ones worth nothing") {
        val unrated = Phenomenon.entries.map { it.key } - table.phenomena.keys
        check(unrated.isEmpty()) { "art/danger.json says nothing about $unrated" }
    }

    test("every material and creature it rates is one this pack has") {
        val absentBlocks = table.materials.keys.filterNot { named ->
            Identifier.tryParse(named)?.let { BuiltInRegistries.BLOCK.getOptional(it).isPresent } == true
        }
        check(absentBlocks.isEmpty()) { "art/danger.json rates blocks that do not exist: $absentBlocks" }

        val absentCreatures = (table.spawns.keys - MONSTERS).filterNot { named ->
            Identifier.tryParse(named)?.let { BuiltInRegistries.ENTITY_TYPE.getOptional(it).isPresent } == true
        }
        check(absentCreatures.isEmpty()) { "art/danger.json rates creatures that do not exist: $absentCreatures" }
    }

    /**
     * The fallback is why the file is twenty-five lines rather than two hundred: vanilla already says which
     * creatures are monsters, so only the ones that are not *ordinarily* dangerous need an entry.
     */
    test("a monster nobody rated is worth the ordinary amount, and a cow is worth nothing") {
        val zombie = table.spawn("minecraft:zombie")
        val cow = table.spawn("minecraft:cow")
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
        check(infernal.materials > 0.0) { "a lava sea was not counted as what the Age is made of" }
        check(infernal.lighting > 0.0) { "a sealed sky was not counted as darkness" }
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
        val direct = Danger.of(composition, Instability.NONE, SEED, authored = true, table, prices)
        check(throughTheRecipe == direct) { "a recipe scored $throughTheRecipe where its composition scored $direct" }
    }
}) {
    companion object {
        private const val SEED = 42L
        private const val ONE_WHOLE = 1.0
        private const val A_ROUNDING = 1e-9
        private const val MONSTERS = "monsters"

        private val table by lazy { DangerTable.load(MinecraftRegistries.shippedData()) }

        private val prices = Manifestation.entries.associateWith(Price::ordinaryFor)

        private fun scoreOver(template: AgeTemplate): Danger = Danger.of(
            AgeRecipe(AgeWorld.Composed(template.world()), seed = SEED, template = template, authored = true),
            table,
            prices,
        )
    }
}
