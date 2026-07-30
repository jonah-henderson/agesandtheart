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
 * An open aspect (design §3.1): a sea *is* a block, so this holds a registry id rather than an
 * enumeration, and any block in the pack resolves to a sea with no per-mod work.
 *
 * The aspect chooses the substance only; the height comes from the [Terrain], which is what lets any sea
 * be poured over any terrain and land at a sensible level. [DEPTH] is the writer's one lever over it.
 */
data class Sea(override val id: ResourceLocation) : Referent {
    override val aspect = Aspect.SEA

    /**
     * A sea of air is the shape standing in open air, with no height to steer. Answered from the id rather
     * than by resolving the block, so it needs no booted Minecraft — [parameters] asks it, and a recipe
     * check and a datapack loader both reach that without a game running.
     */
    val isEmpty: Boolean get() = id in EMPTY_IDS

    override val parameters: List<Parameter>
        get() = if (isEmpty) emptyList() else listOf(DEPTH)

    /**
     * What this sea is made of; air for a sea of nothing. A block the pack does not have resolves to air,
     * loudly — that is a content problem, and refusing to open the world over it would be worse.
     */
    fun substance(): BlockState = BuiltInRegistries.BLOCK.getOptional(id)
        .map { block -> block.defaultBlockState() }
        .orElseGet {
            Constants.LOG.warn("An Age names '{}' as its sea, which no block in this pack is", id)
            Blocks.AIR.defaultBlockState()
        }

    /**
     * This sea poured to [waterline], the height being the terrain's to declare. A null waterline is a
     * shape standing in open air, and then nothing is filled whatever is named here.
     */
    fun over(waterline: Int?, options: Options): SeaFill {
        if (waterline == null || isEmpty) return SeaFill.NONE
        return SeaFill.of(substance(), level = waterline + depthShift(options))
    }

    companion object {
        /**
         * The three the mod refers to by name. Spelled out rather than read from [Blocks], which
         * initialises a large slice of the game — a `Sea` must be constructible without a booted Minecraft.
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
         * Names for "no sea at all", the one value an id cannot spell for itself — every other sea is
         * written as a block and arrives through [named] unaided.
         */
        private val NAMES_FOR_NOTHING: Map<String, Sea> = mapOf("none" to NONE, "void" to NONE)

        /**
         * The sea a recipe means by [key] — a registry id, or one of the words for having none. Null only
         * for a key that is neither, which is malformed rather than missing content.
         */
        fun named(key: String): Sea? =
            NAMES_FOR_NOTHING[key] ?: ResourceLocation.tryParse(key)?.let(::Sea)

        /**
         * Several seas poured to one [waterline], each filling its own territory. The height is shared and
         * the substance is not, so water can meet lava along a line at the same level. A sea of nothing
         * contributes air, leaving genuine open space on one side of the seam.
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
