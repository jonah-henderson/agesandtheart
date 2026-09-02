package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.location
import net.minecraft.core.HolderLookup
import net.minecraft.resources.ResourceKey
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.structure.StructureSet

/**
 * Vocabulary the pack gives us for free: a word for everything a writer could point at (design §8).
 *
 * **Derived words are referential, never evaluative** (§8.1). Each names one registry entry and nothing
 * else — it does not know whether creosote oil is foreboding and never asks. So there is no
 * quality-scoring of forty thousand blocks, and no way for a derived word to be meaningless.
 *
 * It is also why they sit at the **Exact** tier with nothing enforcing it (§8.2): a derived word is not
 * answering a vague query, it *is* the precise answer.
 */
object DerivedWords {
    /**
     * A pack author's hard fence (§8.4): anything carrying this tag never becomes a word at all. Checked
     * at *derivation* — a forbidden thing refused at resolution would be §3.3's silent drop wearing a
     * diagnostic, where one never derived is honestly absent. Shipped empty.
     */
    val FORBIDDEN: Identifier = "forbidden".location()

    private val FORBIDDEN_BLOCKS: TagKey<Block> = TagKey.create(Registries.BLOCK, FORBIDDEN)

    /**
     * **A word for every block in the pack** — what a writer points at to say "made of that" or "a sea of
     * that". No filter beyond [FORBIDDEN]: any filter we invented would exclude somebody's obvious choice,
     * since "spikes made of copper blocks" is not a stone and a sea of packed ice is not a fluid.
     *
     * One word carries every capability — it is an entry of the block registry ([Word.entryOf]), which is
     * what lets it *be* the sea, an open aspect whose value is a block; and it [Word.sets] the material on
     * the two aspects that wear one, the rock and the skin over it.
     * A fluid and its block share an id throughout vanilla, so deriving them separately would collide and
     * cost both bare names.
     *
     * **Only a liquid volunteers for the sea unprompted**: every block can still *be* the sea, but naming
     * a paving slab should not flood the world, so a solid reaches it only by being aimed there.
     *
     * Blocks are registered at class-init, so unlike [biomes] this needs no server.
     */
    fun materials(): List<Word> = BuiltInRegistries.BLOCK.listElements()
        .filter { holder -> !holder.`is`(FORBIDDEN_BLOCKS) }
        .map { holder -> substance(holder.key().identifier(), pours = holder.value().defaultBlockState().fluidState.isSource) }
        .toList()

    /**
     * A block, said as a word: the sea it could be, and the material it could be made into. [Word.entryOf]
     * carries the sea, because an open aspect's value *is* the referent (§3.1) and this word already
     * spells it — the id is the word's own name, so there is nothing left to write down but which registry
     * it came from. [Word.sets] carries the material, because a closed aspect's preset *consumes* one (§3.2).
     */
    private fun substance(id: Identifier, pours: Boolean) = Word(
        id = id,
        tier = Tier.EXACT,
        // Where it speaks when nobody aimed it: the rock, and the sea for something that actually pours.
        // Every block can still *be* the sea or the skin, but naming a paving slab should not flood the
        // world, and naming a rock should say what the world is made of rather than what it is painted
        // with — a surface is reached by aiming at it (`Grammar`'s `scopeFor`).
        aspects = buildSet {
            add(Aspect.SURFACE)
            if (pours) add(Aspect.SEA)
            // **Only if a world could be made of it** (§3.2, [Materials]). A word that claimed the rock and
            // was refused there would reach the landmass, set nothing and cost a page — §3.3's silent drop.
            // A sign keeps its word and keeps every other use of it; what it stops being is a world.
            if (Materials.makesAWorld(id.toString())) add(Aspect.TERRAIN)
        },
        everywhere = emptyMap(),
        // **A block, and nothing else.** The sea is the one aspect whose values are blocks, so saying so
        // reaches it and reaches nothing else; without it every open aspect would take this id for one of
        // its own — a structure set, a biome, a placed feature, a creature.
        entryOf = Registries.BLOCK,
        sets = buildMap {
            put(Surface.MATERIAL.name, id.toString())
            if (Materials.makesAWorld(id.toString())) put(Terrain.STONE.name, id.toString())
        },
    )

    private val FORBIDDEN_BIOMES: TagKey<Biome> = TagKey.create(Registries.BIOME, FORBIDDEN)

    /**
     * Every word this pack's biomes give the [Biomes] aspect.
     *
     * **Needs a server, unlike [materials]**: biomes are datapack content and do not exist until a world
     * has loaded its packs, which is why `Vocabulary.load` takes registries optionally.
     *
     * A biome word **sets a parameter** rather than naming a preset — a sea *is* its block, where a biome
     * enriches a table (§3.1, §3.2). Populative, so two accumulate and neither excludes anything.
     */
    fun biomes(registries: HolderLookup.Provider): List<Word> = registries.lookupOrThrow(Registries.BIOME).let { lookup ->
        val struckOut = lookup.struckOutBy(FORBIDDEN_BIOMES)
        lookup.listElements()
            .filter { holder -> holder.key() !in struckOut }
            .map { holder -> setting(holder.key().identifier(), Aspect.BIOMES, Biomes.GROWN) }
            .toList()
    }

    private val FORBIDDEN_FEATURES: TagKey<PlacedFeature> = TagKey.create(Registries.PLACED_FEATURE, FORBIDDEN)

