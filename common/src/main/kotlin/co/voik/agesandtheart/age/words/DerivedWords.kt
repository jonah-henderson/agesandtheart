package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.language.Speech
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.location
import net.minecraft.core.HolderLookup
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.block.Block

/**
 * A word for everything a writer could point at, which nobody writes by hand (`notes/the-word-rework.md`): every block,
 * a material offering itself; every biome, placed feature, structure set and creature, a thing of its kind naming
 * itself; and every design of ours with a name, a landform offering itself to the land. What any of them means past
 * that is the word that takes it, and its kind.
 */
object DerivedWords {
    /** Anything carrying this tag never becomes a word. */
    val DOES_NOT_HAVE_A_WORD: Identifier = "does_not_have_a_word".location()

    private val WORDLESS_BLOCKS: TagKey<Block> = TagKey.create(Registries.BLOCK, DOES_NOT_HAVE_A_WORD)

    private val WORDLESS_CREATURES: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, DOES_NOT_HAVE_A_WORD)

    val BIOME: Identifier = "biome".location()
    val FEATURE: Identifier = "feature".location()
    val STRUCTURE: Identifier = "structure".location()
    val CREATURE: Identifier = "creature".location()

    /** Every block, offering itself as a material, for whatever takes one to say where it may go. */
    fun blocks(): List<Word> = BuiltInRegistries.BLOCK.listElements()
        .filter { holder -> !holder.`is`(WORDLESS_BLOCKS) }
        .map { holder -> holder.key().identifier() }
        .map { id -> Word(id, listOf(Sense(offers = listOf(Offer(CoreConcepts.BLOCK, Value.Text(id.toString()))))), Speech.MASS) }
        .toList()

    /** Every biome in [registries], which are datapack content and so want the server's. */
    fun biomes(registries: HolderLookup.Provider): List<Word> = thingsOf(registries, Registries.BIOME, Aspect.BIOMES, BIOME, Speech.NOUN)

    /**
     * Every placed feature, the unit a biome's list holds: a thing, but for a kind of tree named for its wood (`oak`,
     * `spruce`, `red_poplar`), which is a quality said of what holds it — `oak trees`, `oak age` — and never stands alone.
     * [tagsOf] answers a feature's tags, by key.
     */
    fun features(registries: HolderLookup.Provider, tagsOf: (String) -> Map<String, Double> = { emptyMap() }): List<Word> =
        thingsOf(registries, Registries.PLACED_FEATURE, Aspect.FEATURES, FEATURE, Speech.NOUN).map { word ->
            val member = word.id.toString()
            if (readsAsAQuality(word.name, tagsOf(member))) quality(word.id, member) else word
        }

    /** Whether a feature of [name] carrying [tags] is said of a thing rather than being one: wooded, and naming no thing. */
    private fun readsAsAQuality(name: String, tags: Map<String, Double>): Boolean {
        val isWooded = (tags[WOODED] ?: 0.0) > 0.0
        val namesAThing = name.split(NAME_SEPARATOR).any { it in NOUNS }
        return isWooded && !namesAThing
    }

    private fun quality(id: Identifier, member: String) =
        Word(id, listOf(Sense(offers = listOf(Offer(CoreConcepts.MEMBER, Value.Text(member))))), Speech.ADJECTIVE)

    private const val WOODED = "wooded"
    private const val NAME_SEPARATOR = '_'

    /** The words in a feature's name that make it a thing however wooded it is: `jungle_tree`, `trees_plains`, `jungle_bush`. */
    private val NOUNS = setOf("tree", "trees", "bush", "vegetation", "litter")

    /** Every structure set, named for the many it holds. */
    fun structures(registries: HolderLookup.Provider): List<Word> =
        thingsOf(registries, Registries.STRUCTURE_SET, Aspect.STRUCTURES, STRUCTURE, Speech.PLURAL)

    /**
     * Every creature a world could grow — anything but vanilla's `MISC` category, which the natural spawner never runs —
     * and the few [writable] ones built rather than born.
     */
    fun creatures(writable: Set<Identifier> = emptySet()): List<Word> = BuiltInRegistries.ENTITY_TYPE.listElements()
        .filter { holder -> !holder.`is`(WORDLESS_CREATURES) }
        .filter { holder -> holder.value().category != MobCategory.MISC || holder.key().identifier() in writable }
        .map { holder -> holder.key().identifier() }
        .map { id -> thing(id, Aspect.SPAWNS, id.toString(), CREATURE, Speech.NOUN) }
        .toList()

    /**
     * Every design of ours with a name, a landform or a hollow of the rock: a describing word offering itself to its part —
     * `cratered landmass`, `tunnelled underground`.
     */
    fun designs(): List<Word> = Aspect.entries.flatMap { aspect ->
        aspect.authored.mapNotNull { preset ->
            val said = preset.writtenWordFor?.location() ?: return@mapNotNull null
            Word(said, listOf(Sense(offers = listOf(Offer(CoreConcepts.MEMBER, Value.Text(preset.key))))), Speech.ADJECTIVE)
        }
    }

    private fun <T : Any> thingsOf(
        registries: HolderLookup.Provider,
        registry: ResourceKey<out Registry<T>>,
        part: Aspect,
        kind: Identifier,
        speech: Speech,
    ): List<Word> {
        val lookup = registries.lookupOrThrow(registry)
        val struckOut = lookup.struckOutBy(TagKey.create(registry, DOES_NOT_HAVE_A_WORD))
        return lookup.listElements()
            .filter { holder -> holder.key() !in struckOut }
            .map { holder -> holder.key().identifier().let { id -> thing(id, part, id.toString(), kind, speech) } }
            .toList()
    }

    /** A thing of [kind] naming [member] of [part], and offering it to whatever arranges or groups it: `zombie herds`. */
    private fun thing(id: Identifier, part: Aspect, member: String, kind: Identifier, speech: Speech) = Word(
        id,
        listOf(Sense(kind = kind, means = listOf(Effect.Chooses(part, member)), offers = listOf(Offer(CoreConcepts.MEMBER, Value.Text(member))))),
        speech,
    )

    /**
     * Every entry a pack struck out with [DOES_NOT_HAVE_A_WORD], asked of the lookup's tags: a provider built without a
     * server carries none, and refuses to list them, which means nothing has been struck out.
     */
    private fun <T : Any> HolderLookup.RegistryLookup<T>.struckOutBy(tag: TagKey<T>): Set<ResourceKey<T>> {
        val tagged = try {
            listTags().toList()
        } catch (tagsAreNotAvailable: UnsupportedOperationException) {
            return emptySet()
        }
        return tagged.firstOrNull { named -> named.key() == tag }
            ?.mapNotNull { holder -> holder.unwrapKey().orElse(null) }
            ?.toSet()
            .orEmpty()
    }
}
