package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.RegionMap
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.roundToInt

/**
 * What fills the space the shape leaves — a sea of something, or nothing at all.
 *
 * An open aspect (design §3.1): a sea *is* a block, so this holds a registry id rather than an
 * enumeration, and any block in the pack resolves to a sea with no per-mod work.
 *
 * The aspect chooses the substance only; the height comes from the [Terrain], which is what lets any sea
 * be poured over any terrain and land at a sensible level. [DEPTH] is the writer's one lever over it.
 */
data class Sea(override val id: Identifier) : RegistryReference {
    override val aspect = Aspect.SEA

    override val registry = Registries.BLOCK

    /**
     * A sea of air is the shape standing in open air, with no height to steer. Answered from the id rather
     * than by resolving the block, so it needs no booted Minecraft — a recipe check and a datapack loader
     * both reach it without a game running.
     */
    val isEmpty: Boolean get() = id in EMPTY_IDS

    /**
     * What this sea is made of; air for a sea of nothing. A block the pack does not have resolves to air,
     * loudly — that is a content problem, and refusing to open the world over it would be worse.
     */
    fun substance(): BlockState = BuiltInRegistries.BLOCK.getOptional(Materials.laidAs(id))
        .map { block -> block.defaultBlockState() }
        .orElseGet {
            Constants.LOG.warn("An Age names '{}' as its sea, which no block in this pack is", id)
            Blocks.AIR.defaultBlockState()
        }

    /**
     * This sea poured to [waterline], the height being the terrain's to declare. A null waterline is a
     * shape standing in open air, and then nothing is filled whatever is named here.
     */
    fun over(waterline: Int?, options: Options, seed: Long): SeaFill {
        if (waterline == null || isEmpty) return SeaFill.NONE
        return SeaFill.of(substance(), level = waterline + depthShift(options, seed))
    }

    companion object {
        /**
         * The three the mod refers to by name. Spelled out rather than read from [Blocks], which
         * initialises a large slice of the game — a `Sea` must be constructible without a booted Minecraft.
         */
        val NONE = Sea(Identifier.withDefaultNamespace("air"))

        /** Every way the game spells nothing, all of which mean a shape standing in open air. */
        private val EMPTY_IDS = setOf("air", "cave_air", "void_air")
            .map(Identifier::withDefaultNamespace)
            .toSet()

        /** An ordinary sea. */
        val WATER = Sea(Identifier.withDefaultNamespace("water"))

        /** A sea of lava — survivable only from a distance. */
        val LAVA = Sea(Identifier.withDefaultNamespace("lava"))

        /** Plasma's (design §7.1.2): it consumes everything at its level and under it, and burns what is near. */
        val PLASMA = Sea(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "plasma"))

        /**
         * Names for "no sea at all", the one value an id cannot spell for itself — every other sea is
         * written as a block and arrives through [named] unaided.
         */
        private val NAMES_FOR_NOTHING: Map<String, Sea> = mapOf("none" to NONE, "void" to NONE)

        /**
         * The sea a recipe means by [key] — a registry id, or one of the words for having none. Null only
         * for a key that is neither, which is malformed rather than missing content.
         */
        fun named(key: String): Sea? =
            NAMES_FOR_NOTHING[key] ?: Identifier.tryParse(key)?.let(::Sea)

        /**
         * Several seas poured to one [waterline], each filling its own territory. The height is shared and
         * the substance is not, so water can meet lava along a line at the same level. A sea of nothing
         * contributes air, leaving genuine open space on one side of the seam.
         */
        fun pour(seas: List<Sea>, waterline: Int?, options: Options, map: RegionMap, seed: Long): SeaFill {
            if (waterline == null || seas.all { it.isEmpty }) return SeaFill.NONE
            return SeaFill(
                seas.map { it.substance() },
                waterline + depthShift(options, seed),
                map,
            )
        }

        /**
         * How far the sea stands above or below the level the terrain left it.
         *
         * **The aspect's own**, though a sea of nothing has no level worth raising. It was the member's
         * for a while, so that `Resolver.capabilityFactor` would lean the draw away from air whenever a
         * writer asked for a level — which was a preference wearing a capability's clothes. Air can hold a
         * depth perfectly well; the result is simply invisible. A word that wants a *deep* sea and not an
         * empty one says so where preferences belong, by pushing the `empty` tag away.
         */
        val DEPTH = Parameter.ranged(
            "depth",
            help = "How far above or below its usual level the sea stands.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "96 blocks down — drained to the basins"),
                Parameter.Landmark(-0.5, "12 down"),
                Parameter.Landmark(0.0, "its usual level"),
                Parameter.Landmark(0.5, "12 up"),
                Parameter.Landmark(1.0, "96 blocks up — an abyss over drowned land"),
            ),
        )

        /**
         * How far the ends of the range reach — see [depthShift] for why this is not the whole story.
         *
         * **96 rather than the 12 this used to be**, because design §7.1.2's deep sea needs a water column
         * past what deep water insists on (`DeepWater.DEEPEST_VANILLA_SEA`) and a twelve-block nudge
         * cannot get near it. The old constant's comment — "enough to redraw a coastline without drowning
         * or stranding what the terrain built" — was right about what the *middle* of this range is for,
         * and the curve below is what keeps it true there.
         */
        private const val DEEPEST_SHIFT = 96

        /**
         * The steer, curved so one parameter can do two jobs.
         *
         * **Cubic, and the exponent is chosen rather than picked**: it puts `±0.5` at ±12 blocks *exactly*
         * — the whole of what this lever used to be, landmark and all — while `±1.0` reaches ±96 and can
         * drown or drain a world. So the fine coastal trim survives in the middle of the range where every
         * word that wants a nudge sits, and only a word reaching for an end gets an abyss.
         *
         * Nothing quantises on the way in: [Options.steer] draws uniformly inside whatever span the word
         * wrote, so a curve here costs nothing anywhere else.
         */
        fun depthShift(options: Options, seed: Long): Int {
            val depth = options.steer(DEPTH, seed) ?: return AS_THE_TERRAIN_LEFT_IT
            val share = depth / Span.NATURAL_MOST
            return (share * share * share * DEEPEST_SHIFT).roundToInt()
        }

        private const val AS_THE_TERRAIN_LEFT_IT = 0
    }
}
