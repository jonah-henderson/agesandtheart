package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.ln

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
 * than a threshold, deliberately: cutting a single noise field into intervals would make the territories
 * *bands*, so two members either side of a third could never meet, where argmax gives an organic mosaic in
 * which every pair shares a border.
 *
 * How much ground each member gets is [shares]. Equal shares divide the world evenly, which is what argmax
 * does unaided; an uneven set tilts the claims so one member's territories merge into a majority while
 * another's survive as scarce islands around its own loudest points. The mosaic character is the same
 * either way — only the level at which each member wins moves.
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
    /**
     * How much ground each member gets, relative to the others — empty meaning an equal division.
     *
     * Not a fraction but a weight: `[64, 1]` is a dominant territory with scarce islands of the second,
     * and what a share of the world that works out to depends on how many members there are. The resolver
     * derives these from how strongly each word claims its preset (design §3.4), so a strong association
     * takes most of the map and a weak one turns up rarely — which is what makes an Age worth walking
     * across rather than looking at from the arrival point.
     */
    val shares: List<Double> = emptyList(),
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

    /**
     * What each member's share adds to its claim, in [ClaimTilt]'s units.
     *
     * The log of the share, which is what makes the arithmetic come out exact: tilted claims are
     * Gumbel-distributed by construction, and the largest of several Gumbels shifted by log-weights wins in
     * exactly the proportion of those weights. So a share of 64 against a share of 1 really does take 64
     * columns in 65, with no per-Age calibration and no dependence on the member count.
     */
    private val tilts = shares.map { share -> ln(share.coerceAtLeast(FAINTEST_SHARE)) }
        .ifEmpty { List(members.coerceAtLeast(1)) { 0.0 } }

    /** Which member owns this column: the loudest claim, softened to a coin-flip inside the seam. */
    fun memberAt(worldX: Int, worldZ: Int): Int {
        if (claims.size <= 1) return 0
        val sampleX = (worldX - originX) / stretch
        val sampleZ = (worldZ - originZ) / stretch

        var best = 0
        var bestTilted = Double.NEGATIVE_INFINITY
        var bestClaim = 0.0
        var runnerUp = 0
        var runnerUpTilted = Double.NEGATIVE_INFINITY
        for (member in claims.indices) {
            val claim = claims[member].getValue(sampleX, 0.0, sampleZ)
            val tilted = ClaimTilt.of(claim) + tilts.getOrElse(member) { 0.0 }
            if (tilted > bestTilted) {
                runnerUp = best
                runnerUpTilted = bestTilted
                best = member
                bestTilted = tilted
                bestClaim = claim
            } else if (tilted > runnerUpTilted) {
                runnerUp = member
                runnerUpTilted = tilted
            }
        }

        // Back into claim units before the seam is measured, so [blend] stays an honest distance in blocks
        // rather than a distance in tilted units — which vary in scale across the claim's range. Exact in
        // the bulk of the distribution and a little narrow out in its tails, where hardly any seam falls.
        val contested = (bestTilted - runnerUpTilted) / ClaimTilt.slopeAt(bestClaim)
        if (margin <= 0.0 || contested >= margin) return best
        // Deep in the band the two are equally likely; at its edge the winner takes it outright. So the
        // seam frays into the losing member rather than stopping along a drawn line.
        val oddsOfUpset = HALF * (1.0 - contested / margin)
        return if (dither.at(worldX, 0, worldZ).nextDouble() < oddsOfUpset) runnerUp else best
    }

    /**
     * One member's raw claim at a column, before any share tilts it — for the offline share check, which
     * has to know the distribution the tilt is built on.
     */
    fun claimAt(member: Int, worldX: Int, worldZ: Int): Double = claims[member].getValue(
        (worldX - originX) / stretch,
        0.0,
        (worldZ - originZ) / stretch,
    )

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

        // A share of zero would ask for a territory of no size and take the logarithm to negative
        // infinity. Held above it rather than rejected, since a share arrives from a recipe.
        private const val FAINTEST_SHARE = 1.0e-3

        val MAP_CODEC: MapCodec<RegionMap> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("members").forGetter(RegionMap::members),
                Codec.DOUBLE.fieldOf("scale").forGetter(RegionMap::scale),
                Codec.INT.optionalFieldOf("blend", 0).forGetter(RegionMap::blend),
                Codec.INT.optionalFieldOf("origin_x", 0).forGetter(RegionMap::originX),
                Codec.INT.optionalFieldOf("origin_z", 0).forGetter(RegionMap::originZ),
                Codec.LONG.fieldOf("seed").forGetter(RegionMap::seed),
                Codec.DOUBLE.listOf().optionalFieldOf("shares", emptyList()).forGetter(RegionMap::shares),
            ).apply(instance, ::RegionMap)
        }

        val CODEC: Codec<RegionMap> = MAP_CODEC.codec()
    }
}

