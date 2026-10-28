package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * A template that changes form with its size: [smaller] until it has been resized to [fromScale] or more, and [larger]
 * from there, so an instancer drawing it at several sizes gives only its largest copies the other shape.
 */
data class BySize(val smaller: TerrainField, val larger: TerrainField, val fromScale: Double, val scale: Double = 1.0) :
    TerrainField {
    override val kind = FieldKind.BY_SIZE

    private val current = if (scale >= fromScale) larger else smaller

    override val horizontalReach = current.horizontalReach

    override val samplesPerColumn = current.samplesPerColumn

    override fun columnSpans(worldX: Int, worldZ: Int): Spans = current.columnSpans(worldX, worldZ)

    override fun resized(factor: Double, pivotY: Int) = copy(
        smaller = smaller.resized(factor, pivotY),
        larger = larger.resized(factor, pivotY),
        scale = scale * factor,
    )

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<BySize> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("smaller").forGetter(BySize::smaller),
                self.fieldOf("larger").forGetter(BySize::larger),
                Codec.DOUBLE.fieldOf("from_scale").forGetter(BySize::fromScale),
                Codec.DOUBLE.optionalFieldOf("scale", 1.0).forGetter(BySize::scale),
            ).apply(instance, ::BySize)
        }
    }
}
