package co.voik.agesandtheart.worldgen.carver

import co.voik.agesandtheart.worldgen.VerticalWindow
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.NormalNoise

/**
 * Rock riddled with small pockets. The cut is what gives `porous`'s water table somewhere to show: an
 * Age's table is only ever consulted *while something is being carved*, so without one the preset was
 * byte-identical to `solid` however wet it claimed to be.
 *
 * **Deliberately not caves** — a cave is a connected walk you travel along, this is isolated voids you
 * break into. The difference comes from the noise rather than any extra machinery: a fine scale with a
 * high threshold leaves scattered blobs where a coarse one with a low threshold joins them into tunnels.
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
    val amplitudes: DoubleArray,
) : CarvingRule {
    private val pockets = NormalNoise.create(XoroshiroRandomSource(seed), firstOctave, *amplitudes)

    override fun cuts(worldX: Int, worldY: Int, worldZ: Int): Boolean {
        if (worldY !in fromY..toY) return false
        return pockets.getValue(worldX / scale, worldY / verticalScale, worldZ / scale) > threshold
    }

    companion object {
        /**
         * The shipped rule: small vugs through the whole rock column. The band spans the world rather than
         * a tuned slice, because a carving may be paired with any terrain and a fixed band would quietly
         * do nothing for half of them. `RuleCarver` skips air first, so the width costs little.
         */
        val VUGS = Porosity(
            fromY = VerticalWindow.MIN_Y,
            toY = VerticalWindow.TOP_Y,
            scale = 9.0,
            verticalScale = 6.5,
            threshold = 0.62,
            seed = 0x0B_5E_1FL,
            firstOctave = -3,
            amplitudes = doubleArrayOf(1.0, 0.6),
        )
    }
}
