package co.voik.agesandtheart.age

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Skew
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That the compositions written **in Kotlin** still say what they meant.
 *
 * A hand-authored preset spells its own claims, and a claim's spelling is `Claim`'s to decide — so a preset
 * that writes one out as a string is a copy that can rot in place while everything reading it stays right.
 * One did: the Spire asked for `plasma{only}` when braces were the marks, survived the move to brackets
 * untouched, and spent some time asking for a biome literally named `agesandtheart:plasma{only}`. Nothing
 * noticed, because a claim that parses as a bare value is a perfectly good claim about a biome nobody has.
 *
 * The general form of the rule is *never spell a claim by hand*; this is the check that says so out loud.
 */
@Tags(NEEDS_REGISTRIES)
class BespokeRecipeCheck : FunSpec({

    test("the Spire grows its own biome and nothing else") {
        val world = AgeRecipe.worldFor(AgePreset.SPIRE)
        val composed = world as? AgeWorld.Composed ?: error("the Spire stopped being a composition: $world")
        val claims = composed.composition.optionsFor(Aspect.BIOMES, 0)
            .allSpelled(Biomes.GROWN.name)
            .map(Claim::read)
        val grown = Skew.of(claims)

        check(grown.exclusive) {
            "the Spire's biome claim is not exclusive, so vanilla's whole table grows there: $claims"
        }
        val wanted = grown.wanted.map { it.value }
        check(wanted == listOf(Biomes.PLASMA_BIOME.toString())) {
            "the Spire asked for $wanted rather than for its own biome — a claim spelled by hand and left behind"
        }
    }
})
