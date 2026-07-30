package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.AspectPreset
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.location
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.structure.StructureSet

/**
 * Vocabulary the pack gives us for free: a word for everything a writer could point at (design §8).
 *
 * This is the interoperability requirement, and it is a hard one — a modpack is the normal way this game
 * is played, and a vocabulary that only covered vanilla would make the mod feel smallest exactly where it
 * should feel largest. Jonah wrote a sea of Immersive Engineering's creosote oil in Mystcraft; that
 * sentence has to work here with no per-mod work on our part.
 *
 * **Derived words are referential, never evaluative** (§8.1). Each names one registry entry and nothing
 * else — it does not know whether creosote oil is foreboding and never asks. That is what makes an
 * intimidating requirement tractable: no quality-scoring of forty thousand blocks, no auditing a modpack,
 * and no way for a derived word to be *meaningless*, since it exists only because something carries the
 * name it gives. The judgement stays where §3.3 says it belongs, on the curated pool.
 *
 * It is also why they sit at the **Exact** tier without anything having to enforce it (§8.2): a derived
 * word is not answering a vague query, it *is* the precise answer. So "vagueness draws only from the
 * curated pool, precision can reach anything" falls out of the tier ladder rather than needing a rule.
 */
object DerivedWords {
    /**
     * A pack author's hard fence (§8.4): anything carrying this tag never becomes a word at all.
     *
     * Checked at *derivation* rather than at resolution, deliberately. A forbidden thing that became a word
     * and was then refused would be §3.3's silent drop wearing a diagnostic; a forbidden thing that was
     * never derived is honestly absent, and `/age words` says so by not listing it.
     *
     * We ship it empty. Which content must never be composable is a judgement about a pack's own economy,
     * and a default fence would be a guess that ages badly across four hundred of them.
     */
    val FORBIDDEN: ResourceLocation = "forbidden".location()

    private val FORBIDDEN_BLOCKS: TagKey<Block> = TagKey.create(Registries.BLOCK, FORBIDDEN)

    /**
     * **A word for every block in the pack** — the whole of what a writer can point at and say "made of
     * that" or "a sea of that".
     *
     * Every block, with no filter beyond [FORBIDDEN], because every block being representable is part of
     * what the mod promises. Any filter we invented would exclude somebody's obvious choice: "spikes made of
     * copper blocks" (Jonah) is not a stone, and a sea of packed ice is not a fluid.
     *
     * **One word, three capabilities, which is what dissolves a collision that would otherwise bite.** These
     * used to be two derivations — a word per *fluid* naming a sea, and nothing at all for materials. Add
     * the second naively and every fluid collides with its own block, because a fluid and its block share an
     * id throughout vanilla; [Vocabulary] would withdraw both bare names to keep mod load order from
     * deciding what `lava` means, and `a sea of lava` would stop being sayable. The collision is not real:
     * "lava" is *one concept*, and whether it is a sea or a substance is decided by where the word is aimed,
     * exactly as `stone` means one thing across the terrain and the dressing (§4.3.1).
     *
     * So a block word [names] the sea — an open aspect whose value *is* a block — and [sets] the material
     * on the aspects that wear one.
     *
     * **Only a liquid volunteers for the sea unprompted** (Jonah), and that carve-out is worth its
     * keep. Reaching all three aspects unaimed is the *logical* reading of "an Age of copper" — copper ground,
     * copper spires, a copper sea — and it is also a surprise nobody asked for the first time a beginner
     * names a block. So the aspects a word speaks to **unaimed** are narrowed to the material, while the
     * sea is left reachable by *aiming*: `Sea` is named for every block, so a sea of packed ice stays
     * sayable, it simply has to be asked for. Water and lava are unaffected, since a sea is what naming them
     * plainly has always meant.
     *
     * Blocks are registered at class-init, so unlike [biomes] this needs no server.
     */
    fun materials(): List<Word> = BuiltInRegistries.BLOCK.holders()
        .filter { holder -> !holder.`is`(FORBIDDEN_BLOCKS) }
        .map { holder -> substance(holder.key().location(), pours = holder.value().defaultBlockState().fluidState.isSource) }
        .toList()