    /**
     * **A word for every placed feature in the pack** — `ore_diamond`, `flower_meadow`, `lake_lava`.
     * [biomes]'s twin again, features being the third population.
     *
     * Per *placed* feature, because that is the unit a biome's list holds and so the only one a writer can
     * name and have mean something — see [co.voik.agesandtheart.age.aspect.PlacedFeature].
     */
    fun features(registries: HolderLookup.Provider): List<Word> = registries.lookupOrThrow(Registries.PLACED_FEATURE).let { lookup ->
        val struckOut = lookup.struckOutBy(FORBIDDEN_FEATURES)
        lookup.listElements()
            .filter { holder -> holder.key() !in struckOut }
            .map { holder -> setting(holder.key().identifier(), Aspect.FEATURES, Features.PLACES) }
            .toList()
    }

    private val FORBIDDEN_SPAWNS: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, FORBIDDEN)

    /**
     * **A word for every creature in the pack** — `zombie`, `axolotl`, `piglin_brute`. The fourth
     * population, and the one a writer is likeliest to name without being taught.
     *
     * Built-in like [materials] rather than datapack content, so this needs no server — but it is derived
     * beside the others because a corpus is loaded once.
     */
    fun spawns(writable: Set<Identifier> = emptySet()): List<Word> = BuiltInRegistries.ENTITY_TYPE.listElements()
        .filter { holder -> !holder.`is`(FORBIDDEN_SPAWNS) }
        .filter { holder -> livesSomewhere(holder.value()) || holder.key().identifier() in writable }
        .map { holder -> setting(holder.key().identifier(), Aspect.SPAWNS, Spawns.LIVES) }
        .toList()

    /**
     * Whether this is a creature a world could grow, as opposed to an arrow, a boat or an item frame.
     *
     * **`MobCategory.MISC` is vanilla's own answer** — the natural spawner runs every category but that
     * one — so seventy-two of the hundred and fifty-seven entity types could never arrive however they
     * were written, and each was a page a writer could find, learn and spend for nothing.
     *
     * **Save the few `art/spawning.json` names**, which is how the golems became writable: they are misc
     * because they are built rather than born, and the file that says how a creature arrives is the same
     * file that says one may be asked for at all. Adding another is a line of data.
     */
    private fun livesSomewhere(type: EntityType<*>): Boolean = type.category != MobCategory.MISC

    private val FORBIDDEN_STRUCTURE_SETS: TagKey<StructureSet> = TagKey.create(Registries.STRUCTURE_SET, FORBIDDEN)

    /**
     * **A word for every structure set in the pack** — `villages`, `woodland_mansions`, `ocean_monuments`.
     * [biomes]'s twin in every respect, structures being a population too.
     *
     * Per structure *set*, not per structure — see [co.voik.agesandtheart.age.aspect.Structures] for why.
     */
    fun structures(registries: HolderLookup.Provider): List<Word> = registries.lookupOrThrow(Registries.STRUCTURE_SET).let { lookup ->
        val struckOut = lookup.struckOutBy(FORBIDDEN_STRUCTURE_SETS)
        lookup.listElements()
            .filter { holder -> holder.key() !in struckOut }
            .map { holder -> setting(holder.key().identifier(), Aspect.STRUCTURES, Structures.BUILT) }
            .toList()
    }

    /**
     * A word that turns a parameter rather than choosing a preset — how a referent reaches an open parameter.
     * The value is the full `namespace:path`, never the bare one: a recipe is read back long after the
     * word that set it is forgotten, so it must be unambiguous even where the word could be short.
     */
    private fun setting(id: Identifier, aspect: Aspect, parameter: Parameter) = Word(
        id = id,
        tier = Tier.EXACT,
        aspects = setOf(aspect),
        everywhere = emptyMap(),
        sets = mapOf(parameter.name to id.toString()),
    )

    /**
     * **A word for every design this pack wrote that is a thing with a name**, rather than a quality of
     * one — see [co.voik.agesandtheart.age.aspect.AuthoredPreset.writtenWordFor], which decides which
     * those are and what each is said as.
     *
     * The same bargain as [materials], one registry in: a landform arriving in the game arrives with the
     * page that means it, so the corpus cannot fall behind the world. Seventeen word files said nothing
     * but their own name before this, and a preset added without one was reachable only by chance.
     *
     * These *are* named rather than being their own id — `spires` means `spire_islands` — so they carry
     * [Word.meansExactly] where a block carries [Word.entryOf].
     */
    fun designs(): List<Word> = Aspect.entries.flatMap { aspect ->
        aspect.authored.mapNotNull { preset ->
            val said = preset.writtenWordFor ?: return@mapNotNull null
            Word(
                id = said.location(),
                tier = Tier.EXACT,
                aspects = setOf(aspect),
                everywhere = emptyMap(),
                meansExactly = mapOf(aspect to preset.key),
            )
        }
    }

    /**
     * Every entry a pack struck out with `agesandtheart:forbidden` (§8.4).
     *
     * Asked of the **lookup's** tag list rather than of each holder's back-reference. `Holder.is(TagKey)`
     * needs the holder's tag set bound, which a provider built without a server never does — it threw
     * "Tags not bound" and took the whole derived corpus with it, so these three populations could only be
     * read where they were hardest to check. A provider carrying no tags simply forbids nothing, which is
     * what an empty-by-default exclusion means anyway.
     */
    private fun <T : Any> HolderLookup.RegistryLookup<T>.struckOutBy(tag: TagKey<T>): Set<ResourceKey<T>> {
        // `RegistrySetBuilder.EmptyTagRegistryLookup` refuses outright — "Tags are not available in
        // datagen" — where a holder's unbound tag set threw "Tags not bound". Either way the provider
        // carries no tags, so nothing has been struck out.
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
