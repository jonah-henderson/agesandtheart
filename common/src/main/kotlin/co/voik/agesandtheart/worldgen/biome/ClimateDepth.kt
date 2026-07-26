package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable

/**
 * How buried a point is — the sixth climate parameter, and the only one of the six that our worlds
 * have to answer for themselves.
 *
 * The other five (temperature, humidity, continentalness, erosion, weirdness) are honest functions of
 * horizontal position, so [AgeBiomeSource] borrows vanilla's outright. Depth is different: vanilla
 * derives it from *vanilla's* terrain, projecting a surface it shaped itself. Our surface comes from a
 * [TerrainField] instead, so borrowing vanilla's depth would measure our columns against a landscape
 * that isn't there.
 *
 * The scale is vanilla's, so vanilla's biome table reads it correctly: `0` at the surface, rising
 * downward, `1.0` about [BLOCKS_PER_UNIT] below it. That is what puts the cave biomes where they
 * belong — vanilla registers surface biomes across depth `0.0`–`1.0`, dripstone and lush caves at
 * `0.2`–`0.9`, and the deep dark at `1.1`.
 */
sealed interface ClimateDepth {

    /** Which case this is — drives codec dispatch, as with `TerrainField`. */
    val kind: DepthKind

    /** Vanilla climate units, measured downward from the surface. */
    fun at(blockX: Int, blockY: Int, blockZ: Int): Float

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
 * Every column is treated as surface, however deep the rock goes. Vanilla's table then never reaches
 * its cave biomes, so an Age gets its surface biome from top to bottom.
 *
 * This is the right default for an Age whose caves are incidental, and it costs nothing to evaluate.
 * Swap in [BelowTerrain] to open up the underground.
 */
data object AtSurface : ClimateDepth {
    override val kind = DepthKind.AT_SURFACE
    override fun at(blockX: Int, blockY: Int, blockZ: Int): Float = 0.0f
}

/**
 * Depth measured against the Age's own rock, via [TerrainField.roofOver][co.voik.agesandtheart.worldgen.field.Spans.roofOver]
 * — so dripstone and lush caves appear where an Age is genuinely deep, and the deep dark only at the
 * very bottom.
 *
 * [terrain] is normally the same field the generator shapes with, and a preset should pass the one
 * value to both so they cannot drift. It is a separate parameter rather than a reference to the
 * generator's field because the two are not required to agree: an Age could lay its biomes out against
 * the base landmass while spires punch through it, and that is a legitimate thing to want.
 *
 * Note this is *more* accurate than vanilla, which estimates its surface with `preliminarySurfaceLevel`
 * where our spans are exact.
 */
data class BelowTerrain(
    val terrain: TerrainField,
    val blocksPerUnit: Int = ClimateDepth.BLOCKS_PER_UNIT,
) : ClimateDepth {

    override val kind = DepthKind.BELOW_TERRAIN

    override fun at(blockX: Int, blockY: Int, blockZ: Int): Float {
        // No rock overhead means open sky, which is the surface by any reading.
        val roofY = terrain.columnSpans(blockX, blockZ).roofOver(blockY) ?: return 0.0f
        return (roofY - blockY).toFloat() / blocksPerUnit
    }

    companion object {
        val CODEC: MapCodec<BelowTerrain> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                TerrainField.CODEC.fieldOf("terrain").forGetter(BelowTerrain::terrain),
                Codec.INT.optionalFieldOf("blocks_per_unit", ClimateDepth.BLOCKS_PER_UNIT)
                    .forGetter(BelowTerrain::blocksPerUnit),
            ).apply(instance, ::BelowTerrain)
        }
    }
}

/** The closed set of depth cases. Codecs are built lazily so enum init can't outrun the objects. */
enum class DepthKind(private val makeCodec: () -> MapCodec<out ClimateDepth>) : StringRepresentable {
    AT_SURFACE({ MapCodec.unit(AtSurface) }),
    BELOW_TERRAIN({ BelowTerrain.CODEC });

    fun codec(): MapCodec<out ClimateDepth> = makeCodec()

    override fun getSerializedName(): String = name.lowercase()

    companion object {
        val CODEC: Codec<DepthKind> = StringRepresentable.fromEnum(DepthKind::values)
    }
}
