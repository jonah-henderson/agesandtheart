package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.slot.Dressing
import co.voik.agesandtheart.age.slot.Medium
import co.voik.agesandtheart.age.slot.Parameter
import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.slot.SlotPreset
import co.voik.agesandtheart.location
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.material.Fluid

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

    private val FORBIDDEN_FLUIDS: TagKey<Fluid> = TagKey.create(Registries.FLUID, FORBIDDEN)

    /**
     * Every word this pack's fluids give the medium slot.
     *
     * **Fluids rather than every block**, which is the distinction between what a slot can *hold* and what
     * the pack hands us a word for. The medium slot is open, so an exact word can name any block at all and
     * a sea of packed ice is a legitimate if strange Age; but the things that obviously want a word are the
     * things you can have a *sea* of, and that is the fluid registry. Vanilla gives two; a pack gives as
     * many as it ships.
     *
     * Flowing variants are skipped: `minecraft:flowing_water` is an implementation detail of the same
     * substance, and a vocabulary offering both would be offering a writer a distinction that does not
     * exist. Tags are unbound until a server has loaded its datapacks and an unbound tag answers false, so
     * deriving offline fences nothing — the right default for a check asking what words *could* exist.
     */
    fun mediums(): List<Word> = BuiltInRegistries.FLUID.holders()
        .filter { holder -> holder.value().isSource(holder.value().defaultFluidState()) }
        .filter { holder -> !holder.`is`(FORBIDDEN_FLUIDS) }
        .map { holder -> referring(holder.key().location(), Slot.MEDIUM, ::Medium) }
        .toList()

    private val FORBIDDEN_BIOMES: TagKey<Biome> = TagKey.create(Registries.BIOME, FORBIDDEN)

    /**
     * Every word this pack's biomes give the dressing.
     *
     * **Needs a server, unlike [mediums].** Blocks and fluids are registered at class-init and readable from
     * `BuiltInRegistries`; biomes are *datapack* content and do not exist until a world has loaded its packs.
     * So this is the half of derived vocabulary an offline check cannot see, and the reason
     * `Vocabulary.load` takes registries optionally rather than requiring them.
     *
     * A biome word **sets a parameter** rather than naming a preset, which is the difference between the
     * dressing slot and the medium slot: a medium *is* its block, where a biome enriches a table
     * (design §3.1, §3.2). It is the populative case, so two of them accumulate and neither excludes
     * anything — naming never pins (Jonah), and `only` is the grammar's job.
     */
    fun biomes(registries: RegistryAccess): List<Word> = registries.lookupOrThrow(Registries.BIOME)
        .listElements()
        .filter { holder -> !holder.`is`(FORBIDDEN_BIOMES) }
        .map { holder -> setting(holder.key().location(), Slot.DRESSING, Dressing.BIOMES) }
        .toList()

    /**
     * A word that turns a knob rather than choosing a preset — how a referent reaches an *open parameter*.
     *
     * The value is the full `namespace:path`, never the bare one: a parameter is read back by
     * `ResourceLocation.tryParse` long after the word that set it is forgotten, so the recipe has to be
     * unambiguous even where the *word* was allowed to be short.
     */
    private fun setting(id: ResourceLocation, slot: Slot, parameter: Parameter) = Word(
        id = id,
        tier = Tier.EXACT,
        slots = setOf(slot),
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
     * A fluid and its block share an id throughout vanilla and by convention everywhere else, and a medium
     * is a *block* (§3.1) — so the same id serves as both, and confirming it would need a booted game this
     * does not otherwise require. A pack breaking that convention gets a medium that resolves to air and
     * says so in the log, which is the same failure as naming a block some other mod removed.
     */
    private fun referring(id: ResourceLocation, slot: Slot, referent: (ResourceLocation) -> SlotPreset) =
        Word(id = id, tier = Tier.EXACT, slots = setOf(slot), query = emptyMap(), names = referent(id).key)
}
