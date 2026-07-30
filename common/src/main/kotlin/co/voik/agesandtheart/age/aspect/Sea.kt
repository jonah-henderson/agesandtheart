package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.RegionMap
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * What fills the space the shape leaves — a sea of something, or nothing at all.
 *
 * **An open aspect** (design §3.1): the sea *is* a block, so this holds a registry id rather than being an
 * enumeration. The three it used to be — void, sea, lava — were air, water and lava plus their tags, so
 * opening the aspect deleted the enum and moved nothing: the tags still live in `preset_tags/sea.json`, now
 * under the ids, and [NONE]/[WATER]/[LAVA] survive as names for the three anything in the mod refers to by name.
 *
 * That is what makes "a sea of creosote oil" an ordinary sentence rather than a feature: an exact word naming
 * any block in the pack resolves to a sea, with no per-mod work and no wrapper preset.
 *
 * The aspect chooses the **substance** only; the height comes from the [Terrain], which is the one thing that
 * knows where its own sea belongs. That split is what makes the aspects genuinely independent: any sea can be
 * poured over any terrain and land at a sensible level, so "Spire islands over a sea of lava" needs no
 * hand-written combination.
 *
 * [DEPTH] is the writer's one lever over the height, and it is enumerated rather than a number — three steps
 * either side of whatever the terrain considers normal (design §3.2).
 *
 * **This aspect was called `Medium` until 2026-07-29**, when it was renamed for two reasons worth keeping.
 * The first is scope: it was briefly going to own a third field, an *atmosphere* standing where air normally
 * stands, and once that was dropped for building Ages with nowhere to stand (see the plan's step 7) what was
 * left is exactly vanilla's `default_fluid` + `sea_level` — for which "medium" overreaches. The second is that
 * §3.1 asks for aspects named after what vanilla calls them, and `sea_level` is vanilla's own key. `Ocean` was
 * considered and rejected: in Minecraft that is a *biome* word, and it would collide with [Biomes].
 *
 * Note that a *plasma* sea is not a sea of plasma. Spire's green sea is ordinary water wearing a green biome,
 * so it is [Biomes]' business — substance and appearance really are separate axes, which the tag-driven design
 * proved early and by accident.
 */
data class Sea(override val id: ResourceLocation) : Referent {
    override val aspect = Aspect.SEA

    /**
     * A sea of air is the shape standing in open air, and there is no height to steer.
     *
     * Answered from the id rather than by resolving the block, which is the difference between a property that
     * reads a map and one that needs a booted Minecraft — and this is asked by [parameters], which a recipe
     * check and a datapack loader both reach without a game running. The three spellings of nothing all count.
     *
     * A sea naming a block this pack does not have is *not* empty by this test, and pours air anyway because
     * [substance] falls back to it. Same world either way; the fallback keeps its complaint.
     */
    val isEmpty: Boolean get() = id in EMPTY_IDS

    override val parameters: List<Parameter>
        get() = if (isEmpty) emptyList() else listOf(DEPTH)

    /**
     * What this sea is made of; air for a sea of nothing, which is how "nothing" joins a set of substances.
     *
     * A block the pack does not have resolves to air, loudly. That is a *content* problem — a mod removed
     * since the Age was written — and an Age whose sea silently vanished is easier to diagnose with a line in
     * the log than without one; refusing to open the world over it would be worse, since the rest of the Age is
     * still perfectly good.
     */
    fun substance(): BlockState = BuiltInRegistries.BLOCK.getOptional(id)
        .map { block -> block.defaultBlockState() }
        .orElseGet {
            Constants.LOG.warn("An Age names '{}' as its sea, which no block in this pack is", id)
            Blocks.AIR.defaultBlockState()
        }

    /**
     * This sea poured to [waterline] — the height being the terrain's to declare, not the sea's. A null
     * waterline is a shape standing in open air, and then nothing is filled whatever is named here: the
     * shape's own contract wins over the sentence, because a sea at no particular height is not a thing the
     * world can be.
     *
     * Where several terrains share an Age they may disagree about the height, or about whether there should be
     * a sea at all. One of them wins, drawn from the Age's seed — see
     * [co.voik.agesandtheart.age.AgeGeneration] — so a half-drowned Age is a real and intended outcome.
     */
    fun over(waterline: Int?, options: Options): SeaFill {
        if (waterline == null || isEmpty) return SeaFill.NONE
        return SeaFill.of(substance(), level = waterline + depthShift(options))
    }

    companion object {
        /**
         * The three the mod refers to by name.
         *
         * Spelled out rather than read back from [Blocks], which would be the obvious way and is the wrong
         * one: touching `Blocks` initialises a large slice of the game, so a `Sea` could not be *constructed*
         * without a booted Minecraft — and a recipe, a check and a datapack all want to build one without one.
         * These three ids are as fixed as anything in the game.
         */
        val NONE = Sea(ResourceLocation.withDefaultNamespace("air"))

        /** Every way the game spells nothing, all of which mean a shape standing in open air. */
        private val EMPTY_IDS = setOf("air", "cave_air", "void_air")
            .map(ResourceLocation::withDefaultNamespace)
            .toSet()

        /** An ordinary sea. */
        val WATER = Sea(ResourceLocation.withDefaultNamespace("water"))

        /** A sea of lava — survivable only from a distance. */
        val LAVA = Sea(ResourceLocation.withDefaultNamespace("lava"))

        /**
         * Names for "no sea at all", which is the one value an id cannot spell for itself.
         *
         * Every other sea is written as a block and needs no table: `lava` and `water` already parse as
         * registry ids, so they arrive through [named] unaided. Absence does not — `void` is not a block —
         * so it gets the two words a writer would reach for. This replaced a compatibility table mapping the
         * pre-open keys, which stopped being able to do that job the moment the aspect's own key moved from
         * `medium` to `sea`: a recipe written before then cannot be read regardless of what its values say.
         */
        private val NAMES_FOR_NOTHING: Map<String, Sea> = mapOf("none" to NONE, "void" to NONE)

        /**
         * The sea a recipe means by [key] — a registry id, or one of the words for having none.
         *
         * Null only for a key that is neither, which is a malformed recipe rather than a missing block: an id
         * naming nothing resolves fine here and falls back to air in [substance], where the complaint belongs.
         */
        fun named(key: String): Sea? =
            NAMES_FOR_NOTHING[key] ?: ResourceLocation.tryParse(key)?.let(::Sea)

        /**
         * Several seas poured to one [waterline], each filling its own territory.
         *
         * The height is shared and the substance is not, which is the design's asymmetry rather than a
         * shortcut: a terrain declares where its sea belongs, so an Age has exactly one waterline, but *what
         * the sea is* can change across it. Water meeting lava along a line at the same level, with no
         * barrier, is a thing Minecraft otherwise cannot show you.
         *
         * A sea of nothing among them contributes air, so "sea here, nothing there" is an ordinary sentence
         * and leaves genuine open space on one side of the seam.
         */
        fun pour(seas: List<Sea>, waterline: Int?, options: Options, map: RegionMap): SeaFill {
            if (waterline == null || seas.all { it.isEmpty }) return SeaFill.NONE
            return SeaFill.divided(
                seas.map { it.substance() },
                waterline + depthShift(options),
                map,
            )
        }

        val DEPTH = Parameter("depth", "normal", "shallow", "deep")

        /** Enough to redraw a coastline without drowning or stranding what the terrain built. */
        private const val DEPTH_STEP = 12

        private fun depthShift(options: Options): Int = when (options.of(DEPTH)) {
            "shallow" -> -DEPTH_STEP
            "deep" -> DEPTH_STEP
            else -> 0
        }
    }
}
