package co.voik.agesandtheart.age.slot

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.worldgen.field.AmbientMedium
import co.voik.agesandtheart.worldgen.field.RegionMap
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * What fills the space the shape leaves — a sea of something, or nothing at all.
 *
 * **An open slot** (design §3.1): the medium *is* a block, so this holds a registry id rather than being
 * an enumeration. The three it used to be — void, sea, lava — were air, water and lava plus their tags,
 * so opening the slot deleted the enum and moved nothing: the tags still live in
 * `preset_tags/medium.json`, now under the ids, and [VOID]/[SEA]/[LAVA] survive as names for the three
 * anything in the mod actually refers to by name.
 *
 * That is what makes "a sea of creosote oil" an ordinary sentence rather than a feature: an exact word
 * naming any block in the pack resolves to a medium, with no per-mod work and no wrapper preset.
 *
 * The slot chooses the **substance** only; the height comes from the [Landform], which is the one thing
 * that knows where its own sea belongs. That split is what makes the slots genuinely independent: any
 * medium can be poured over any landform and land at a sensible level, so "Spire islands over a sea of
 * lava" needs no hand-written combination.
 *
 * [DEPTH] is the writer's one lever over the height, and it is enumerated rather than a number — three
 * steps either side of whatever the landform considers normal (design §3.2).
 *
 * Note that a *plasma* sea is not a medium. Spire's green sea is ordinary water wearing a green biome, so
 * it belongs to [Dressing] — which is a nice accident of the tag-driven design proving itself early:
 * substance and appearance really are separate axes.
 */
data class Medium(override val id: ResourceLocation) : Referent {
    override val slot = Slot.MEDIUM

    /**
     * A medium of air is the shape standing in open air, and there is no height to steer.
     *
     * Answered from the id rather than by resolving the block, which is the difference between a property
     * that reads a map and one that needs a booted Minecraft — and this is asked by [parameters], which a
     * recipe check and a datapack loader both reach without a game running. The three spellings of nothing
     * all count.
     *
     * A medium naming a block this pack does not have is *not* empty by this test, and pours air anyway
     * because [substance] falls back to it. Same world either way; the fallback keeps its complaint.
     */
    val isEmpty: Boolean get() = id in EMPTY_IDS

    override val parameters: List<Parameter>
        get() = if (isEmpty) emptyList() else listOf(DEPTH)

    /**
     * What this medium is made of; air for a medium of nothing, which is how "nothing" joins a set of
     * substances.
     *
     * A block the pack does not have resolves to air, loudly. That is a *content* problem — a mod
     * removed since the Age was written — and an Age whose sea silently vanished is easier to diagnose
     * with a line in the log than without one; refusing to open the world over it would be worse, since
     * the rest of the Age is still perfectly good.
     */
    fun substance(): BlockState = BuiltInRegistries.BLOCK.getOptional(id)
        .map { block -> block.defaultBlockState() }
        .orElseGet {
            Constants.LOG.warn("An Age names '{}' as its medium, which no block in this pack is", id)
            Blocks.AIR.defaultBlockState()
        }

    /**
     * This medium poured to [waterline] — the height being the landform's to declare, not the medium's.
     * A null waterline is a shape standing in open air, and then nothing is filled whatever is named
     * here: the shape's own contract wins over the sentence, because a sea at no particular height is
     * not a thing the world can be.
     *
     * Where several landforms share an Age they may disagree about the height, or about whether there
     * should be a sea at all. One of them wins, drawn from the Age's seed — see
     * [co.voik.agesandtheart.age.AgeGeneration] — so a half-drowned Age is a real and intended outcome.
     */
    fun over(waterline: Int?, options: Options): AmbientMedium {
        if (waterline == null || isEmpty) return AmbientMedium.VOID
        return AmbientMedium.sea(substance(), level = waterline + depthShift(options))
    }

    companion object {
        /**
         * The three the mod refers to by name.
         *
         * Spelled out rather than read back from [Blocks], which would be the obvious way and is the wrong
         * one: touching `Blocks` initialises a large slice of the game, so a `Medium` could not be
         * *constructed* without a booted Minecraft — and a recipe, a check and a datapack all want to build
         * one without one. These three ids are as fixed as anything in the game.
         */
        val VOID = Medium(ResourceLocation.withDefaultNamespace("air"))

        /** Every way the game spells nothing, all of which mean a shape standing in open air. */
        private val EMPTY_IDS = setOf("air", "cave_air", "void_air")
            .map(ResourceLocation::withDefaultNamespace)
            .toSet()

        /** An ordinary sea. */
        val SEA = Medium(ResourceLocation.withDefaultNamespace("water"))

        /** A sea of lava — survivable only from a distance. */
        val LAVA = Medium(ResourceLocation.withDefaultNamespace("lava"))

        /**
         * The three by the names they had before the slot opened, so a recipe written then still reads.
         *
         * Kept indefinitely rather than dropped after a migration pass: an Age is only ever rebuilt from
         * its recipe, recipes live in save files we do not control, and three map entries are a much
         * smaller thing to carry than a save that will not open.
         */
        private val FORMER_KEYS: Map<String, Medium> =
            mapOf("void" to VOID, "sea" to SEA, "lava" to LAVA)

        /**
         * The medium a recipe means by [key] — a registry id, or one of the three names the slot used
         * before it opened.
         *
         * Null only for a key that is neither, which is a malformed recipe rather than a missing block:
         * an id naming nothing resolves fine here and falls back to air in [substance], where the
         * complaint belongs.
         */
        fun named(key: String): Medium? =
            FORMER_KEYS[key] ?: ResourceLocation.tryParse(key)?.let(::Medium)

        /**
         * Several mediums poured to one [waterline], each filling its own territory.
         *
         * The height is shared and the substance is not, which is the design's asymmetry rather than a
         * shortcut: a landform declares where its sea belongs, so an Age has exactly one waterline, but
         * *what the sea is* can change across it. Water meeting lava along a line at the same level,
         * with no barrier, is a thing Minecraft otherwise cannot show you.
         *
         * A medium of nothing among them contributes air, so "sea here, nothing there" is an ordinary
         * sentence and leaves genuine open space on one side of the seam.
         */
        fun pour(mediums: List<Medium>, waterline: Int?, options: Options, map: RegionMap): AmbientMedium {
            if (waterline == null || mediums.all { it.isEmpty }) return AmbientMedium.VOID
            return AmbientMedium.seas(
                mediums.map { it.substance() },
                waterline + depthShift(options),
                map,
            )
        }

        val DEPTH = Parameter("depth", "normal", "shallow", "deep")

        /** Enough to redraw a coastline without drowning or stranding what the landform built. */
        private const val DEPTH_STEP = 12

        private fun depthShift(options: Options): Int = when (options.of(DEPTH)) {
            "shallow" -> -DEPTH_STEP
            "deep" -> DEPTH_STEP
            else -> 0
        }
    }
}
