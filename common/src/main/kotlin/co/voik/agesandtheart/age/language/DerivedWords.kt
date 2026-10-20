package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.word.Speech
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
 * A word for everything a writer could point at, which nobody writes by hand (design §8): every block, biome,
 * placed feature, creature and structure set in the pack, every design of ours with a name, and the page of every
 * part of the world. Each means only what it names.
 */
object DerivedWords {
    /** Anything carrying this tag never becomes a word (§8.4). */
    val DOES_NOT_HAVE_A_WORD: Identifier = "does_not_have_a_word".location()

    private val WORDLESS_BLOCKS: TagKey<Block> = TagKey.create(Registries.BLOCK, DOES_NOT_HAVE_A_WORD)

    /** Blocks whose word means a sea and nothing else. */
    private val ONLY_SEAS: TagKey<Block> = TagKey.create(Registries.BLOCK, "only_a_sea".location())

    private val WORDLESS_SPAWNS: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, DOES_NOT_HAVE_A_WORD)

    /**
     * A word for every block: the sea it could be, what the rock, the ground and the structures could be made of, and
     * the formations it could be raised as.
     *
     * Laid bare, a block is always the rock where a world could be made of it, the ground's skin otherwise, and the
     * skin as well by chance; it is a sea only where it pours, and makes structures only where it is aimed at them. A
     * rock that does not pour now and then raises one of [GEOMETRIC_FORMATIONS] out of itself: `obsidian age`, an
     * obsidian obelisk.
     */
    fun blocks(): List<Word> = BuiltInRegistries.BLOCK.listElements()
        .filter { holder -> !holder.`is`(WORDLESS_BLOCKS) }
        .map { holder ->
            val id = holder.key().identifier()
            if (holder.`is`(ONLY_SEAS)) onlyASea(id) else substance(id, pours = holder.value().defaultBlockState().fluidState.isSource)
        }
        .toList()

    private fun onlyASea(id: Identifier) = Word(id, listOf(alwaysApplying(Effect.Chooses(Aspect.SEA, id.toString()))), Speech.MASS)

    private fun substance(id: Identifier, pours: Boolean): Word {
        val block = id.toString()
        val isARock = Materials.makesARock(block)
        val choosesTheSea = Effect.Chooses(Aspect.SEA, block)
        val asTheSea = if (pours) alwaysApplying(choosesTheSea) else onlyWhereAimed(choosesTheSea)
        val theRock = Effect.Sets(ParameterKey(Terrain.STONE.name, Aspect.TERRAIN), block)
        val theSkin = Effect.Sets(ParameterKey(Surface.MATERIAL.name, Aspect.SURFACE), block)
        val asTheRock = if (isARock) alwaysApplying(theRock) else null
        val asTheSkin = if (isARock) Meaning(listOf(theSkin)) else alwaysApplying(theSkin)
        val asTheStructures = onlyWhereAimed(Effect.Sets(ParameterKey(Structures.MATERIAL.name, Aspect.STRUCTURES), block))
        val standsAsAFormation = isARock && !pours
        val asFormations = if (standsAsAFormation) GEOMETRIC_FORMATIONS.map { raisedAs(it, block) } else emptyList()
        return Word(id, listOfNotNull(asTheSea, asTheRock, asTheSkin, asTheStructures) + asFormations, Speech.MASS)
    }

    /** One of [GEOMETRIC_FORMATIONS] made of [block], now and then where the block is laid bare. */
    private fun raisedAs(pattern: String, block: String) = Meaning(
        listOf(Effect.Mints(pattern, unstated = block, flows = false)),
        chanceToApply = Meaning.NEVER,
        unaimedChanceToApply = A_FORMATION_NOW_AND_THEN,
        asserts = true,
    )

    /** Our formations that are shapes rather than lumps, which a rock laid bare may be raised as. */
    val GEOMETRIC_FORMATIONS: List<String> = listOf("obelisks", "pyramids", "rings", "arches", "spikes").map { "agesandtheart:$it" }

    private const val A_FORMATION_NOW_AND_THEN = 0.25

    private fun onlyWhereAimed(effect: Effect) = Meaning(listOf(effect), unaimedChanceToApply = Meaning.NEVER)

    /** A word for every biome, which is datapack content and so needs the server's registries. */
    fun biomes(registries: HolderLookup.Provider): List<Word> =
        choosingEachEntryOf(registries, Registries.BIOME, Aspect.BIOMES, Speech.NOUN)

    /** A word for every placed feature, the unit a biome's list holds. */
    fun features(registries: HolderLookup.Provider): List<Word> =
        choosingEachEntryOf(registries, Registries.PLACED_FEATURE, Aspect.FEATURES, Speech.NOUN)

    /** A word for every structure set, named for the many it holds. */
    fun structures(registries: HolderLookup.Provider): List<Word> =
        choosingEachEntryOf(registries, Registries.STRUCTURE_SET, Aspect.STRUCTURES, Speech.PLURAL)

    /**
     * A word for every creature a world could grow — anything but vanilla's `MISC` category, which the natural
     * spawner never runs — and for the few [writable] ones built rather than born.
     */
    fun spawns(writable: Set<Identifier> = emptySet()): List<Word> = BuiltInRegistries.ENTITY_TYPE.listElements()
        .filter { holder -> !holder.`is`(WORDLESS_SPAWNS) }
        .filter { holder -> holder.value().category != MobCategory.MISC || holder.key().identifier() in writable }
        .map { holder -> choosing(holder.key().identifier(), Aspect.SPAWNS, holder.key().identifier().toString(), Speech.NOUN) }
        .toList()

    /** A word for every design of ours that is a thing with a name, said as the design says it is. */
    fun designs(): List<Word> = Aspect.entries.flatMap { aspect ->
        aspect.authored.mapNotNull { preset ->
            val said = preset.writtenWordFor ?: return@mapNotNull null
            choosing(said.location(), aspect, preset.key, preset.writtenSpeech)
        }
    }

    /**
     * The page of every part of the world, which means nothing of its own and takes what the part understands. The
     * aurora's and the rainbow's say the Age has one.
     */
    fun aspectPages(): List<Word> = Aspect.entries.map { aspect ->
        val phenomenon = ANNOUNCED[aspect]
        val announces = phenomenon?.let { listOf(Meaning(listOf(Effect.Chooses(Aspect.PHENOMENA, it)), mustAlwaysApply = true, asserts = true)) }
        Word(aspect.page.location(), announces.orEmpty())
    }

    private val ANNOUNCED = mapOf(Aspect.AURORA to "aurora", Aspect.RAINBOW to "rainbow")

    private fun <T : Any> choosingEachEntryOf(
        registries: HolderLookup.Provider,
        registry: ResourceKey<out Registry<T>>,
        aspect: Aspect,
        speech: Speech,
    ): List<Word> {
        val lookup = registries.lookupOrThrow(registry)
        val struckOut = lookup.struckOutBy(TagKey.create(registry, DOES_NOT_HAVE_A_WORD))
        return lookup.listElements()
            .filter { holder -> holder.key() !in struckOut }
            .map { holder -> holder.key().identifier().let { id -> choosing(id, aspect, id.toString(), speech) } }
            .toList()
    }

    private fun choosing(id: Identifier, aspect: Aspect, member: String, speech: Speech?) =
        Word(id, listOf(alwaysApplying(Effect.Chooses(aspect, member))), speech)

    private fun alwaysApplying(effect: Effect) = Meaning(listOf(effect), mustAlwaysApply = true)

    /**
     * Every entry a pack struck out with [DOES_NOT_HAVE_A_WORD], asked of the lookup's tags: a provider built
     * without a server carries none, and refuses to list them, which means nothing has been struck out.
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
