package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.Spans
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

    /**
     * One [ColumnCache] per chunk worker. Depth is asked per *quart* cell, so a chunk asks 384 times
     * about only 16 distinct columns — and vanilla walks them x, then y, then z, so the same column
     * comes back around every fourth call rather than on the next one.
     *
     * That redundancy cost nothing while this sat on a heightmap, where a column is one noise sample.
     * On a [co.voik.agesandtheart.worldgen.field.Noise3D] field a column is a walk of the whole band,
     * and recomputing it two dozen times is most of what the Age spends. Measured: `caverns` at 139%
     * of the vanilla budget against `hills` at 101%, for terrain that is otherwise cheaper.
     *
     * Per-thread rather than shared, because a biome source is used by every chunk worker at once and
     * this is the toolkit's first piece of mutable state. Keeping it thread-confined means it needs no
     * synchronisation and can never publish a half-written entry.
     */
    private val columnCache = ThreadLocal.withInitial { ColumnCache() }

    override fun at(blockX: Int, blockY: Int, blockZ: Int): Float {
        val spans = columnCache.get().spansAt(blockX, blockZ, terrain)
        // No rock overhead means open sky, which is the surface by any reading.
        val roofY = spans.roofOver(blockY) ?: return 0.0f
        return (roofY - blockY).toFloat() / blocksPerUnit
    }

    /**
     * The columns of one chunk, remembered while its biomes are laid out. Direct-mapped and fixed-size,
     * so a lookup is an array index and nothing is ever evicted deliberately or allocated per chunk.
     *
     * **The indexing is exact rather than approximate, but it has to read the right bits.** Biomes are
     * sampled per quart cell and [AgeBiomeSource] multiplies back up before asking, so the coordinates
     * arriving here are block coordinates that only ever take *every fourth* value: 0, 4, 8, 12 within
     * a chunk. Their low two bits are therefore always zero, and indexing on those would drop all
     * sixteen columns into one aspect and thrash — measured, and worth exactly nothing. Shifting back down
     * to the quart index first gives four consecutive values per axis, and so sixteen columns in
     * sixteen aspects with no collisions. Entries from an earlier chunk fail the key check and are
     * overwritten.
     */
    private class ColumnCache {
        private val keys = LongArray(SLOTS) { EMPTY_KEY }
        private val spans = arrayOfNulls<Spans>(SLOTS)

        fun spansAt(x: Int, z: Int, terrain: TerrainField): Spans {
            val key = (x.toLong() shl Int.SIZE_BITS) or (z.toLong() and UNSIGNED_INT)
            val aspect = (((x shr QUART_BITS) and 3) shl 2) or ((z shr QUART_BITS) and 3)
            spans[aspect]?.let { if (keys[aspect] == key) return it }
            return terrain.columnSpans(x, z).also {
                keys[aspect] = key
                spans[aspect] = it
            }
        }

        private companion object {
            const val SLOTS = 16
            const val UNSIGNED_INT = 0xFFFF_FFFFL

            // Block coordinates back down to the quart cell they came from — four blocks to a cell.
            const val QUART_BITS = 2

            // A key no real column can produce, so an untouched aspect cannot match by accident.
            const val EMPTY_KEY = Long.MIN_VALUE
        }
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