    /**
     * A block, said as a word: the sea it could be, and the material it could be made into.
     *
     * [Word.names] carries the sea because an open aspect's value *is* the referent (§3.1); [Word.sets]
     * carries the material because a closed aspect's preset *consumes* one (§3.2). A word doing both is the
     * two halves of §8.1.1's "which aspect does `minecraft:deepslate` fill?" — the answer was always more than
     * one, and this is that answer written down.
     *
     * **The dressing dropped out of this in step 4.** The terrain and the dressing used to carry a `stone` each,
     * spelled alike so one word reached both; a material is the *fill* now, which only the terrain answers for,
     * so there is one place to reach and the dressing would only have been charged for a knob it no longer has.
     */
    private fun substance(id: ResourceLocation, pours: Boolean) = Word(
        id = id,
        tier = Tier.EXACT,
        // Where it speaks when nobody aimed it. The sea joins only for something that actually pours;
        // every block can still *be* the sea, but naming a paving slab should not flood the world.
        aspects = if (pours) setOf(Aspect.SEA, Aspect.TERRAIN) else setOf(Aspect.TERRAIN),
        query = emptyMap(),
        names = Sea(id).key,
        sets = mapOf(Terrain.STONE.name to id.toString()),
    )

    private val FORBIDDEN_BIOMES: TagKey<Biome> = TagKey.create(Registries.BIOME, FORBIDDEN)

    /**
     * Every word this pack's biomes give the [Biomes] aspect.
     *
     * **Needs a server, unlike [seas].** Blocks and fluids are registered at class-init and readable from
     * `BuiltInRegistries`; biomes are *datapack* content and do not exist until a world has loaded its packs.
     * So this is the half of derived vocabulary an offline check cannot see, and the reason
     * `Vocabulary.load` takes registries optionally rather than requiring them.
     *
     * A biome word **sets a parameter** rather than naming a preset, which is the difference between the
     * dressing aspect and the sea aspect: a sea *is* its block, where a biome enriches a table
     * (design §3.1, §3.2). It is the populative case, so two of them accumulate and neither excludes
     * anything — naming never pins (Jonah), and `only` is the grammar's job.
     */
    fun biomes(registries: RegistryAccess): List<Word> = registries.lookupOrThrow(Registries.BIOME)
        .listElements()
        .filter { holder -> !holder.`is`(FORBIDDEN_BIOMES) }
        .map { holder -> setting(holder.key().location(), Aspect.BIOMES, Biomes.GROWN) }
        .toList()

    private val FORBIDDEN_STRUCTURE_SETS: TagKey<StructureSet> = TagKey.create(Registries.STRUCTURE_SET, FORBIDDEN)

    /**
     * **A word for every structure set in the pack** — `villages`, `woodland_mansions`, `ocean_monuments`.
     *
     * Structures are a population like biomes (Jonah), so this is [biomes]'s twin in every respect: a datapack
     * registry, so it needs a server; a word that *sets a parameter* rather than naming a preset, since the
     * aspect's presets are only the base an Age starts from; and the exact tier, since a derived word names its
     * answer rather than asking for one.
     *
     * **Per structure *set*, not per structure** — Jonah's call, and see
     * [co.voik.agesandtheart.age.aspect.Structures] for the measurement behind it. Within a set the members are
     * biome variations of one idea, and the biome already chooses between them, so a word per structure would
     * mostly have been a word that did nothing. The words come out consistently plural, which reads well: *a
     * world of villages and woodland mansions, except ocean monuments.*
     */
    fun structures(registries: RegistryAccess): List<Word> = registries.lookupOrThrow(Registries.STRUCTURE_SET)
        .listElements()
        .filter { holder -> !holder.`is`(FORBIDDEN_STRUCTURE_SETS) }
        .map { holder -> setting(holder.key().location(), Aspect.STRUCTURES, Structures.BUILT) }
        .toList()

    /**
     * A word that turns a knob rather than choosing a preset — how a referent reaches an *open parameter*.
     *
     * The value is the full `namespace:path`, never the bare one: a parameter is read back by
     * `ResourceLocation.tryParse` long after the word that set it is forgotten, so the recipe has to be
     * unambiguous even where the *word* was allowed to be short.
     */
    private fun setting(id: ResourceLocation, aspect: Aspect, parameter: Parameter) = Word(
        id = id,
        tier = Tier.EXACT,
        aspects = setOf(aspect),
        query = emptyMap(),
        sets = mapOf(parameter.name to id.toString()),
    )

    /**
     * One word naming one thing — the shape every derived word takes.
     *
     * The word's own id is the referent's, so [Word.name] is the registry path and a writer says
     * `creosote`; [Vocabulary] decides whether that bare path is unambiguous enough to offer. No query,
     * because there is nothing to ask: the word names its answer.
     *
     * A fluid and its block share an id throughout vanilla and by convention everywhere else, and a sea
     * is a *block* (§3.1) — so the same id serves as both, and confirming it would need a booted game this
     * does not otherwise require. A pack breaking that convention gets a sea that resolves to air and
     * says so in the log, which is the same failure as naming a block some other mod removed.
     */
    private fun referring(id: ResourceLocation, aspect: Aspect, referent: (ResourceLocation) -> AspectPreset) =
        Word(id = id, tier = Tier.EXACT, aspects = setOf(aspect), query = emptyMap(), names = referent(id).key)
}
