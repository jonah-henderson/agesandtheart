package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.ColumnMemo
import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import net.minecraft.util.StringRepresentable

/**
 * How buried a point is — the sixth climate parameter, and the only one our worlds answer for themselves.
 * The other five are honest functions of horizontal position, so [AgeBiomeSource] borrows vanilla's; depth
 * is derived from *vanilla's* terrain, which is not the landscape a [TerrainField] made.
 *
 * The scale is vanilla's, so vanilla's table reads it correctly: `0` at the surface, rising downward,
 * `1.0` about [BLOCKS_PER_UNIT] below. That is what puts the cave biomes where they belong — surface
 * biomes span `0.0`–`1.0`, dripstone and lush caves `0.2`–`0.9`, the deep dark `1.1`.
 */
sealed interface ClimateDepth {

    /** Which case this is — drives codec dispatch, as with `TerrainField`. */
    val kind: DepthKind

    /**
     * Vanilla climate units, measured downward from the surface.
     *
     * [sampled] is what vanilla's own depth function says here, which only [AsSampled] wants — [BelowTerrain]
     * answers from its own rock and ignores it. Threaded as an argument rather than captured because the
     * value is per position and the depth outlives any one of them.
     */
    fun at(blockX: Int, blockY: Int, blockZ: Int, sampled: Float): Float

    /**
     * Whether [sampled] is read at all — the only reason to compute it.
     *
     * Vanilla's depth function walks a density tree, and [AgeBiomeSource] computed it on **every** biome
     * lookup in every Age to hand it to one case of two that throws it away. Declared per case rather
     * than asked as `this == AsSampled`, so a third case has to answer for itself.
     */
    val readsVanillas: Boolean

    companion object {
        /**
         * How far down one unit of depth reaches. Vanilla's own figure: its depth function is a
         * gradient of `1.5` to `-1.5` across the 384-block build range, so a unit is 128 blocks.
         */
        const val BLOCKS_PER_UNIT = 128

        val CODEC: Codec<ClimateDepth> = DepthKind.CODEC.dispatch(
            "type",
            { depth: ClimateDepth -> depth.kind },
            { kind: DepthKind -> kind.codec() },
        )
    }
}

/**
 * **Vanilla's own answer, passed straight through** — for an Age whose rock is vanilla's.
 *
 * The depth function in the noise router that shaped the rock is the one the sampler reads, so the two
 * agree by construction and there is nothing for us to measure.
 */
data object AsSampled : ClimateDepth {
    override val kind = DepthKind.AS_SAMPLED
    override val readsVanillas = true
    override fun at(blockX: Int, blockY: Int, blockZ: Int, sampled: Float): Float = sampled
}

/**
 * Depth measured against the Age's own rock, so dripstone and lush caves appear where an Age is genuinely
 * deep and the deep dark only at the very bottom. More accurate than vanilla, which estimates its surface
 * with `preliminarySurfaceLevel` where our spans are exact.
 *
 * [terrain] is normally the field the generator shapes with, but is a separate parameter because the two
 * need not agree: an Age could lay its biomes out against the base landmass while spires punch through it.
 */
data class BelowTerrain(val terrain: TerrainField) : ClimateDepth {

    override val kind = DepthKind.BELOW_TERRAIN

    /** It measures against its own rock, so what vanilla sampled is never read. */
    override val readsVanillas = false

    /**
     * Depth is asked per *quart* cell, so a chunk asks 384 times about 16 distinct columns — free on a
     * heightmap, but on a [co.voik.agesandtheart.worldgen.field.Noise3D] field a column is a walk of the whole
     * band, and recomputing it two dozen times was most of what an Age spent (`caverns` measured at 139% of
     * the vanilla budget against `hills` at 101%).
     */
    private val columns = ColumnMemo(terrain::columnSpans)

    override fun at(blockX: Int, blockY: Int, blockZ: Int, sampled: Float): Float {
        val spans = columns.spansAt(blockX, blockZ)
        // No rock overhead means open sky, which is the surface by any reading.
        val roofY = spans.roofOver(blockY) ?: return 0.0f
        return (roofY - blockY).toFloat() / ClimateDepth.BLOCKS_PER_UNIT
    }

    companion object {
        val CODEC: MapCodec<BelowTerrain> =
            TerrainField.CODEC.fieldOf("terrain").xmap(::BelowTerrain, BelowTerrain::terrain)
    }
}

/** The closed set of depth cases. Codecs are built lazily so enum init can't outrun the objects. */
enum class DepthKind(private val makeCodec: () -> MapCodec<out ClimateDepth>) : StringRepresentable {
    BELOW_TERRAIN({ BelowTerrain.CODEC }),
    AS_SAMPLED({ MapCodec.unit(AsSampled) });

    fun codec(): MapCodec<out ClimateDepth> = makeCodec()

    override fun getSerializedName(): String = name.lowercase()

    companion object {
        val CODEC: Codec<DepthKind> = StringRepresentable.fromEnum(DepthKind::values)
    }
}
