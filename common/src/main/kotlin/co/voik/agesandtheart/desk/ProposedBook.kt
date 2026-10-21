package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.language.Structure
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.resolution.Resolution
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.Vocabulary
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier
import kotlin.random.Random

/**
 * What a proposal may be made of, by the desk in the room (Jonah): a bare or lightly furnished desk five pages of
 * common ink, three implements twenty pages of any ink, and a furnished desk fifty pages with every structure page
 * as well. Below that only `age`, which opens every book.
 */
enum class ProposalTier(val mostPages: Int, val inks: Set<InkTier>, val usesStructurePages: Boolean) {
    FIRST(mostPages = 5, inks = setOf(InkTier.COMMON), usesStructurePages = false),
    SECOND(mostPages = 20, inks = InkTier.entries.toSet(), usesStructurePages = false),
    THIRD(mostPages = 50, inks = InkTier.entries.toSet(), usesStructurePages = true);

    companion object {
        /** The tier for a desk on [rung] of the study's tiers, the bare desk's for none at all. */
        fun forRung(rung: Int?): ProposalTier = entries.getOrElse(rung ?: 0) { THIRD }
    }
}

/**
 * How unstable a proposal is to be, from the interference dial's [step] (Jonah): an instability index exactly, so
 * a dial at nought is coherent; the strange, harmful and failing tiers a step each up the dial (`Spending.topTier`);
 * and the top step an Age that collapses, which [collapses] answers off the resolution.
 */
data class InterferenceBand(val least: Int, val most: Int, val mustCollapse: Boolean) {

    fun admits(resolution: Resolution, collapses: (Resolution) -> Boolean): Boolean {
        val index = resolution.instability.index
        val inBand = index in least..most
        return inBand && (!mustCollapse || collapses(resolution))
    }

    companion object {
        /** The dial's steps: nought to a hundred per cent, in tenths. */
        const val STEPS = 11

        private val BANDS = listOf(
            InterferenceBand(0, 0, false),
            InterferenceBand(1, 1, false),
            InterferenceBand(2, 2, false),
            InterferenceBand(3, 3, false),
            InterferenceBand(4, 4, false),
            InterferenceBand(5, 5, false),
            InterferenceBand(6, 6, false),
            InterferenceBand(7, 7, false),
            InterferenceBand(8, 9, false),
            InterferenceBand(10, 12, false),
            InterferenceBand(13, Int.MAX_VALUE, true),
        )

        fun of(step: Int): InterferenceBand = BANDS[step.coerceIn(0, STEPS - 1)]
    }
}

/**
 * The book the frequency tuner proposes for a signal (design §7.4): a sentence drawn from a writer's own words, the
 * same one for the same seed, tier and interference.
 *
 * **Valid by checking, not by construction.** A draft is grown a clause at a time, each read as laid and resolved
 * as it would be bound, and a clause that would take the draft past the band's instability is not laid. What is
 * proposed reads as laid, is within the tier's pages and inks, and resolves to exactly the instability asked for;
 * where no draft gets there, nothing is proposed.
 */
object ProposedBook {

    /**
     * The pages proposed for [seed] from [known], within [tier] and [band]; empty where nothing can be said. [collapses]
     * answers whether a resolution buys collapse, which only a server can draw, for the band that asks it.
     */
    fun of(
        vocabulary: Vocabulary,
        registries: RegistryAccess,
        known: Collection<Identifier>,
        seed: Long,
        tier: ProposalTier,
        band: InterferenceBand,
        collapses: (Resolution) -> Boolean = { false },
    ): List<Identifier> {
        val words = Words.of(vocabulary, registries, known, tier)
        val nucleus = words.nucleus(seed) ?: return emptyList()
        for (draft in 0..<DRAFTS) {
            val random = Random(seed xor (draft.toLong() * DRAFT_STRIDE) xor band.hashCode().toLong())
            val proposed = Draft(vocabulary, words, seed, tier, band, collapses, random).grownFrom(nucleus)
            if (proposed != null) return proposed.mapNotNull(words.byPage::get)
        }
        return emptyList()
    }

