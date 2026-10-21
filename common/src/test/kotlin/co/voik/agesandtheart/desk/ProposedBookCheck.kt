package co.voik.agesandtheart.desk

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.RUNS_LONG_TAG
import co.voik.agesandtheart.preview.authoring.Corpus
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier

/**
 * What the frequency tuner proposes: a book the writer could bind, from their own words, within the desk's tier, as
 * unstable as the interference dial says and no more — the same one for the same signal.
 */
@Tags(NEEDS_REGISTRIES)
class ProposedBookCheck : FunSpec({

    val vocabulary by lazy { Corpus.load().vocabulary }
    val registries: RegistryAccess by lazy { RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY) }
    val everyWord by lazy { vocabulary.words.map { it.id } + vocabulary.structurePages.map { it.id } }
    val seeds = (0..<SIGNALS).map { Tuning.CENTRED.withDial(it % Tuning.DIALS, it % Tuning.STEPS).seed + it }

    fun propose(seed: Long, tier: ProposalTier, step: Int, known: Collection<Identifier> = everyWord) =
        ProposedBook.of(vocabulary, registries, known, seed, tier, InterferenceBand.of(step))

    /** What is wrong with [proposed] for [tier] and [step] at [seed], or null where nothing is. */
    fun complaintAbout(proposed: List<Identifier>, seed: Long, tier: ProposalTier, step: Int): String? {
        val pages = proposed.map(Identifier::getPath)
        val band = InterferenceBand.of(step)
        val reading = vocabulary.read(pages, seed)
        val index = reading?.let { vocabulary.resolve(it, seed).instability.index }
        val inkOutOfTier = proposed.filter { id ->
            val word = vocabulary.word(id)
            val structure = vocabulary.structurePage(id.path)
            val ink = word?.let { vocabulary.ink.tierFor(it, registries) } ?: structure?.let(vocabulary.ink::tierFor)
            ink !in tier.inks
        }
        val structurePages = proposed.filter { vocabulary.structurePage(it.path) != null && it.path != "age" }
        return when {
            proposed.size > tier.mostPages -> "${proposed.size} pages: $pages"
            reading?.readsAsLaid != true -> "does not read as laid: $pages"
            index == null || index !in band.least..band.most -> "instability $index outside ${band.least}..${band.most}: $pages"
            inkOutOfTier.isNotEmpty() -> "inks beyond the tier: $inkOutOfTier"
            structurePages.isNotEmpty() && !tier.usesStructurePages -> "structure pages below the third tier: $structurePages"
            else -> null
        }
    }

    test("a proposal at a few interferences is valid at every tier") {
        val bad = mutableListOf<String>()
        for (tier in ProposalTier.entries) for (step in SAMPLED_STEPS) for (seed in seeds.take(SAMPLED_SIGNALS)) {
            val proposed = propose(seed, tier, step)
            if (proposed.isNotEmpty()) complaintAbout(proposed, seed, tier, step)?.let { bad += "$tier, step $step, seed $seed: $it" }
        }
        check(bad.isEmpty()) { bad.joinToString("\n") }
    }

    for (tier in ProposalTier.entries) {
        test("every $tier proposal at every interference short of collapse is valid").config(tags = setOf(RUNS_LONG_TAG)) {
            val bad = mutableListOf<String>()
            val proposedAt = mutableMapOf<Int, Int>()
            for (step in 0..<InterferenceBand.STEPS - 1) for (seed in seeds) {
                val proposed = propose(seed, tier, step)
                if (proposed.isEmpty()) continue
                proposedAt.merge(step, 1, Int::plus)
                complaintAbout(proposed, seed, tier, step)?.let { bad += "$tier, step $step, seed $seed: $it" }
            }
            check(bad.isEmpty()) { bad.joinToString("\n") }
            check((proposedAt[0] ?: 0) > SIGNALS / 2) { "$tier proposed a coherent book for ${proposedAt[0]} of $SIGNALS signals" }
            println("PROPOSED $tier by step: $proposedAt")
        }
    }

    test("the same signal proposes the same book") {
        val seed = Tuning.CENTRED.seed
        check(propose(seed, ProposalTier.THIRD, 3) == propose(seed, ProposalTier.THIRD, 3))
    }

    test("a writer who knows only a few words is proposed only those") {
        val known = listOf("age", "gentle", "pillared", "landmass", "frozen", "sky").mapNotNull { name ->
            everyWord.firstOrNull { it.path == name }
        }
        val proposed = seeds.flatMap { propose(it, ProposalTier.SECOND, 0, known) }
        check(proposed.isNotEmpty()) { "nothing was proposed from $known" }
        check(proposed.all { it in known }) { "proposed words the writer does not know: ${proposed - known.toSet()}" }
    }

    test("a writer with no nucleus is proposed nothing") {
        val known = everyWord.filter { it.path in setOf("gentle", "landmass") }
        check(propose(Tuning.CENTRED.seed, ProposalTier.THIRD, 0, known).isEmpty())
    }

    test("a proposal is quick enough to answer a dial") {
        val started = System.nanoTime()
        for (seed in seeds.take(TIMED)) propose(seed, ProposalTier.THIRD, TIMED_STEP)
        val each = (System.nanoTime() - started) / 1_000_000 / TIMED
        println("PROPOSED in ${each}ms each")
        check(each < MOST_MILLIS) { "a proposal took ${each}ms" }
    }

    test("print a few, to read").config(tags = setOf(RUNS_LONG_TAG)) {
        for (step in listOf(0, 3, 6, 9)) for (seed in seeds.take(PRINTED)) {
            val pages = propose(seed, ProposalTier.THIRD, step).map(Identifier::getPath)
            println("PROPOSED step $step: ${pages.joinToString(" ")}")
        }
    }
}) {
    private companion object {
        const val SIGNALS = 30
        const val SAMPLED_SIGNALS = 3
        val SAMPLED_STEPS = listOf(0, 3, 6)
        const val PRINTED = 3
        const val TIMED = 10
        const val TIMED_STEP = 6
        const val MOST_MILLIS = 150
    }
}
