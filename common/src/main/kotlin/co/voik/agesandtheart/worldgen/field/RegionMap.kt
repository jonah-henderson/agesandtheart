package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.ln

/**
 * How an Age divides itself between the presets a set-valued aspect names — the territories, and nothing
 * else. Separate from everything that uses it, because several do and they must agree to the column, or
 * one seam lands in three slightly different places and reads as three faults.
 *
 * Each member gets its own low-frequency noise and the loudest claim at a column wins. **Argmax rather
 * than a threshold**: cutting one noise field into intervals would make the territories *bands*, so two
 * members either side of a third could never meet. Argmax gives a mosaic where every pair shares a border.
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
     * Where this map's territories sit. Two aspects with the same seed but different origins get
     * boundaries near each other without being coincident — the ground changing, then the vegetation.
     */
    val originX: Int,
    val originZ: Int,
    val seed: Long,
    /**
     * How much ground each member gets relative to the others — empty meaning an equal division. A weight
     * rather than a fraction: `[64, 1]` is a dominant territory with scarce islands of the second.
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
     * What each member's share adds to its claim, in [ClaimTilt]'s units — **the log of the share**, which
     * is what makes the arithmetic exact: tilted claims are Gumbel-distributed by construction, and the
     * largest of several Gumbels shifted by log-weights wins in exactly the proportion of those weights.
     */
    private val tilts = shares.map { share -> ln(share.coerceAtLeast(FAINTEST_SHARE)) }
        .ifEmpty { List(members.coerceAtLeast(1)) { 0.0 } }

    /** Which member owns this column: the loudest claim, softened to a coin-flip inside the seam. */
    fun memberAt(worldX: Int, worldZ: Int): Int {
        if (claims.size <= 1) return 0
        val contest = contestAt(worldX, worldZ)
        if (margin <= 0.0 || contest.contested >= margin) return contest.winner
        // Deep in the band the two are equally likely; at its edge the winner takes it outright. So the
        // seam frays into the losing member rather than stopping along a drawn line.
        val oddsOfUpset = HALF * (1.0 - contest.contested / margin)
        return if (dither.at(worldX, 0, worldZ).nextDouble() < oddsOfUpset) contest.runnerUp else contest.winner
    }

    /**
     * How far this column stands from the nearest seam, in blocks — infinite where there is no seam. The
     * margin between the top two claims, converted back into blocks; what makes a [Rift] expressible.
     *
     * **Proportionate rather than surveyed**, like [blend]: the conversion assumes the claim noise moves
     * at its local rate, so this is exact in the bulk and a little narrow in the tails. Good enough for "a
     * chasm about thirty blocks across", not for promising thirty-one.
     *
     * Measured **before** [memberAt]'s dither frays the boundary, so it is symmetric about where the seam
     * *is* — which is what lets one band straddle it rather than tracking one member's edge.
     */
    fun blocksFromSeamAt(worldX: Int, worldZ: Int): Double {
        if (claims.size <= 1) return Double.POSITIVE_INFINITY
        return contestAt(worldX, worldZ).contested * stretch
    }

    /**
     * Who won a column, who came second, and by how much — the argmax, written once because both public
     * questions need it. It allocates, which is a considered trade: [memberAt] is asked per block and
     * already pays a noise sample per member, and the object never escapes.
     *
     * `Contest.contested` is in **claim units, not blocks** — that is what [memberAt] compares against
     * [margin]. [blocksFromSeamAt] does the conversion.
     */
    private fun contestAt(worldX: Int, worldZ: Int): Contest {
        val sampleX = (worldX - originX) / stretch
        val sampleZ = (worldZ - originZ) / stretch

        var best = 0
        var bestTilted = Double.NEGATIVE_INFINITY
        var bestClaim = 0.0
        var runnerUp = 0
        var runnerUpTilted = Double.NEGATIVE_INFINITY
        for (member in claims.indices) {
            val claim = claims[member].get(sampleX, 0.0, sampleZ).toDouble()
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
        // rather than a distance in tilted units — which vary in scale across the claim's range.
        return Contest(best, runnerUp, (bestTilted - runnerUpTilted) / ClaimTilt.slopeAt(bestClaim))
    }

    /** See [contestAt]. [contested] is the winner's margin over the runner-up, in claim units. */
    private data class Contest(val winner: Int, val runnerUp: Int, val contested: Double)

    /**
     * One member's raw claim at a column, before any share tilts it — for the offline share check, which
     * has to know the distribution the tilt is built on.
     */
    fun claimAt(member: Int, worldX: Int, worldZ: Int): Double = claims[member].get(
        (worldX - originX) / stretch,
        0.0,
        (worldZ - originZ) / stretch,
    ).toDouble()

    /** The same territories at [factor] the size, for when a whole field is resized around them. */
    fun resized(factor: Double): RegionMap = copy(
        scale = scale * factor,
        blend = (blend * factor).toInt(),
        originX = (originX * factor).toInt(),
        originZ = (originZ * factor).toInt(),
    )

    companion object {
        /** One member owns everything — what a aspect with a single preset gets, and costs nothing. */
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
    }
}

/**
 * The monotone map from a raw claim to the scale a share is expressed in.
 *
 * A share is only meaningful as *ground covered*, and how much ground a biased claim wins depends on the
 * claim noise's own distribution, which `NormalNoise` does not publish and which is not standard anyway.
 * So it is **measured** ([CLAIMS] at each of [PROBABILITIES], by `./gradlew :common:claimprofile`), and
 * every claim maps first to its percentile and then to a **Gumbel** value — the largest of several
 * Gumbels shifted by log-weights wins in precisely the proportion of those weights.
 *
 * **The table is only true of the noise it was measured against.** Change [RegionMap]'s octave or
 * amplitudes and every share drifts; `RegionShareCheck` is what notices, and `claimprofile` prints a
 * replacement.
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
     * `./gradlew :common:claimprofile`, which prints this array ready to paste.
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
     * Extrapolated rather than clamped: clamping flattens the top of the scale, which is precisely where a
     * scarce territory has to outbid a dominant one, and it came out at half the ground it asked for.
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
