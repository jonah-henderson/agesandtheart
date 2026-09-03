package co.voik.agesandtheart.preview

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Word

/**
 * **A SPIKE — do not build on this.** It answers one question with numbers instead of taste: when a vague
 * word reaches a weighted set, how many members does it lift, and by how much?
 *
 * The suspicion it tests: a tag query over a weighted set is a *filter and scale over every qualifier*,
 * never a draw among them — so adding several members that share a tag makes the word that wants that tag
 * reach all of them at once. And exclusion is a membership test on the tag map, so a member carrying a
 * tag at all is struck as hard as one carrying it wholly.
 */
fun main() {
    val vocabulary = Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen)
    reportHowManyOneWordLifts(vocabulary)
    reportWhoComesForATag(vocabulary)
    reportExclusionIsWeightBlind(vocabulary)
    reportTagsNobodyAsksAfter(vocabulary)
    reportMarginalCarriers(vocabulary)
}

private val weightedSets get() = Aspect.entries.filter { it.holds == Holds.WEIGHTED_SET && it.pool != null }

private fun narrowingWords(vocabulary: Vocabulary): List<Word> =
    vocabulary.words.filter { it.tier.narrows && it.wanted.isNotEmpty() }.sortedBy { it.name }

/** How wide a net each narrowing word casts, per aspect it reaches. */
private fun reportHowManyOneWordLifts(vocabulary: Vocabulary) {
    println("=== How many members of a weighted set one word lifts ===")
    println("word / aspect / lifted / of / mean density / max density")
    for (word in narrowingWords(vocabulary)) {
        for (aspect in weightedSets) {
            if (word.restrictsIn(aspect).isEmpty()) continue
            val members = vocabulary.askableIn(aspect)
            if (members.isEmpty()) continue
            val standings = members.map { it to Resolver.standingOf(vocabulary, word, aspect, it) }
            val lifted = standings.filter { (_, standing) -> standing.kept && standing.strength > 1.0 }
            if (lifted.isEmpty()) continue
            val mean = lifted.sumOf { (_, standing) -> standing.strength } / lifted.size
            val most = lifted.maxOf { (_, standing) -> standing.strength }
            println(
                "%-14s %-12s %3d / %3d   mean %.2f   max %.2f".format(
                    word.name, aspect.name.lowercase(), lifted.size, members.size, mean, most,
                ),
            )
        }
    }
    println()
}

/**
 * **The question a content author actually has**: if I tag a new member `monumental`, who comes for it?
 *
 * Per tag, the words that would reach a member carrying it — and at what carried weight each begins to,
 * which is `Tier.threshold` divided by the word's own weight on that tag.
 */
private fun reportWhoComesForATag(vocabulary: Vocabulary) {
    println("=== If a new member carries this tag, these words reach it ===")
    // **Every aspect, and the aspect is named** — reach is per aspect, so a word wanting `monumental`
    // of the landmass says nothing whatever about a feature carrying the same tag.
    val wanters = mutableMapOf<String, MutableList<Triple<Word, Aspect, Double>>>()
    for (word in vocabulary.words.filter { it.tier.narrows }) {
        for (aspect in Aspect.entries) {
            for ((tag, weight) in word.restrictsIn(aspect)) {
                if (weight <= 0.0) continue
                // The carried weight at which this word starts to qualify the member.
                wanters.getOrPut(tag) { mutableListOf() } += Triple(word, aspect, word.tier.threshold / weight)
            }
        }
    }
    val strikers = mutableMapOf<String, MutableList<Word>>()
    for (word in vocabulary.words.filter { it.tier.narrows }) {
        for (tag in word.unwanted) strikers.getOrPut(tag) { mutableListOf() } += word
    }
    for (tag in (wanters.keys + strikers.keys).sorted()) {
        val wants = wanters[tag].orEmpty().sortedBy { it.first.name }
        val strikes = strikers[tag].orEmpty().distinctBy { it.name }.sortedBy { it.name }
        val wanted = wants.joinToString(", ") { (word, aspect, from) ->
            "%s@%s(from %.2f)".format(word.name, aspect.name.lowercase(), from)
        }
        val struck = strikes.joinToString(", ") { it.name }
        println("%-14s wanted by: %-72s struck by: %s".format(tag, wanted.ifEmpty { "-" }, struck.ifEmpty { "-" }))
    }
    println()
}