    /** What the writer knows that [tier] allows, sorted out by what each can do in a sentence. */
    private class Words(
        private val vocabulary: Vocabulary,
        val byPage: Map<String, Identifier>,
    ) {
        private fun wordOf(page: String): Word? = vocabulary.word(page)

        fun nucleus(seed: Long): String? = byPage.keys.sorted().firstOrNull { vocabulary.read(listOf(it), seed) != null }

        /** The pages a clause can close on — an aspect's, or a member's — and what lands on each. */
        val heads: List<String> = byPage.keys.sorted().filter { page -> wordOf(page)?.let(vocabulary.lexicon::acceptance) != null }

        val modifiers: List<String> = byPage.keys.sorted().filter { page -> page !in heads && wordOf(page) != null }

        val members: List<String> = heads.filter { head -> Aspect.entries.none { it.page == head } }

        val biomes: List<String> = members.filter { member -> wordOf(member)?.let(vocabulary.lexicon::placesFor)?.contains(Aspect.BIOMES) == true }

        val structural: Set<String> = byPage.keys.filter { vocabulary.structurePage(it)?.structure.let { it != null && it != Structure.AGE } }.toSet()

        fun landsOn(head: String, modifier: String): Boolean {
            val acceptance = wordOf(head)?.let(vocabulary.lexicon::acceptance) ?: return false
            return wordOf(modifier)?.effects.orEmpty().any { acceptance.take(it) != null }
        }

        companion object {
            fun of(vocabulary: Vocabulary, registries: RegistryAccess, known: Collection<Identifier>, tier: ProposalTier): Words {
                fun allows(id: Identifier): Boolean {
                    val word = vocabulary.word(id)
                    val structure = vocabulary.structurePage(id.path)
                    return when {
                        word != null -> vocabulary.ink.tierFor(word, registries) in tier.inks
                        // `age` opens every book, so it is the one structure page every tier has.
                        structure?.structure == Structure.AGE -> true
                        structure != null -> tier.usesStructurePages && vocabulary.ink.tierFor(structure) in tier.inks
                        else -> false
                    }
                }
                return Words(vocabulary, known.filter(::allows).associateBy(Identifier::getPath))
            }
        }
    }

    /** One draft, grown a clause at a time from the nucleus. */
    private class Draft(
        private val vocabulary: Vocabulary,
        private val words: Words,
        private val seed: Long,
        private val tier: ProposalTier,
        private val band: InterferenceBand,
        private val collapses: (Resolution) -> Boolean,
        private val random: Random,
    ) {
        /** The draft once it is in the band, or null where every try is spent first. */
        fun grownFrom(nucleus: String): List<String>? {
            var pages = listOf(nucleus)
            var admitted: List<String>? = null
            repeat(CLAUSE_TRIES) {
                if (admitted != null && random.nextDouble() < STOPS_ONCE_ADMITTED) return admitted
                val clause = clause() ?: return@repeat
                val grown = pages + clause
                if (grown.size > tier.mostPages) return@repeat
                val reading = vocabulary.read(grown, seed)?.takeIf { it.readsAsLaid } ?: return@repeat
                val resolution = vocabulary.resolve(reading, seed)
                if (resolution.instability.index > band.most) return@repeat
                pages = grown
                if (band.admits(resolution, collapses)) admitted = pages
            }
            return admitted
        }

        private fun clause(): List<String>? {
            val grammar = tier.usesStructurePages && words.structural.isNotEmpty() && random.nextDouble() < GRAMMAR_CHANCE
            return if (grammar) structured() ?: described() else described()
        }

        /** Modifiers on a head, or a member said alone. */
        private fun described(): List<String>? {
            val head = words.heads.randomOrNull(random) ?: return null
            val fitting = words.modifiers.filter { words.landsOn(head, it) }
            val isAMember = head in words.members
            if (fitting.isEmpty()) return if (isAMember) listOf(head) else null
            val count = random.nextInt(if (isAMember) 0 else 1, minOf(MOST_MODIFIERS, fitting.size) + 1)
            return fitting.shuffled(random).take(count) + head
        }

        /** One of the structure pages' constructions, where the writer knows its page; the reading says whether it held. */
        private fun structured(): List<String>? {
            val member = words.members.randomOrNull(random) ?: return null
            fun knows(page: String) = page in words.structural
            val forms = buildList<() -> List<String>?> {
                if (knows("no")) add { listOf("no", member) }
                if (knows("only")) add { listOf("only", member) }
                if (knows("everywhere")) add { listOf(member, "everywhere") }
                if (knows("in")) add { words.biomes.randomOrNull(random)?.let { listOf(member, "in", it) } }
                if (knows("of")) add { words.members.randomOrNull(random)?.let { other -> listOf(other, "of", member) } }
                if (knows("link") && knows("in")) add { listOf("link", "in", member) }
                if (knows("and")) add {
                    val fitting = words.modifiers.filter { words.landsOn(member, it) }.shuffled(random).take(2)
                    if (fitting.size == 2) listOf(fitting[0], "and", fitting[1], member) else null
                }
            }
            return forms.randomOrNull(random)?.invoke()
        }
    }

    /** How many drafts are grown before a signal is said to propose nothing. */
    private const val DRAFTS = 6

    /** How many clauses one draft tries, laid or not. */
    private const val CLAUSE_TRIES = 24

    /** How often a draft already in its band stops growing, so proposals are not all as long as they can be. */
    private const val STOPS_ONCE_ADMITTED = 0.5

    private const val GRAMMAR_CHANCE = 0.35
    private const val MOST_MODIFIERS = 3

    private const val DRAFT_STRIDE = -0x61c8_8646_80b5_83ebL
}
