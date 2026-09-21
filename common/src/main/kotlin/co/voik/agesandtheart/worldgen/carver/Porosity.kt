package co.voik.agesandtheart.worldgen.carver

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.CarverOutput
import net.minecraft.world.level.levelgen.WorldGenerationContext
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.carver.WorldCarver
import net.minecraft.world.level.levelgen.synth.NormalNoise

/**
 * Rock riddled with small pockets. The cut is what gives `porous`'s water table somewhere to show: an
 * Age's table is only ever consulted *while something is being carved*, so without one the preset was
 * byte-identical to `solid` however wet it claimed to be.
 *
 * **Deliberately not caves** — a cave is a connected walk you travel along, this is isolated voids you
 * break into. The difference comes from the noise rather than any extra machinery: a fine scale with a
 * high threshold leaves scattered blobs where a coarse one with a low threshold joins them into tunnels.
 *
 * It is both the rule and the carver: 26.3 folded a carver's configuration into the carver itself, so the
 * dials below are the serialised form rather than a separate record beside it.
 */
class Porosity(
    override val fromY: Int,
    override val toY: Int,
    /**
     * Blocks per unit of noise — how big a pocket is. Small on purpose: raise it and the pockets grow,
     * meet one another and become caves, which is a different preset's job.
     */
    val scale: Double,
    /** Vertical stretch, so pockets are a little wider than they are tall, as real vugs tend to be. */
    val verticalScale: Double,
    /**
     * How much of the rock is void. The single dial worth turning: the noise is near-normal, so a high
     * threshold takes only the extremes and leaves rock that is *mostly* solid.
     */
    val threshold: Double,
    val seed: Long,
    val firstOctave: Int,
    val amplitudes: List<Double>,
    /** How often a chunk is a starting one at all. */
    val probability: Float,
) : CarvingRule, WorldCarver {

    private val pockets = NormalNoise.createParity(firstOctave, *amplitudes.toDoubleArray())
        .create(XoroshiroRandomSource(seed))

    override fun cuts(worldX: Int, worldY: Int, worldZ: Int): Boolean {
        if (worldY !in fromY..toY) return false
        return pockets.get(worldX / scale, worldY / verticalScale, worldZ / scale) > threshold
    }

    override fun isStartChunk(random: RandomSource): Boolean = random.nextFloat() <= probability

    override fun carve(
        context: WorldGenerationContext,
        random: RandomSource,
        chunkBeingBuilt: ChunkPos,
        sourceChunk: ChunkPos,
        output: CarverOutput,
    ): Boolean = RuleCarving.cut(this, chunkBeingBuilt, sourceChunk, output)

    override fun codec(): MapCodec<out WorldCarver> = CODEC

    companion object {
        val CODEC: MapCodec<Porosity> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("from_y").forGetter(Porosity::fromY),
                Codec.INT.fieldOf("to_y").forGetter(Porosity::toY),
                Codec.DOUBLE.fieldOf("scale").forGetter(Porosity::scale),
                Codec.DOUBLE.fieldOf("vertical_scale").forGetter(Porosity::verticalScale),
                Codec.DOUBLE.fieldOf("threshold").forGetter(Porosity::threshold),
                Codec.LONG.fieldOf("seed").forGetter(Porosity::seed),
                Codec.INT.fieldOf("first_octave").forGetter(Porosity::firstOctave),
                Codec.DOUBLE.listOf().fieldOf("amplitudes").forGetter(Porosity::amplitudes),
                Codec.FLOAT.fieldOf("probability").forGetter(Porosity::probability),
            ).apply(instance, ::Porosity)
        }
    }
}
