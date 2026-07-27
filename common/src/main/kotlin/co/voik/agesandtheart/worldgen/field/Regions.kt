package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * Divides the world between several shapes, so an Age can be two landforms at once.
 *
 * This is the machinery behind set-valued slots (`notes/the-art-design.md` §3.4): where a writer names
 * two landforms, the contradiction is satisfied **by coexistence** — plains running into sheer walls —
 * rather than by one term winning and the other going silently missing. It is the reason tags were
 * chosen over bipolar axes in the first place, so it is worth more than its size suggests.
 *
 * Each member gets its own low-frequency noise and the loudest claim at a column wins. Argmax rather
 * than a threshold, deliberately: a threshold has to be re-derived every time the member count changes
 * and gives uneven shares when it is wrong, where argmax divides any number of members into roughly
 * equal, organically-shaped territories and needs no tuning at all.
 *
 * **A member is asked for a column only when it wins it.** Regions therefore cost one noise sample per
 * member plus one winner's evaluation — not the sum of everything the Age could have been.
 */
data class Regions(
    val members: List<TerrainField>,
    /** How far across a territory runs, in blocks. One vanilla biome, per §3.4. */
    val scale: Double,
    /**
     * How wide the transition between territories is, in blocks. **Zero is a knife edge** — the region
     * is decided outright and an island straddling a boundary is sheared off flat in mid-air, which is
     * the strongest reading of the impossible geometry this is all for. Wider, and the two shapes
     * interlock through a band of stochastic columns so one dissolves into the other.
     *
     * Drawn per Age and frozen in its recipe, so an Age keeps the character it was written with.
     */
    val blend: Int,
    val seed: Long,
) : TerrainField {
    override val kind = FieldKind.REGIONS

    // Territories cover the world, so this is never a sensible instancing template.
    override val horizontalReach = Double.POSITIVE_INFINITY

    // One claim per member, plus whatever the winner costs. The winner is unknown here, so the dearest
    // member is the honest answer for an ordering hint.
    override val samplesPerColumn = members.size + (members.maxOfOrNull { it.samplesPerColumn } ?: 0)

    private val stretch = scale.coerceAtLeast(SMALLEST_STRETCH)

    // One noise per member, decorrelated by index. Sampling at world/scale makes a claim's wavelength
    // [scale] blocks, so the territory size is the one number that governs it.
    private val claims = members.indices.map { member ->
        fieldNoise(seed + member * MEMBER_STRIDE, CLAIM_OCTAVE, CLAIM_AMPLITUDES)
    }

    // How close two claims must be, in claim units, for a column to fall in the transition band. A
    // claim moves by about one over [scale] blocks, so a band [blend] blocks wide is that same fraction
    // of a claim — which is what keeps `blend` an honest distance rather than an abstract weight.
    private val margin = if (blend <= 0) 0.0 else blend / stretch

    // Positional, so the dither is a pure function of the column. A per-call RNG would let two chunks
    // disagree about the same column at their shared border, which is the one thing this must not do.
    private val dither = XoroshiroRandomSource(seed).forkPositional()

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val chosen = members.getOrNull(memberAt(worldX, worldZ)) ?: return Spans.EMPTY
        return chosen.columnSpans(worldX, worldZ)
    }

    /** Which member owns this column: the loudest claim, softened to a coin-flip inside the seam. */
    private fun memberAt(worldX: Int, worldZ: Int): Int {
        if (members.size <= 1) return 0
        val sampleX = worldX / stretch
        val sampleZ = worldZ / stretch

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
        // seam frays into the losing shape rather than stopping along a drawn line.
        val oddsOfUpset = HALF * (1.0 - contested / margin)
        return if (dither.at(worldX, 0, worldZ).nextDouble() < oddsOfUpset) runnerUp else best
    }

    override fun resized(factor: Double, pivotY: Int) = Regions(
        members.map { it.resized(factor, pivotY) },
        scale * factor,
        (blend * factor).toInt(),
        seed,
    )

    companion object {
        // Wavelength one in claim space, so a claim's extent is exactly [scale] blocks. The second
        // amplitude roughens the borders; without it territories come out as smooth ovals.
        private const val CLAIM_OCTAVE = 0
        private val CLAIM_AMPLITUDES = listOf(1.0, 0.5)

        // Enough that two members' claims share no structure. Arbitrary, only ever needs to be big.
        private const val MEMBER_STRIDE = 0x9E37_79B9L

        private const val HALF = 0.5

        fun codec(self: Codec<TerrainField>): MapCodec<Regions> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.listOf().fieldOf("members").forGetter(Regions::members),
                Codec.DOUBLE.fieldOf("scale").forGetter(Regions::scale),
                Codec.INT.optionalFieldOf("blend", 0).forGetter(Regions::blend),
                Codec.LONG.fieldOf("seed").forGetter(Regions::seed),
            ).apply(instance, ::Regions)
        }
    }
}
