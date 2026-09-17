package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.datapack.ResourceParsing
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.RandomSource

/**
 * One rarity of word, which is one `art/rarity/<name>.json`.
 *
 * The file names the words rather than words declaring their own rarity, so how hard a word is to *find*
 * stays separate from what it *does* — [Tier] keeps meaning precision and nothing else.
 */
data class RarityBucket(
    /** The file's name. */
    val name: String,
    /** This bucket's share of a draw, against its siblings'. */
    val weight: Double,
    val words: Set<String>,
    /**
     * Weight of the *entire* derived corpus as one entry here. One entry because there are hundreds of
     * them: listed individually they would drown every authored word in the bucket.
     */
    val derived: Double,
    /** Whether authored words no bucket lists land here. Exactly one bucket should say yes. */
    val catchAll: Boolean,
) {
    /** This bucket with [later] laid over it — a higher-priority pack retuning it. */
    fun mergedWith(later: RarityBucket): RarityBucket = RarityBucket(
        name = name,
        weight = later.weight,
        words = words + later.words,
        derived = later.derived,
        catchAll = later.catchAll,
    )

    companion object {
        private const val NO_DERIVED = 0.0

        fun codec(name: String): Codec<RarityBucket> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("weight").forGetter(RarityBucket::weight),
                Codec.STRING.listOf().optionalFieldOf("words", emptyList())
                    .forGetter { it.words.toList() },
                Codec.DOUBLE.optionalFieldOf("derived", NO_DERIVED).forGetter(RarityBucket::derived),
                Codec.BOOL.optionalFieldOf("catch_all", false).forGetter(RarityBucket::catchAll),
            ).apply(instance) { weight, words, derived, catchAll ->
                RarityBucket(name, weight, words.toSet(), derived, catchAll)
            }
        }
    }
}

/**
 * How likely each word is to turn up on a found page.
 *
 * A different axis from [Tier]: tier is what a word costs to write, rarity is how hard it is to come by.
 * A word may be evocative and rare, or exact and common.
 */
data class WordRarity(val buckets: List<RarityBucket>) {

    /** Which bucket [word] belongs to, for the page to say so. A derived word may be listed by its full id. */
    fun bucketOf(vocabulary: Vocabulary, word: Word): RarityBucket? {
        buckets.firstOrNull { word.name in it.words || word.id.toString() in it.words }?.let { return it }
        if (vocabulary.isDerived(word)) return buckets.filter { it.derived > 0.0 }.maxByOrNull { it.derived }
        return buckets.firstOrNull { it.catchAll }
    }

    /**
     * A word for a fresh page, or null if no bucket can offer one.
     *
     * Bucket first, then within it. A single flat table would make a bucket's share depend on how many
     * words it lists, so adding a rare word would make every other rare word rarer.
     */
    fun draw(vocabulary: Vocabulary, random: RandomSource, keep: (Word) -> Boolean = { true }): Word? {
        val resolved = buckets.mapNotNull { bucket -> resolve(bucket, vocabulary, keep)?.let { bucket to it } }
        val bucket = pick(resolved, random) { (_, entries) -> entries.weight } ?: return null
        return bucket.second.draw(random)
    }

    /** What a bucket can actually offer on this server, or null if that is nothing. */
    private fun resolve(bucket: RarityBucket, vocabulary: Vocabulary, keep: (Word) -> Boolean): Entries? {
        val spokenFor = buckets.flatMap { it.words }.toSet()
        val listed = bucket.words.mapNotNull(vocabulary::word).filter(keep).toMutableList()
        if (bucket.catchAll) {
            listed += vocabulary.authoredWords.filter { it.name !in spokenFor && keep(it) }
        }
        // **A word named in a bucket leaves the anonymous mass**, exactly as an authored one does. The
        // derived half was unguarded, so naming `diamond_ore` rare made it drawable as rare *and* as an
        // ordinary derived page — which is not what setting a rarity means.
        val derived = if (bucket.derived > 0.0) {
            vocabulary.derivedWords.filter { it.name !in spokenFor && it.id.toString() !in spokenFor && keep(it) }
        } else {
            emptyList()
        }
        if (listed.isEmpty() && derived.isEmpty()) return null
        // A bucket listing nothing this server has must not still win its full share.
        val share = if (listed.isEmpty()) bucket.derived else bucket.weight
        return Entries(share, listed.distinct(), derived, bucket.derived)
    }

    private data class Entries(
        val weight: Double,
        val listed: List<Word>,
        val derived: List<Word>,
        val derivedWeight: Double,
    ) {
        fun draw(random: RandomSource): Word? {
            val derivedShare = if (derived.isEmpty()) 0.0 else derivedWeight
            val total = listed.size + derivedShare
            if (total <= 0.0) return null
            // The derived mass sits past the listed words, so one roll settles which kind it is.
            val roll = random.nextDouble() * total
            if (roll >= listed.size) return derived[random.nextInt(derived.size)]
            return listed[roll.toInt().coerceAtMost(listed.size - 1)]
        }
    }

    private fun <T> pick(from: List<T>, random: RandomSource, weight: (T) -> Double): T? {
        val total = from.sumOf(weight)
        if (total <= 0.0) return null
        var roll = random.nextDouble() * total
        for (candidate in from) {
            roll -= weight(candidate)
            if (roll <= 0.0) return candidate
        }
        return from.lastOrNull()
    }

    companion object {
        /** Where a pack puts rarity buckets, one file per rarity. */
        const val RARITY_DIRECTORY = "art/rarity"

        /** The buckets in [resources], stacked so a pack may retune a weight without reprinting the file. */
        fun load(resources: ResourceManager, problems: MutableList<String>): WordRarity {
            val merged = mutableMapOf<String, RarityBucket>()
            val stacks = resources.listResourceStacks(RARITY_DIRECTORY, ResourceParsing::isJson)
            for ((file, layers) in stacks.entries.sortedBy { it.key.toString() }) {
                val name = ResourceParsing.nameUnder(file, RARITY_DIRECTORY)
                for (layer in layers) {
                    val bucket = ResourceParsing.parse(layer, file, RarityBucket.codec(name), problems)
                        ?: continue
                    val standing = merged[name]
                    merged[name] = standing?.mergedWith(bucket) ?: bucket
                }
            }
            val buckets = merged.values.sortedByDescending { it.weight }
            if (buckets.none { it.catchAll }) {
                problems += "no rarity bucket is the catch_all, so an authored word listed in none is unobtainable"
            }
            return WordRarity(buckets)
        }
    }
}
