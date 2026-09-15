package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.word.grammar.Grammar
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * **How strict an Age is being about a word**, which is the one thing in a tag query the seed decides.
 *
 * A query used to be a pure function of the word and the corpus, so the same word reached the same members
 * at the same amounts in every Age that said it. Now a cut is drawn once per Age between nothing and the
 * tier's own threshold: a member answering at or above the threshold arrives whatever is drawn, and one
 * answering below it arrives in proportion to how close it came.
 *
 * The subject is **discovered rather than named**, because which members sit in that band is content and
 * moves. If the corpus ever holds none, this fails rather than passing on an empty set — a check with
 * nothing to check is the thing it is guarding against.
 */
@Tags(NEEDS_REGISTRIES)
class StrictnessCheck : FunSpec({

    /** A word with members it *almost* reaches, and those members. */
    data class Subject(
        val word: Word,
        val aspect: Aspect,
        val marginal: List<Pair<Taggable, Double>>,
        val outright: List<Taggable>,
    )

    val subject: Subject by lazy {
        val weightedSets = Aspect.entries.filter { it.holds == Holds.WEIGHTED_SET && it.pool != null }
        val found = weightedSets.firstNotNullOfOrNull { aspect ->
            vocabulary.words.filter { it.tier.narrows && it.restrictsIn(aspect).isNotEmpty() }
                .sortedBy { it.name }
                .firstNotNullOfOrNull { word ->
                    val members = vocabulary.askableIn(aspect)
                    val pulls = members.map { it to word.pullIn(aspect, vocabulary.tagsOf(it)) }
                    val marginal = pulls.filter { (_, pull) -> pull > 0.0 && pull < word.tier.threshold }
                    val outright = pulls.filter { (_, pull) -> pull >= word.tier.threshold }.map { it.first }
                    if (marginal.isEmpty() || outright.isEmpty()) null
                    else Subject(word, aspect, marginal, outright)
                }
        }
        checkNotNull(found) {
            "no word in the corpus almost-reaches anything, so nothing here is being checked — either a " +
                "tag was retuned onto a threshold, or the band this mechanism reads has been emptied"
        }
    }

    fun claimedAt(seed: Long): Set<String> {
        val sentence = Grammar.read(vocabulary, listOf("age", subject.word.name))
            ?: error("'${subject.word.name}' is not a book on its own")
        val composition = Resolver.resolve(vocabulary, sentence, seed).composition
        val pool = subject.aspect.pool ?: error("${subject.aspect} holds no pool")
        return composition.optionsFor(subject.aspect, 0).allSpelled(pool.name)
    }

    fun reached(member: Taggable, claims: Set<String>) = claims.any { member.key in it }

    test("a member the word answers outright arrives in every Age") {
        val strays = mutableListOf<String>()
        for (seed in SEEDS) {
            val claims = claimedAt(seed)
            for (member in subject.outright) {
                if (!reached(member, claims)) strays += "${member.key} missing at seed $seed"
            }
        }
        check(strays.isEmpty()) {
            "'${subject.word.name}' is guaranteed to reach what answers it outright, and did not:\n  " +
                strays.take(5).joinToString("\n  ")
        }
    }

    test("a member it almost answers arrives in some Ages and not others") {
        val varying = subject.marginal.count { (member, _) ->
            val seen = SEEDS.count { seed -> reached(member, claimedAt(seed)) }
            seen in 1..<SEEDS.size
        }
        check(varying > 0) {
            "none of ${subject.marginal.size} almost-carriers of '${subject.word.name}' varied across " +
                "${SEEDS.size} seeds — the cut is not being drawn"
        }
    }

    /**
     * The rule itself, stated as a frequency. Loose on purpose: this is a proportion measured over a few
     * dozen Ages, and what it is guarding is that the number tracks how close the member came at all —
     * not that a sample is a distribution.
     */
    test("how often it arrives is how close it came") {
        for ((member, pull) in subject.marginal.take(MEASURED)) {
            val expected = pull / subject.word.tier.threshold
            val seen = SEEDS.count { seed -> reached(member, claimedAt(seed)) }.toDouble() / SEEDS.size
            check(abs(seen - expected) < TOLERANCE) {
                "${member.key} answers '${subject.word.name}' at $pull of ${subject.word.tier.threshold}, " +
                    "so it should arrive in ${"%.0f".format(expected * 100)}% of Ages and arrived in " +
                    "${"%.0f".format(seen * 100)}%"
            }
        }
    }

    /**
     * **An instruction is not subject to the draw.** `only` and `except` are the writer saying outright
     * what to keep and strike, so they are read at the tier's own threshold however generous the Age.
     */
    test("only is read strictly, whatever the Age") {
        val pool = subject.aspect.pool ?: error("${subject.aspect} holds no pool")
        fun claimsOnly(seed: Long): Set<String> {
            val sentence = Grammar.read(vocabulary, listOf("age", "only", subject.word.name))
                ?: error("'only ${subject.word.name}' is not a book")
            return Resolver.resolve(vocabulary, sentence, seed).composition
                .optionsFor(subject.aspect, 0).allSpelled(pool.name)
        }
        val everyAge = SEEDS.map { seed ->
            subject.marginal.count { (member, _) -> reached(member, claimsOnly(seed)) }
        }.distinct()
        check(everyAge.size == 1) {
            "'only ${subject.word.name}' kept ${everyAge.sorted()} almost-carriers depending on the seed, " +
                "and an instruction may not be lucky"
        }
    }
}) {
    private companion object {
        /** Enough Ages for a proportion to settle, few enough that resolving them all stays quick. */
        val SEEDS = (1L..80L).toList()

        /** How many almost-carriers to measure the frequency of; they are usually all at one weight. */
        const val MEASURED = 3

        const val TOLERANCE = 0.15
    }
}
