package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * A regular three-way grid of square-cut passages: tunnels running along X and along Z on every storey,
 * and shafts standing on Y wherever two of them cross. The space to take *out* of rock, as
 * [co.voik.agesandtheart.worldgen.LatticeField] uses it.
 *
 * **Closed form, and one node rather than a union of boxes.** A column's answer depends only on where it
 * sits against the grid: on a line both ways it is a shaft, on a line one way it is a tunnel at every
 * storey, and off both it is solid. So an infinite lattice costs a modulo and nothing else.
 *
 * **Only whole storeys are laid.** A storey is a tunnel [height] tall, and a band that would take part of
 * one leaves it out rather than cutting a crawlspace along the floor or the roof. The shafts run from the
 * floor of the lowest storey to the roof of the highest, so every one of them opens onto a tunnel at both
 * ends instead of dead-ending in rock.
 *
 * The grid is anchored at ([offsetX], [offsetY], [offsetZ]), which is what makes one Age's lattice not
 * everybody's.
 */
data class Lattice(
    val lowY: Int,
    val highY: Int,
    /** How wide a tunnel is, and how wide a shaft is both ways. */
    val width: Int,
    /** How tall a tunnel is. */
    val height: Int,
    /** From one tunnel to the next, both horizontal ways. */
    val spacingXZ: Int,
    /** From one storey to the next. */
    val spacingY: Int,
    val offsetX: Int,
    val offsetY: Int,
    val offsetZ: Int,
) : TerrainField {
    init {
        require(width in 1..<spacingXZ) { "a lattice's tunnels ($width) must leave rock between them ($spacingXZ)" }
        require(height in 1..<spacingY) { "a lattice's storeys ($height) must leave rock between them ($spacingY)" }
    }

    override val kind = FieldKind.LATTICE
    override val horizontalReach = Double.POSITIVE_INFINITY

    /** Every storey that fits whole between [lowY] and [highY] — the same in every column, so worked once. */
    private val storeys: List<IntRange> = run {
        val first = lowY + Math.floorMod(offsetY - lowY, spacingY)
        generateSequence(first) { it + spacingY }
            .takeWhile { it + height - 1 <= highY }
            .map { it..<it + height }
            .toList()
    }

    private val tunnels = Spans.ofAscending(storeys)
    private val shaft = if (storeys.isEmpty()) Spans.EMPTY else Spans.of(storeys.first().first, storeys.last().last)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val onX = onALine(worldX, offsetX)
        val onZ = onALine(worldZ, offsetZ)
        return when {
            onX && onZ -> shaft
            onX || onZ -> tunnels
            else -> Spans.EMPTY
        }
    }

    private fun onALine(at: Int, offset: Int): Boolean = Math.floorMod(at - offset, spacingXZ) < width

    override fun resized(factor: Double, pivotY: Int): Lattice {
        val spacingXZ = scaled(spacingXZ, factor).coerceAtLeast(2)
        val spacingY = scaled(spacingY, factor).coerceAtLeast(2)
        return Lattice(
            lowY = scaledAbout(lowY, factor, pivotY),
            highY = scaledAbout(highY, factor, pivotY),
            width = scaled(width, factor).coerceIn(1, spacingXZ - 1),
            height = scaled(height, factor).coerceIn(1, spacingY - 1),
            spacingXZ = spacingXZ,
            spacingY = spacingY,
            offsetX = scaled(offsetX, factor),
            offsetY = scaledAbout(offsetY, factor, pivotY),
            offsetZ = scaled(offsetZ, factor),
        )
    }

    companion object {
        val CODEC: MapCodec<Lattice> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("low_y").forGetter(Lattice::lowY),
                Codec.INT.fieldOf("high_y").forGetter(Lattice::highY),
                Codec.INT.fieldOf("width").forGetter(Lattice::width),
                Codec.INT.fieldOf("height").forGetter(Lattice::height),
                Codec.INT.fieldOf("spacing_xz").forGetter(Lattice::spacingXZ),
                Codec.INT.fieldOf("spacing_y").forGetter(Lattice::spacingY),
                Codec.INT.fieldOf("offset_x").forGetter(Lattice::offsetX),
                Codec.INT.fieldOf("offset_y").forGetter(Lattice::offsetY),
                Codec.INT.fieldOf("offset_z").forGetter(Lattice::offsetZ),
            ).apply(instance, ::Lattice)
        }
    }
}
