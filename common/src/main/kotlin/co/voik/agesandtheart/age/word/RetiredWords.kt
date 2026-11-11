package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.datapack.ResourceParsing
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.packs.resources.ResourceManager

/**
 * Words that have gone, and the word that now means what they did.
 *
 * A saved page, notebook, book or learned-word set names a word by id for as long as the save lives, so a
 * word that is merged or renamed is listed here instead of deleted.
 */
data class RetiredWords(private val successors: Map<String, String>, private val phrases: Map<String, List<String>> = emptyMap()) {
    /** The name [name] has become, following a chain of retirements, or null if it was not retired. */
    fun successorOf(name: String): String? {
        val bare = bareName(name)
        var current = successors[bare] ?: return null
        val seen = mutableSetOf(bare)
        while (seen.add(current)) current = successors[current] ?: return current
        return null
    }

    /** Every retired name, for a check that each still leads somewhere. */
    val names: Set<String> get() = successors.keys

    /** The words [name] is now said as, where it was retired into several: `rising_north` into `north rising`. */
    fun phraseOf(name: String): List<String>? = phrases[bareName(name)]

    /** Every name retired into several words. */
    val phraseNames: Set<String> get() = phrases.keys

    companion object {
        /** Where a pack lists the words it has retired. */
        const val RETIRED_FILE = "art/retired.json"

        private data class Retirements(val words: Map<String, String>, val phrases: Map<String, String>)

        private val CODEC: Codec<Retirements> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("words").forGetter(Retirements::words),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("phrases", emptyMap()).forGetter(Retirements::phrases),
            ).apply(instance, ::Retirements)
        }

        /** Our own words answer to their bare name and to `agesandtheart:name`. */
        private fun bareName(name: String): String = name.removePrefix("${Constants.MOD_ID}:")

        fun load(resources: ResourceManager, problems: MutableList<String>): RetiredWords {
            val files = resources.listResourceStacks("art") { it.path == RETIRED_FILE }
            val read = files.entries.sortedBy { it.key.toString() }.flatMap { (file, layers) ->
                layers.mapNotNull { layer -> ResourceParsing.parse(layer, file, CODEC, problems) }
            }
            val successors = read.flatMap { it.words.entries }.associate { (retired, successor) -> bareName(retired) to bareName(successor) }
            val phrases = read.flatMap { it.phrases.entries }.associate { (retired, phrase) ->
                bareName(retired) to phrase.split(' ').filter(String::isNotBlank).map(::bareName)
            }
            return RetiredWords(successors, phrases)
        }
    }
}
