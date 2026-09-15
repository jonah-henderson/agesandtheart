package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.StringRepresentable

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
     * The tag answer, or null if nothing carries one. Asked dearest first, so a thing carrying both tags,
     * or an id in two registries wanting different inks, gets the better ink.
     */
    private fun fromTags(id: Identifier, registries: RegistryAccess): InkTier? =
        TAG_NAMES.entries.firstOrNull { (_, tag) -> registries.carriesTagNamed(id, tag) }?.key

    companion object {
        /** Where a pack lists authored words by ink tier, one file per tier. */
        const val INK_DIRECTORY = "art/ink"

        private const val JSON_SUFFIX = ".json"

        /**
         * `agesandtheart:requires_masterwork_ink` and `..._fine_ink`, dearest first, on each registry a word
         * can name.
         */
        private val TAG_NAMES: Map<InkTier, Identifier> =
            listOf(InkTier.MASTERWORK, InkTier.FINE).associateWith { "requires_${it.key}_ink".location() }

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
