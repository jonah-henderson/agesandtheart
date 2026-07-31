package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.location
import net.minecraft.core.Registry
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.structure.StructureSet

/**
 * Words the page channel may never hand out.
 *
 * **A timing fence, not a prohibition** (design §8.3). The scarab, the paper tree and nara sit on the
 * reproduction rung — you may name what you can make more of — and possessing one salvaged block is
 * exactly the state in which naming it is the duplication exploit (§8.4). Writing is already fenced,
 * since you must know a word to write it and knowledge comes from the two devices; **loot is not**, which
 * is what this closes.
 *
 * Deliberately *not* [DerivedWords.FORBIDDEN]: that tag is a pack author's hard fence and these stay
 * writable, as the summit reward.
 */
object PageExclusion {
    val TAG_NAME: Identifier = "not_on_pages".location()

    private val BLOCKS: TagKey<Block> = TagKey.create(Registries.BLOCK, TAG_NAME)
    private val BIOMES: TagKey<Biome> = TagKey.create(Registries.BIOME, TAG_NAME)
    private val STRUCTURE_SETS: TagKey<StructureSet> = TagKey.create(Registries.STRUCTURE_SET, TAG_NAME)

    /** Whether [word] is fenced off from pages. Authored words never are — they name no referent. */
    fun isExcluded(word: Word, registries: RegistryAccess): Boolean =
        carries(registries, Registries.BLOCK, word.id, BLOCKS) ||
            carries(registries, Registries.BIOME, word.id, BIOMES) ||
            carries(registries, Registries.STRUCTURE_SET, word.id, STRUCTURE_SETS)

    private fun <T : Any> carries(
        registries: RegistryAccess,
        registry: ResourceKey<out Registry<T>>,
        id: Identifier,
        tag: TagKey<T>,
    ): Boolean {
        val holder = registries.lookup(registry).orElse(null)?.get(id)?.orElse(null) ?: return false
        return holder.`is`(tag)
    }
}