/**
 * The monotone map from a raw claim to the scale a share is expressed in.
 *
 * The problem it solves: a share is only meaningful as *ground covered*, and how much ground a biased claim
 * wins depends on the shape of the claim noise's own distribution — which vanilla's `NormalNoise` does not
 * publish, and which is not any standard distribution anyway. Guessing at it would make "a quarter of the
 * world" mean whatever it happened to mean.
 *
 * So the distribution is **measured** ([CLAIMS] is the claim value at each of [PROBABILITIES], sampled over
 * millions of columns by `:common:regionsharecheck`) and every claim is mapped first to its own percentile
 * and then to a **Gumbel** value. That second step is what buys exactness: the largest of several Gumbel
 * values, each shifted by the log of a weight, wins in precisely the proportion of those weights. No
 * per-Age calibration, no dependence on how many members there are.
 *
 * **The table is only true of the noise it was measured against.** Change [RegionMap]'s octave or
 * amplitudes and every share drifts; `:common:regionsharecheck` is what notices, and it prints a
 * replacement table when it does.
 */
object ClaimTilt {
    /**
     * The probabilities the table is measured at — refined towards the ends, because a scarce territory
     * wins precisely where its own claim is unusually loud, and a coarse tail would quietly cap how rare a
     * territory can be.
     */
    val PROBABILITIES = doubleArrayOf(
        0.0001, 0.0005, 0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.3, 0.4, 0.5,
        0.6, 0.7, 0.8, 0.9, 0.95, 0.98, 0.99, 0.995, 0.998, 0.999, 0.9995, 0.9999,
    )

    /**
     * The claim value found at each of [PROBABILITIES]. **Measured, not derived** — regenerate with
     * `./gradlew :common:regionsharecheck`, which prints this array when the shares it measures drift.
     */
    private val CLAIMS = doubleArrayOf(
        -1.0839, -0.9828, -0.9350, -0.8828, -0.8043, -0.7365, -0.6596, -0.5376, -0.4241, -0.2818, -0.1768, -0.0856,
        -0.0004, 0.0849, 0.1763, 0.2819, 0.4252, 0.5389, 0.6617, 0.7389, 0.8066, 0.8843, 0.9352, 0.9798,
        1.0672,
    )

    /** Where each knot lands once its probability is read as a Gumbel value. */
    private val TILTS = DoubleArray(PROBABILITIES.size) { knot -> -ln(-ln(PROBABILITIES[knot])) }

    /**
     * [claim] on the tilted scale, interpolated between the knots and **extrapolated** past the outermost.
     *
     * Extrapolated rather than clamped, which was a real bug rather than a nicety: clamping flattens the top
     * of the scale, and the top of the scale is precisely where a scarce territory has to outbid a dominant
     * one. Flattened, a share of one against sixty-four came out at half the ground it asked for, because it
     * could never bid high enough to win the columns that were rightfully its own.
     */
    fun of(claim: Double): Double {
        val knot = segmentBelow(claim)
        val span = CLAIMS[knot + 1] - CLAIMS[knot]
        val along = (claim - CLAIMS[knot]) / span
        val tilted = TILTS[knot] + along * (TILTS[knot + 1] - TILTS[knot])
        return tilted.coerceIn(-LOUDEST_TILT, LOUDEST_TILT)
    }

    /**
     * How fast the tilted scale moves per unit of claim, hereabouts — which is what converts a gap between
     * two tilted claims back into the claim units a seam width is expressed in.
     */
    fun slopeAt(claim: Double): Double {
        val knot = segmentBelow(claim)
        val span = CLAIMS[knot + 1] - CLAIMS[knot]
        return ((TILTS[knot + 1] - TILTS[knot]) / span).coerceAtLeast(GENTLEST_SLOPE)
    }

    /** The knot at or below [claim], as a linear scan: twenty-three knots make a search pointless. */
    private fun segmentBelow(claim: Double): Int {
        for (knot in 0..<CLAIMS.size - 2) {
            if (claim < CLAIMS[knot + 1]) return knot
        }
        return CLAIMS.size - 2
    }

    // So a flat stretch of the table can never divide a seam width by nothing.
    private const val GENTLEST_SLOPE = 1.0e-6

    // Far past the log of any share ratio the ladder can express, so it bounds the extrapolation without
    // ever being the thing that decides a column.
    private const val LOUDEST_TILT = 16.0
}
