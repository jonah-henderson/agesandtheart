package co.voik.agesandtheart.age.word

import com.mojang.serialization.Codec
import net.minecraft.core.Registry
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.structure.StructureSet

/**
 * The words a pool holds — what a village writer's trade may hand over.
 *
 * **Two channels, because the corpus has two halves** — the same split [InkRequirement] makes. A
 * *derived* word names a registry entry, so a pool claims it with a tag on that entry, which is how a
 * pack stocks its own decorative block without touching us. An *authored* word names nothing, so it is
 * listed by name in a file.
 *
 * A pool is named by **a tag id spelled in full**, and its authored list is that tag's path under `art/`:
 * `agesandtheart:writer_stock/master` reads `art/writer_stock/master.json` and the
 * `writer_stock/master` tags on blocks, biomes and structure sets. The namespace is the tag's alone —
 * authored lists stack across packs the way every other `art/` directory does.
 */
class WriterStock(private val listed: Map<String, Set<String>>) {

    /**
     * Every word [pool] holds, in corpus order. Nothing is filtered here: what a page may carry is
     * [Withheld]'s question and is asked by whoever is drawing.
     */
    fun words(pool: Identifier, vocabulary: Vocabulary, registries: RegistryAccess): List<Word> {
        val authored = listed[pool.path].orEmpty()
        fun isListed(word: Word) = word.name in authored
        fun isTagged(word: Word) = carriesTag(word.id, pool, registries)
        return vocabulary.words.filter { isListed(it) || isTagged(it) }
    }

    /**
     * One word from [pool], or null where the pool holds nothing a page may carry.
     *
     * **Drawn uniformly**, as a pooled page always has been: a pool is already a statement about what
     * should turn up, and weighting it again would say the same thing twice.
     */
    fun draw(
        pool: Identifier,
        vocabulary: Vocabulary,
        registries: RegistryAccess,
        random: RandomSource,
    ): Word? {
        val held = words(pool, vocabulary, registries).filterNot { Withheld.holdsBack(it, registries) }
        return if (held.isEmpty()) null else held[random.nextInt(held.size)]
    }

    /**
     * Whether the thing [id] names carries the pool's tag. Every registry a derived word can be read off
     * is asked, since the id alone does not say which it came from.
     */
    private fun carriesTag(id: Identifier, pool: Identifier, registries: RegistryAccess): Boolean =
        taggedIn(registries, Registries.BLOCK, id, TagKey.create(Registries.BLOCK, pool)) ||
            taggedIn(registries, Registries.BIOME, id, TagKey.create(Registries.BIOME, pool)) ||
            taggedIn(registries, Registries.STRUCTURE_SET, id, TagKey.create(Registries.STRUCTURE_SET, pool))

    private fun <T : Any> taggedIn(
        registries: RegistryAccess,
        registry: ResourceKey<out Registry<T>>,
        id: Identifier,
        tag: TagKey<T>,
    ): Boolean {
        val holder = registries.lookup(registry).orElse(null)?.get(id)?.orElse(null) ?: return false
        return holder.`is`(tag)
    }

    companion object {
        /** Where a pack lists a pool's authored words, one file per pool. */
        const val STOCK_DIRECTORY = "art/writer_stock"

        private const val ART_PREFIX = "art/"

        private const val JSON_SUFFIX = ".json"

        val NONE = WriterStock(emptyMap())

        /** The authored half, stacked so a pack may add words to a pool without reprinting ours. */
        fun load(resources: ResourceManager, problems: MutableList<String>): WriterStock {
            val byPool = mutableMapOf<String, MutableSet<String>>()
            val stacks = resources.listResourceStacks(STOCK_DIRECTORY) { it.path.endsWith(JSON_SUFFIX) }
            for ((file, layers) in stacks.entries.sortedBy { it.key.toString() }) {
                // The key is the tag path a trade will name, so the file's own location *is* the pool id.
                val pool = file.path.removePrefix(ART_PREFIX).removeSuffix(JSON_SUFFIX)
                for (layer in layers) {
                    val page = ResourceParsing.parse(layer, file, StockPage.CODEC, problems) ?: continue
                    byPool.getOrPut(pool) { mutableSetOf() } += page.words
                }
            }
            return WriterStock(byPool.mapValues { (_, words) -> words.toSet() })
        }

        private data class StockPage(val words: List<String>) {
            companion object {
                val CODEC: Codec<StockPage> = Codec.STRING.listOf().xmap(::StockPage, StockPage::words)
                    .fieldOf("words").codec()
            }
        }
    }
}