/**
 * Whether a word that strikes a tag reads the weight it was carried at. `Word.excludes` asks
 * `tags.containsKey`, so the answer should be no — printed rather than asserted, because a spike states
 * what it found.
 */
private fun reportExclusionIsWeightBlind(vocabulary: Vocabulary) {
    println("=== What a striking word removes, and at what carried weight ===")
    for (word in vocabulary.words.filter { it.unwanted.isNotEmpty() && it.tier.narrows }.sortedBy { it.name }) {
        for (aspect in weightedSets) {
            val members = vocabulary.askableIn(aspect)
            val struck = members.filter { member -> word.excludes(member, vocabulary.tagsOf(member)) }
            if (struck.isEmpty()) continue
            val weights = struck.map { member ->
                val carried = vocabulary.tagsOf(member)
                word.unwanted.mapNotNull { carried[it] }.maxOrNull() ?: 0.0
            }
            println(
                "%-12s strikes %2d of %3d in %-12s carried weights %.2f .. %.2f".format(
                    word.name, struck.size, members.size, aspect.name.lowercase(),
                    weights.min(), weights.max(),
                ),
            )
        }
    }
    println()
}


/**
 * **Tags carried by members that no word ever asks after.** Tagging a member is not what makes it
 * reachable — a word has to want the tag — so a tag with no wanter is carried weight that nothing reads.
 */
private fun reportTagsNobodyAsksAfter(vocabulary: Vocabulary) {
    println("=== Tags carried by members, and whether any word reads them ===")
    val carried = sortedSetOf<String>()
    for (aspect in weightedSets) {
        for (member in vocabulary.askableIn(aspect)) carried += vocabulary.tagsOf(member).keys
    }
    val read = mutableSetOf<String>()
    for (word in vocabulary.words.filter { it.tier.narrows }) {
        read += word.wanted
        read += word.unwanted
    }
    val leaned = mutableSetOf<String>()
    for (word in vocabulary.words) leaned += word.leanedTags
    val inert = carried.filterNot { it in read || it in leaned }
    println("carried by some member: ${carried.size}")
    println("wanted or struck by a narrowing word: ${carried.count { it in read }}")
    println("only ever leaned on: ${carried.count { it !in read && it in leaned }}")
    println("read by nothing at all: ${inert.size}")
    if (inert.isNotEmpty()) println("    ${inert.joinToString(", ")}")
    println()
}

/**
 * **The members a word almost reaches** — carried above nothing and below the tier's threshold. Under a
 * fixed threshold these are invisible; under a drawn one they are what varies from Age to Age.
 */
private fun reportMarginalCarriers(vocabulary: Vocabulary) {
    println("=== Members a word almost reaches (0 < pull < threshold) ===")
    for (word in vocabulary.words.filter { it.tier.narrows }.sortedBy { it.name }) {
        for (aspect in weightedSets) {
            if (word.restrictsIn(aspect).isEmpty()) continue
            val marginal = vocabulary.askableIn(aspect).mapNotNull { member ->
                val pull = word.pullIn(aspect, vocabulary.tagsOf(member))
                if (pull > 0.0 && pull < word.tier.threshold) member to pull else null
            }
            if (marginal.isEmpty()) continue
            val chances = marginal.map { (_, pull) -> pull / word.tier.threshold }
            println(
                "%-12s %-11s %2d almost-carriers, arriving in %.0f%%..%.0f%% of Ages".format(
                    word.name, aspect.name.lowercase(), marginal.size,
                    chances.min() * 100, chances.max() * 100,
                ),
            )
        }
    }
    println()
}
