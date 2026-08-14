package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.Spans
import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
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
     * [sampled] is what vanilla's own depth function says here, which only [AsSampled] wants — the others
     * answer from their own rock and ignore it. Threaded as an argument rather than captured because the
     * value is per position and the depth outlives any one of them.
     */
    fun at(blockX: Int, blockY: Int, blockZ: Int, sampled: Float): Float

    /**
     * Whether [sampled] is read at all — the only reason to compute it.
     *
     * Vanilla's depth function walks a density tree, and [AgeBiomeSource] computed it on **every** biome
     * lookup in every Age to hand it to two cases out of three that throw it away. Declared per case rather
     * than asked as `this == AsSampled`, so a fourth case has to answer for itself.
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
 * Every column is treated as surface, however deep the rock goes, so vanilla's table never reaches its
 * cave biomes. The right default where caves are incidental, and free to evaluate.
 */
data object AtSurface : ClimateDepth {
    override val kind = DepthKind.AT_SURFACE
    override val readsVanillas = false
    override fun at(blockX: Int, blockY: Int, blockZ: Int, sampled: Float): Float = 0.0f
}

/**
 * **Vanilla's own answer, passed straight through** — for an Age whose rock is vanilla's.
 *
 * The depth function in the noise router that shaped the rock is the one the sampler reads, so the two
 * agree by construction and there is nothing for us to measure. [AtSurface] would be the wrong default
 * there rather than merely a coarse one: it answers zero everywhere, and an Age would grow no cave biome at
 * all — no lush caves, no dripstone, no deep dark.
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
data class BelowTerrain(
    val terrain: TerrainField,
    val blocksPerUnit: Int = ClimateDepth.BLOCKS_PER_UNIT,
) : ClimateDepth {

    override val kind = DepthKind.BELOW_TERRAIN

    /** It measures against its own rock, so what vanilla sampled is never read. */
    override val readsVanillas = false

    /**
     * One [ColumnCache] per chunk worker. Depth is asked per *quart* cell, so a chunk asks 384 times about
     * 16 distinct columns — free on a heightmap, but on a [co.voik.agesandtheart.worldgen.field.Noise3D]
     * field a column is a walk of the whole band, and recomputing it two dozen times was most of what an
     * Age spent (`caverns` measured at 139% of the vanilla budget against `hills` at 101%).
     *
     * **Per-thread rather than shared**: a biome source is used by every chunk worker at once, and
     * thread-confinement is what lets this need no synchronisation.
     */
    private val columnCache = ThreadLocal.withInitial { ColumnCache() }

    override fun at(blockX: Int, blockY: Int, blockZ: Int, sampled: Float): Float {
        val spans = columnCache.get().spansAt(blockX, blockZ, terrain)
        // No rock overhead means open sky, which is the surface by any reading.
        val roofY = spans.roofOver(blockY) ?: return 0.0f
        return (roofY - blockY).toFloat() / blocksPerUnit
    }

    /**
     * The columns of one chunk, remembered while its biomes are laid out. Direct-mapped and fixed-size, so
     * a lookup is an array index and nothing is allocated per chunk.
     *
     * **The indexing must shift down to the quart index first.** Coordinates arriving here are block
     * coordinates taking only every fourth value — 0, 4, 8, 12 within a chunk — so their low two bits are
     * always zero, and indexing on those drops all sixteen columns into one slot and thrashes. Shifting
     * gives four consecutive values per axis and sixteen slots with no collisions.
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
    BELOW_TERRAIN({ BelowTerrain.CODEC }),
    AS_SAMPLED({ MapCodec.unit(AsSampled) });

    fun codec(): MapCodec<out ClimateDepth> = makeCodec()

    override fun getSerializedName(): String = name.lowercase()

    companion object {
        val CODEC: Codec<DepthKind> = StringRepresentable.fromEnum(DepthKind::values)
    }
}
