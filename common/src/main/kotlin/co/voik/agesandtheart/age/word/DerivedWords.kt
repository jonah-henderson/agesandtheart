package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.AspectPreset
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.location
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
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
     * One word carries every capability — it [Word.names] the sea, an open aspect whose value *is* a
     * block, and [Word.sets] the material on the two aspects that wear one, the rock and the skin over it.
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
     * A block, said as a word: the sea it could be, and the material it could be made into. [Word.names]
     * carries the sea because an open aspect's value *is* the referent (§3.1); [Word.sets] carries the
     * material because a closed aspect's preset *consumes* one (§3.2).
     */
    private fun substance(id: Identifier, pours: Boolean) = Word(
        id = id,
        tier = Tier.EXACT,
        // Where it speaks when nobody aimed it: the rock, and the sea for something that actually pours.
        // Every block can still *be* the sea or the skin, but naming a paving slab should not flood the
        // world, and naming a rock should say what the world is made of rather than what it is painted
        // with — a surface is reached by aiming at it (`Grammar`'s `scopeFor`).
        aspects = if (pours) setOf(Aspect.SEA, Aspect.TERRAIN, Aspect.SURFACE) else setOf(Aspect.TERRAIN, Aspect.SURFACE),
        query = emptyMap(),
        names = Sea(id).key,
        sets = mapOf(Terrain.STONE.name to id.toString(), Surface.MATERIAL.name to id.toString()),
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
    fun biomes(registries: RegistryAccess): List<Word> = registries.lookupOrThrow(Registries.BIOME)
        .listElements()
        .filter { holder -> !holder.`is`(FORBIDDEN_BIOMES) }
        .map { holder -> setting(holder.key().identifier(), Aspect.BIOMES, Biomes.GROWN) }
        .toList()

    private val FORBIDDEN_FEATURES: TagKey<PlacedFeature> = TagKey.create(Registries.PLACED_FEATURE, FORBIDDEN)

    /**
     * **A word for every placed feature in the pack** — `ore_diamond`, `flower_meadow`, `lake_lava`.
     * [biomes]'s twin again, features being the third population.
     *
     * Per *placed* feature, because that is the unit a biome's list holds and so the only one a writer can
     * name and have mean something — see [co.voik.agesandtheart.age.aspect.PlacedFeature].
     */
    fun features(registries: RegistryAccess): List<Word> = registries.lookupOrThrow(Registries.PLACED_FEATURE)
        .listElements()
        .filter { holder -> !holder.`is`(FORBIDDEN_FEATURES) }
        .map { holder -> setting(holder.key().identifier(), Aspect.FEATURES, Features.GROWS) }
        .toList()

    private val FORBIDDEN_STRUCTURE_SETS: TagKey<StructureSet> = TagKey.create(Registries.STRUCTURE_SET, FORBIDDEN)

    /**
     * **A word for every structure set in the pack** — `villages`, `woodland_mansions`, `ocean_monuments`.
     * [biomes]'s twin in every respect, structures being a population too.
     *
     * Per structure *set*, not per structure — see [co.voik.agesandtheart.age.aspect.Structures] for why.
     */
    fun structures(registries: RegistryAccess): List<Word> = registries.lookupOrThrow(Registries.STRUCTURE_SET)
        .listElements()
        .filter { holder -> !holder.`is`(FORBIDDEN_STRUCTURE_SETS) }
        .map { holder -> setting(holder.key().identifier(), Aspect.STRUCTURES, Structures.BUILT) }
        .toList()

    /**
     * A word that turns a knob rather than choosing a preset — how a referent reaches an open parameter.
     * The value is the full `namespace:path`, never the bare one: a recipe is read back long after the
     * word that set it is forgotten, so it must be unambiguous even where the word could be short.
     */
    private fun setting(id: Identifier, aspect: Aspect, parameter: Parameter) = Word(
        id = id,
        tier = Tier.EXACT,
        aspects = setOf(aspect),
        query = emptyMap(),
        sets = mapOf(parameter.name to id.toString()),
    )

    /**
     * One word naming one thing. The word's id is the referent's, so [Word.name] is the registry path and
     * a writer says `creosote`; [Vocabulary] decides whether that bare path is unambiguous enough to offer.
     */
    private fun referring(id: Identifier, aspect: Aspect, referent: (Identifier) -> AspectPreset) =
        Word(id = id, tier = Tier.EXACT, aspects = setOf(aspect), query = emptyMap(), names = referent(id).key)
}
