package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * How an Age divides itself between the several presets a set-valued slot names — the territories, and
 * nothing else.
 *
 * Deliberately separate from anything that *uses* it, because more than one thing does: [Regions] asks
 * it which shape lays the rock, the surface rule asks it which dressing paints, and the biome source
 * asks it which biomes belong here. They must all agree to the column, or a seam would land in three
 * slightly different places and read as three faults rather than one boundary.
 *
 * Each member gets its own low-frequency noise and the loudest claim at a column wins. Argmax rather
 * than a threshold, deliberately: a threshold has to be re-derived every time the member count changes
 * and gives uneven shares when it is wrong, where argmax divides any number of members into roughly
 * equal, organically-shaped territories and needs no tuning at all.
 */
data class RegionMap(
    val members: Int,
    /** How far across a territory runs, in blocks. One vanilla biome, per design §3.4. */
    val scale: Double,
    /**
     * How wide the transition between territories is, in blocks. **Zero is a knife edge** — the region
     * is decided outright and an island straddling a boundary is sheared off flat in mid-air, which is
     * the strongest reading of the impossible geometry this is all for. Wider, and the two members
     * interlock through a band of stochastic columns so one dissolves into the other.
     */
    val blend: Int,
    /**
     * Where this map's territories sit. Two slots partitioning with the same seed but different origins
     * get boundaries near each other without being coincident — the ground changing, then shortly after
     * the vegetation, which is how landscapes tend to behave.
     */
    val originX: Int,
    val originZ: Int,
    val seed: Long,
) {
    private val stretch = scale.coerceAtLeast(SMALLEST_STRETCH)

    // One noise per member, decorrelated by index. Sampling at world/scale makes a claim's wavelength
    // [scale] blocks, so the territory size is the one number that governs it.
    private val claims = (0..<members.coerceAtLeast(1)).map { member ->
        fieldNoise(seed + member * MEMBER_STRIDE, CLAIM_OCTAVE, CLAIM_AMPLITUDES)
    }

    // How close two claims must be, in claim units, for a column to fall in the transition band. A
    // claim moves by about one over [scale] blocks, so a band [blend] blocks wide is that same fraction
    // of a claim — which is what keeps `blend` an honest distance rather than an abstract weight.
    private val margin = if (blend <= 0) 0.0 else blend / stretch

    // Positional, so the dither is a pure function of the column. A per-call RNG would let two chunks
    // disagree about the same column at their shared border, which is the one thing this must not do.
    private val dither = XoroshiroRandomSource(seed).forkPositional()

    /** Which member owns this column: the loudest claim, softened to a coin-flip inside the seam. */
    fun memberAt(worldX: Int, worldZ: Int): Int {
        if (claims.size <= 1) return 0
        val sampleX = (worldX - originX) / stretch
        val sampleZ = (worldZ - originZ) / stretch

        var best = 0
        var bestClaim = Double.NEGATIVE_INFINITY
        var runnerUp = 0
        var runnerUpClaim = Double.NEGATIVE_INFINITY
        for (member in claims.indices) {
            val claim = claims[member].getValue(sampleX, 0.0, sampleZ)
            if (claim > bestClaim) {
                runnerUp = best
                runnerUpClaim = bestClaim
                best = member
                bestClaim = claim
            } else if (claim > runnerUpClaim) {
                runnerUp = member
                runnerUpClaim = claim
            }
        }

        val contested = bestClaim - runnerUpClaim
        if (margin <= 0.0 || contested >= margin) return best
        // Deep in the band the two are equally likely; at its edge the winner takes it outright. So the
        // seam frays into the losing member rather than stopping along a drawn line.
        val oddsOfUpset = HALF * (1.0 - contested / margin)
        return if (dither.at(worldX, 0, worldZ).nextDouble() < oddsOfUpset) runnerUp else best
    }

    /** The same territories at [factor] the size, for when a whole field is resized around them. */
    fun resized(factor: Double): RegionMap = copy(
        scale = scale * factor,
        blend = (blend * factor).toInt(),
        originX = (originX * factor).toInt(),
        originZ = (originZ * factor).toInt(),
    )

    companion object {
        /** One member owns everything — what a slot with a single preset gets, and costs nothing. */
        fun whole(): RegionMap = RegionMap(members = 1, scale = 1.0, blend = 0, originX = 0, originZ = 0, seed = 0)

        // Wavelength one in claim space, so a claim's extent is exactly [scale] blocks. The second
        // amplitude roughens the borders; without it territories come out as smooth ovals.
        private const val CLAIM_OCTAVE = 0
        private val CLAIM_AMPLITUDES = listOf(1.0, 0.5)

        // Enough that two members' claims share no structure. Arbitrary, only ever needs to be big.
        private const val MEMBER_STRIDE = 0x9E37_79B9L

        private const val HALF = 0.5

        val MAP_CODEC: MapCodec<RegionMap> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("members").forGetter(RegionMap::members),
                Codec.DOUBLE.fieldOf("scale").forGetter(RegionMap::scale),
                Codec.INT.optionalFieldOf("blend", 0).forGetter(RegionMap::blend),
                Codec.INT.optionalFieldOf("origin_x", 0).forGetter(RegionMap::originX),
                Codec.INT.optionalFieldOf("origin_z", 0).forGetter(RegionMap::originZ),
                Codec.LONG.fieldOf("seed").forGetter(RegionMap::seed),
            ).apply(instance, ::RegionMap)
        }

        val CODEC: Codec<RegionMap> = MAP_CODEC.codec()
    }
}
