package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import net.minecraft.core.RegistryAccess
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.tags.TagKey
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.structure.StructureSet

/**
 * How potent an ink has to be to write a word at all (design §7.1.1).
 *
 * Named generically in code and titled by the lang file, so the setting's own name for the top ink is a
 * string a pack can change — the same rule [Script] follows.
 */
enum class InkTier(val key: String) : StringRepresentable {
    COMMON("common"),
    FINE("fine"),
    MASTERWORK("masterwork"),
    ;

    /** Whether an ink of [this] quality can write something demanding [required]. */
    fun satisfies(required: InkTier): Boolean = ordinal >= required.ordinal

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<InkTier> = StringRepresentable.fromEnum(InkTier::values)

        /** Written with anything. Most of the corpus. */
        val DEFAULT = COMMON
    }
}

/**
 * Which ink each word demands.
 *
 * Two channels, because the vocabulary has two halves. A **derived** word names a registry entry, so its
 * requirement is a tag on that entry — which is what lets another mod declare its own ore worth the good
 * ink without touching us. An **authored** word names nothing, so it is listed by name in
 * `art/ink/<tier>.json`; that is how the sky words get there, having no registry entry to tag.
 *
 * Everything unmentioned is [InkTier.COMMON]. Silence means cheap, so a pack that ships neither file nor
 * tag still works.
 */
class InkRequirement(private val authored: Map<String, InkTier>) {

    /**
     * What [word] demands. Tags are asked before the authored list because a pack that tags a block has
     * said something more specific than a name collision could.
     */
    fun tierFor(word: Word, registries: RegistryAccess): InkTier =
        fromTags(word.id, registries) ?: authored[word.name] ?: InkTier.DEFAULT

    /**
     * The tag answer, or null if nothing carries one. Every registry a derived word can come from is
     * asked, since the id alone does not say which it was read off — and an id in two registries wanting
     * different inks should get the dearer.
     */
    private fun fromTags(id: Identifier, registries: RegistryAccess): InkTier? {
        val found = listOfNotNull(
            tierIn(registries, Registries.BLOCK, id, BLOCK_TAGS),
            tierIn(registries, Registries.BIOME, id, BIOME_TAGS),
            tierIn(registries, Registries.STRUCTURE_SET, id, STRUCTURE_TAGS),
        )
        return found.maxByOrNull { it.ordinal }
    }

    private fun <T : Any> tierIn(
        registries: RegistryAccess,
        registry: ResourceKey<out Registry<T>>,
        id: Identifier,
        tags: Map<InkTier, TagKey<T>>,
    ): InkTier? {
        val holder = registries.lookup(registry).orElse(null)?.get(id)?.orElse(null) ?: return null
        // Dearest first: a thing in both tags is worth the better ink.
        return tags.entries.sortedByDescending { it.key.ordinal }.firstOrNull { holder.`is`(it.value) }?.key
    }

    companion object {
        /** Where a pack lists authored words by ink tier, one file per tier. */
        const val INK_DIRECTORY = "art/ink"

        private const val JSON_SUFFIX = ".json"

        val NONE = InkRequirement(emptyMap())

        /** `agesandtheart:requires_fine_ink` and `..._masterwork_ink`, on each registry a word can name. */
        private fun tagName(tier: InkTier): Identifier = "requires_${tier.key}_ink".location()

        private val GATED = listOf(InkTier.FINE, InkTier.MASTERWORK)

        val BLOCK_TAGS: Map<InkTier, TagKey<Block>> =
            GATED.associateWith { TagKey.create(Registries.BLOCK, tagName(it)) }

        val BIOME_TAGS: Map<InkTier, TagKey<Biome>> =
            GATED.associateWith { TagKey.create(Registries.BIOME, tagName(it)) }

        /** Structure *sets*, which is where derived structure words are read from. */
        val STRUCTURE_TAGS: Map<InkTier, TagKey<StructureSet>> =
            GATED.associateWith { TagKey.create(Registries.STRUCTURE_SET, tagName(it)) }

        /** The authored half, stacked so a pack may add words without reprinting ours. */
        fun load(resources: ResourceManager, problems: MutableList<String>): InkRequirement {
            val byWord = mutableMapOf<String, InkTier>()
            val stacks = resources.listResourceStacks(INK_DIRECTORY) { it.path.endsWith(JSON_SUFFIX) }
            for ((file, layers) in stacks.entries.sortedBy { it.key.toString() }) {
                val tierKey = file.path.removePrefix("$INK_DIRECTORY/").removeSuffix(JSON_SUFFIX)
                val tier = InkTier.entries.firstOrNull { it.key == tierKey }
                if (tier == null) {
                    problems += "$file names no ink tier ('$tierKey'); tiers are ${InkTier.entries.joinToString(" ") { it.key }}"
                    continue
                }
                for (layer in layers) {
                    val page = ResourceParsing.parse(layer, file, InkPage.CODEC, problems) ?: continue
                    for (word in page.words) byWord[word] = tier
                }
            }
            return InkRequirement(byWord.toMap())
        }

        private data class InkPage(val words: List<String>) {
            companion object {
                val CODEC: Codec<InkPage> = Codec.STRING.listOf().xmap(::InkPage, InkPage::words)
                    .fieldOf("words").codec()
            }
        }
    }
}
