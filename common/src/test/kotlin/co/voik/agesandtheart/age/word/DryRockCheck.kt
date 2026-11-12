package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.resolved
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Carvers
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **A dry underground** (Jonah, 2026-09-29): `dry rock` switches the rock's water table dry, and asking for
 * it beside drowned rock is charged rather than lost. Whether the rock then holds no water is a generation
 * question, and `/age compare` against the same Age with wet rock answers it (31,868 blocks, water to air,
 * at seed 7, radius 6).
 */
@Tags(NEEDS_REGISTRIES)
class DryRockCheck : FunSpec({

    test("dry rock is rock with its water table switched dry") {
        val dry = resolved(SEED, "dry", "rock")
        val rock = dry.composition.carvers
        check(Carvers.FLOODED_CAVES !in rock) { "dry rock seated flooded rock: $rock" }
        check((0..<rock.size).all { dry.composition.optionsFor(Aspect.CARVERS, it).isTrue(Carvers.DRY) }) {
            "dry rock left some of its rock wet: ${dry.composition}"
        }
        check(dry.instability.flaws.isEmpty()) { "dry rock alone was charged: ${dry.instability.flaws}" }
    }

    test("porous rock is the porous carving and nothing else, and dry porous rock is it dry") {
        val porous = resolved(SEED, "porous", "rock")
        check(porous.composition.carvers == listOf(Carvers.POROUS)) { "porous rock seated ${porous.composition.carvers}" }
        check(porous.instability.flaws.isEmpty()) { "porous rock alone was charged: ${porous.instability.flaws}" }
        val dry = resolved(SEED, "dry", "porous", "rock")
        check(dry.composition.carvers == listOf(Carvers.POROUS)) { "dry porous rock seated ${dry.composition.carvers}" }
        check(dry.composition.optionsFor(Aspect.CARVERS, 0).isTrue(Carvers.DRY)) { "dry porous rock was wet: ${dry.composition}" }
    }

    /** Flooded rock has no `dry` to set, so without the exclusion it simply won and `dry` went unsaid. */
    test("dry flooded rock is charged, not quietly flooded") {
        val both = resolved(SEED, "dry", "flooded", "rock")
        check(both.instability.flaws.isNotEmpty()) { "dry flooded rock came out coherent as ${both.composition}" }
    }
})

private const val SEED = 7L
