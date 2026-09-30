package co.voik.agesandtheart.age.word

import net.minecraft.world.item.Rarity
import co.voik.agesandtheart.datapack.ResourceParsing
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.ExtraCodecs
import net.minecraft.util.RandomSource

/**
 * One rarity of word, which is one `art/rarity/<name>.json`.
 *
 * The file names the words rather than words declaring their own rarity, so how hard a word is to *find*
 * stays separate from what it *does*.
 */
data class RarityBucket(
    /** The file's name. */
    val name: String,
    /** This bucket's share of found pages, against its siblings'. */
    val weight: Double,
    /** Words listed here, by name or — for a derived word — by full id. */
    val words: Set<String>,
    /** Whether every word no bucket lists lands here. Exactly one bucket should say yes. */
    val isDefault: Boolean,
    /** The vanilla rarity a page of this bucket carries, which is what colours its name. */
    val itemRarity: Rarity = Rarity.COMMON,
) {
    /** This bucket with [later] laid over it — a higher-priority pack retuning it. */
    fun mergedWith(later: RarityBucket): RarityBucket = RarityBucket(
        name = name,
        weight = later.weight,
        words = words + later.words,
        isDefault = later.isDefault,
        itemRarity = later.itemRarity,
    )

    fun lists(word: Word): Boolean = word.name in words || word.id.toString() in words

    companion object {
        fun codec(name: String): Codec<RarityBucket> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("weight").forGetter(RarityBucket::weight),
                Codec.STRING.listOf().optionalFieldOf("words", emptyList())
                    .forGetter { it.words.toList() },
                Codec.BOOL.optionalFieldOf("default", false).forGetter(RarityBucket::isDefault),
                Rarity.CODEC.optionalFieldOf("item_rarity", Rarity.COMMON).forGetter(RarityBucket::itemRarity),
            ).apply(instance) { weight, words, isDefault, itemRarity ->
                RarityBucket(name, weight, words.toSet(), isDefault, itemRarity)
            }
        }
    }
}

/**
 * How likely each word is to turn up on a found page: a bucket is rolled by weight, then a word within it
 * uniformly. Every word is in exactly one bucket — the one listing it, or else the default.
 *
 * A different axis from [Word.price]: price is what a word costs to write, rarity is how hard it is to come by.
 * A word may be evocative and rare, or exact and common.
 */
data class WordRarity(
    val buckets: List<RarityBucket>,
    /** Authored words kept off found pages, by name — see [CannotAppearInLoot]. */
    val keptOutOfLoot: Set<String> = emptySet(),
) {
    private val default: RarityBucket? get() = buckets.firstOrNull { it.isDefault }

    /** Which bucket [word] belongs to, for the page to say so. */
    fun bucketOf(word: Word): RarityBucket? = buckets.firstOrNull { it.lists(word) } ?: default

    /**
     * A word for a fresh page, or null if no bucket can offer one.
     *
     * Bucket first, then within it, so a bucket's share of pages is its weight whatever it lists. [only]
     * narrows the roll to the buckets named, which is how a loot table asks for rare pages. [keep] is
     * asked only of the bucket rolled, and a bucket it empties is dropped and the roll made again.
     */
    fun draw(
        vocabulary: Vocabulary,
        random: RandomSource,
        only: Set<String>? = null,
        keep: (Word) -> Boolean = { true },
    ): Word? {
        val byBucket = vocabulary.words.groupBy(::bucketOf)
        val offered = buckets.filter { only == null || it.name in only }.toMutableList()
        while (true) {
            val bucket = pick(offered, random, RarityBucket::weight) ?: return null
            val words = byBucket[bucket].orEmpty().filter(keep)
            if (words.isNotEmpty()) return words[random.nextInt(words.size)]
            offered -= bucket
        }
    }

    /** What [bucket] can actually offer on this server. */
    fun wordsIn(bucket: RarityBucket, vocabulary: Vocabulary): List<Word> =
        vocabulary.words.filter { bucketOf(it) == bucket }

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

        /** The authored words no found page may carry, which a tag cannot hold since they name nothing. */
        const val KEPT_OUT_OF_LOOT_FILE = "art/cannot_appear_in_loot.json"

        /** A loot function's `rarity`: one bucket's name, or a list of them. */
        val BUCKET_NAMES_CODEC: Codec<Set<String>> =
            ExtraCodecs.compactListCodec(Codec.STRING).xmap({ it.toSet() }, { it.toList() })

        private val KEPT_OUT_CODEC: Codec<List<String>> = RecordCodecBuilder.create { instance ->
            instance.group(Codec.STRING.listOf().fieldOf("words").forGetter { it }).apply(instance) { it }
        }

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
            val defaults = buckets.count { it.isDefault }
            if (defaults != 1) {
                problems += "$defaults rarity buckets say `default`, and exactly one should: it is where every " +
                    "word no bucket lists is found"
            }
            return WordRarity(buckets, keptOutOfLoot(resources, problems))
        }

        /** Every pack's list, together — a pack may keep its own words out but not let ours back in. */
        private fun keptOutOfLoot(resources: ResourceManager, problems: MutableList<String>): Set<String> {
            val files = resources.listResourceStacks("art") { it.path == KEPT_OUT_OF_LOOT_FILE }
            return files.flatMap { (file, layers) ->
                layers.flatMap { layer -> ResourceParsing.parse(layer, file, KEPT_OUT_CODEC, problems).orEmpty() }
            }.toSet()
        }
    }
}
