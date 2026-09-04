package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.word.grammar.Grammar
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **A description bends what is there and never brings anything about** (2026-09-03).
 *
 * An evocative word is written on the whole Age, so its faintest reaches touch everything — and before the
 * describing rule those reaches were *introductions*: a beautiful Age arrived with an inferno, a ghast in
 * every biome and a dragon overhead. Where the biomes decide what belongs — features, biomes, spawns — a
 * word may only lean on what already grows — unless it *names* the member, since admitting one outright
 * is a naming and namings introduce.
 *
 * **Checked because the evidence for it is unreadable.** A recipe spells out the whole leaned pool, so an
 * Age written with one evocative word lists dozens of members and looks exactly like a word pulling in
 * things it should be pushing away. `desolate` was reported as doing that; it was not, and answering the
 * question took a scratch probe and a rebuild. This answers it in a second, and would fail if the rule
 * ever quietly stopped applying.
 */
@Tags(NEEDS_REGISTRIES)
class DescribingCheck : FunSpec({

    val vocabulary by lazy {
        Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen).also {
            check(it.problems.isEmpty()) { "vocabulary problems: ${it.problems}" }
        }
    }

    /** Every member a book left in one population, as spelled. */
    fun membersOf(aspect: Aspect, parameter: String, vararg pages: String): List<String> {
        val sentence = Grammar.read(vocabulary, listOf("age", *pages)) ?: error("not a book: ${pages.toList()}")
        val composition = Resolver.resolve(vocabulary, sentence, SAMPLE_SEED).composition
        return composition.optionsFor(aspect, 0).allSpelled(parameter).toList()
    }

    /**
     * The populations a description may only bend, and the word that leans hardest on each.
     *
     * `desolate` and `beautiful` are the two evocative words that reach everything, and they are chosen
     * here because both have been suspected of introducing — one of them rightly, once.
     */
    val populations = listOf(
        Triple(Aspect.BIOMES, Biomes.GROWN.name, "the biomes"),
        Triple(Aspect.FEATURES, Features.PLACES.name, "the features"),
        Triple(Aspect.SPAWNS, Spawns.LIVES.name, "the spawns"),
    )

    for (name in listOf("desolate", "beautiful")) {
        for ((aspect, parameter, what) in populations) {
            test("'$name' brings nothing about in $what that it did not name") {
                val word = vocabulary.word(name) ?: error("no '$name' in the corpus")
                // What a word is *allowed* to introduce: what it admits or chooses outright. Everything
                // else it says about a population is a lean, and a lean may only bend.
                val named = word.admits[aspect].orEmpty() + setOfNotNull(word.chooses[aspect])
                val introduced = membersOf(aspect, parameter, name, "magma_block", "landmass")
                    // `except` is a strike-out, not an introduction, and carries no `where_it_grows`.
                    .filterNot { it.contains(WHERE_IT_GROWS) || it.contains(STRUCK_OUT) }
                    .filterNot { spelled -> named.any { spelled.startsWith(it) } }
                check(introduced.isEmpty()) {
                    "'$name' brought ${introduced.size} of $what about by leaning alone: ${introduced.take(5)}"
                }
            }
        }
    }

    /**
     * And the rule is not vacuous: a word that *names* a member still puts it there.
     *
     * Without this the checks above would pass just as well on a resolver that had stopped populating
     * anything at all, which is the failure mode a "nothing was introduced" assertion invites.
     */
    test("naming a biome still introduces it") {
        val named = membersOf(Aspect.BIOMES, Biomes.GROWN.name, "minecraft:ice_spikes", "biomes")
        check(named.any { it.contains("ice_spikes") && !it.contains(WHERE_IT_GROWS) }) {
            "naming a biome outright no longer introduces it: $named"
        }
    }
})

private const val SAMPLE_SEED = 0x5EEDL

/** How a claim spells "bend this where it already is" — [co.voik.agesandtheart.age.aspect.Claim]'s own. */
private const val WHERE_IT_GROWS = "where_it_grows"

/** How a claim spells a member struck out — removal, which introduces nothing. */
private const val STRUCK_OUT = "except"
