package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.resolved
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Spawns
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **What one evocative page may bring about** — the bound on an atmosphere's reach (`Resolver.drawnAmong`).
 *
 * An evocative word leans on tags and a tag reaches dozens of members, so scaling every one of them made a
 * single page write the catalogue: `foreboding age basalt landmass molten sea` resolved to forty-nine
 * creatures and seven phenomena, which in play was hadalfish and ghasts in a basalt world under a tempest,
 * a blizzard, an inferno and a deluge at once (Jonah, 2026-09-17, the Age Tsi).
 *
 * The two Ages below are the real ones, at the seeds they were really written with, so what this holds is
 * the case that was actually wrong rather than a reconstruction of it.
 */
@Tags(NEEDS_REGISTRIES)
class AtmosphereDrawCheck : FunSpec({

    fun claims(seed: Long, aspect: Aspect, pages: List<String>): List<Claim> {
        val options = resolved(seed, *pages.toTypedArray()).composition.optionsFor(aspect, 0)
        return when (aspect) {
            Aspect.PHENOMENA -> Phenomena.claimsIn(options)
            else -> options.claimsOn(Spawns.LIVES)
        }
    }

    /** What an atmosphere actually puts into the world, which is what got out of hand. */
    fun introduced(seed: Long, aspect: Aspect, pages: List<String>): List<String> =
        claims(seed, aspect, pages)
            .filter { it.onlyWhereItGrows && it.density > Rung.ORDINARY }
            .map(Claim::value)

    /**
     * **Swept over seeds rather than asked of one**, because offline a tag reaches a fraction of what it
     * reaches in play — the biomes and structure sets are derived from the *dynamic* registries, so at any
     * one seed the bound here may not bite at all and a check on that seed alone would pass saying
     * nothing. The half that must bite somewhere is asserted separately.
     *
     * The bound as it is actually met is `WritingCheck`'s, on a server, where the corpus is whole.
     */
    test("one dread page never writes the whole catalogue, at any seed") {
        for (seed in MANY_SEEDS) {
            val creatures = introduced(seed, Aspect.SPAWNS, TSI)
            check(creatures.size <= MOST_CREATURES) {
                "seed $seed introduced ${creatures.size} creatures: $creatures"
            }
            val phenomena = introduced(seed, Aspect.PHENOMENA, TSI)
            check(phenomena.size <= MOST_PHENOMENA) {
                "seed $seed introduced ${phenomena.size} phenomena: $phenomena"
            }
        }
    }

    /**
     * **And still brings something about**, which is the half a bound is easiest to break: an atmosphere
     * that introduced nothing would be a word that does not work rather than one that works properly.
     */
    test("a dread page still makes an Age dreadful") {
        val brought = MANY_SEEDS.flatMap { introduced(it, Aspect.SPAWNS, TSI) }
        check(brought.isNotEmpty()) { "'foreboding' brought nothing about at any seed" }
    }

    /**
     * **The property worth having.** The old behaviour lifted every match, so every `foreboding` Age was
     * the same `foreboding` Age; drawn off the seed, two are unalike — which is the whole reason to draw
     * rather than to cap.
     */
    test("two Ages written from the same word are not the same Age") {
        val drawn = MANY_SEEDS.map { introduced(it, Aspect.SPAWNS, TSI).toSet() }
        check(drawn.distinct().size > ONE_ANSWER) {
            "every seed drew the same creatures: ${drawn.first()}"
        }
    }

    /** And the same seed twice, or an Age would be a different world each time it was opened. */
    test("the draw is the same answer every time") {
        check(introduced(TSI_SEED, Aspect.SPAWNS, TSI) == introduced(TSI_SEED, Aspect.SPAWNS, TSI)) {
            "the same Age drew different creatures on a second resolution"
        }
    }

    /**
     * **A word aimed at an aspect is not an atmosphere and keeps every member it reaches.** `undead` means
     * the undead — all of them, the same ones every time — where `foreboding` means a feeling and is drawn
     * among. Both are descriptions and only one is a draw.
     *
     * **Read off the seed rather than off the count**, because a count proves nothing: `undead` reaches
     * exactly six creatures in the shipped corpus, which is also what the draw would have left. Varying
     * with the seed is the thing only a drawn word does.
     */
    test("a word that aims at an aspect is left whole") {
        val undead = MANY_SEEDS.map { introduced(it, Aspect.SPAWNS, listOf("age", "undead")).toSet() }
        check(undead.distinct().size == ONE_ANSWER) {
            "'undead' drew a different set per seed, so it was treated as an atmosphere: $undead"
        }
        check(undead.first().isNotEmpty()) { "'undead' reached nothing at all, so this proves nothing" }
    }
}) {
    private companion object {
        /** The two found-book Ages that showed the fault, at the seeds they were written with. */
        val TSI = listOf("foreboding", "age", "basalt", "landmass", "molten", "sea")
        const val TSI_SEED = 5134421L

        /** Its own seed and a few beside it, since offline the reach at any one seed may be thin. */
        val MANY_SEEDS = (0L..15L).map { TSI_SEED + it }

        /** `Resolver.INTRODUCES_WHAT_IT_LIFTS`, restated so a change to it is a change here too. */
        const val MOST_CREATURES = 6
        const val MOST_PHENOMENA = 2
        const val ONE_ANSWER = 1
    }
}
