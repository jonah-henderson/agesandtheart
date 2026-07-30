package co.voik.agesandtheart.worldgen.carver

import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.NormalNoise

/**
 * Rock riddled with small pockets — the rule that makes `porous` a thing rather than a word.
 *
 * **`porous` was a no-op**, byte-identical to `solid`, and had been since it was written: it named no
 * carvers, and an Age's water table is only ever consulted *while something is being carved*. So the
 * preset defined a table describing where water stood in the stone, and nothing ever asked it. Its own
 * description promised "the rock is wet through"; the ground was solid.
 *
 * Giving it a cut is what makes both halves true at once (Jonah's call, design §3.4). The pockets are
 * somewhere for water to stand, so the wet-and-dry table finally shows: near a submerged surface they fill,
 * and far below they run dry, which is exactly the gradient `WaterTable` was built to express and had no
 * way to display.
 *
 * **Deliberately not caves.** A cave is a connected walk you travel along; this is isolated voids you break
 * into. That difference is the whole distinction between `caves` and `porous` as answers to "what is going
 * on beneath the surface", and it comes from the shape of the noise rather than from any extra machinery: a
 * fine scale with a high threshold leaves scattered blobs, where a coarse scale with a low one would join
 * them into tunnels.
 */
class Porosity(
    override val fromY: Int,
    override val toY: Int,
    /**
     * Blocks per unit of noise — how big a pocket is.
     *
     * Small, and that is the point. Raise it and the pockets grow, meet one another and become caves,
     * which is a different preset's job.
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
         * The shipped rule: small vugs through the whole rock column.
         *
         * The band spans the world rather than a tuned slice, because `porous` is a *carving* and may be
         * paired with any terrain — Spire islands sit above y=100 where hills sit below y=100, and a fixed
         * band would quietly do nothing for one of them. `RuleCarver` skips air before asking, so a band
         * this wide costs about what the rock in it costs rather than what the sky does.
         */
        val VUGS = Porosity(
            fromY = -64,
            toY = 320,
            scale = 9.0,
            verticalScale = 6.5,
            threshold = 0.62,
            seed = 0x0B_5E_1FL,
            firstOctave = -3,
            amplitudes = doubleArrayOf(1.0, 0.6),
        )
    }
}
