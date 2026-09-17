package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.roundToInt

/**
 * [base], displaced vertically per territory — a region seam made visible as a scarp (design §3.4). It
 * asks which territory a column falls in and moves that column's rock by that territory's [throws].
 *
 * **A node of its own rather than a lift ([Spans.shifted]) inside each member**, because erosion is tied to absolute
 * heights: [Weathered]'s keel and band are world Y values, so a territory lifted before the wind reaches
 * it is weathered by a profile aimed at where it used to be. So this wraps the *finished* shape,
 * weathering included, and `AgeGeneration.assemble` builds it last — which is also the geologically
 * honest ordering.
 *
 * The height contract, carving, hydrology and [TerrainFill] all follow for free. Vanilla's structure
 * placement does **not**: it takes a height at one column and builds from there, so a structure will
 * straddle a scarp.
 *
 * **Never on a blended seam** — a displacement through a frayed boundary comes out as a strip of one-block
 * spikes rather than a cliff. Unreachable by construction now that a seam is one form or another, and
 * `FaultCheck.noSeamBothBlendsAndDisplaces` asserts it.
 *
 * One interaction to know rather than guard: a raised throw can push rock through the top of an Age's
 * [co.voik.agesandtheart.worldgen.VerticalWindow], showing up as a flat top. Not clamped, because clamping
 * would need to know how high the child reaches.
 */
data class Fault(
    val base: TerrainField,
    /** Whose seams this fault runs along — **the same map the shape divides on**, or it would not be a seam. */
    val map: RegionMap,
    /** How far each territory is thrown, in blocks, in the order [map] numbers them. Signed. */
    val throws: List<Int>,
) : TerrainField {
    override val kind = FieldKind.FAULT

    // Moving rock vertically changes nothing horizontally.
    override val horizontalReach = base.horizontalReach

    override val samplesPerColumn = base.samplesPerColumn + map.members

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val standing = base.columnSpans(worldX, worldZ)
        // Nothing here to displace, so do not pay to find out which side of the seam an empty column fell on.
        if (standing.ranges.isEmpty()) return standing
        return standing.shifted(throws.getOrElse(map.memberAt(worldX, worldZ)) { 0 })
    }

    /**
     * Scaling reaches the child, and the throws scale with it: a throw is a distance in the space the
     * child's heights live in, so leaving it alone would move a territory relative to itself.
     */
    override fun resized(factor: Double, pivotY: Int) = Fault(
        base.resized(factor, pivotY),
        map.resized(factor),
        throws.map { scaled(it, factor) },
    )

    companion object {
        /**
         * [base] thrown along [map]'s seams, or [base] itself where there is nothing for a fault to be.
         * A one-member map has no seam, so a throw would displace the whole world uniformly — an altitude
         * change wearing a fault's name. Refused here so no caller can express it by accident.
         */
        fun of(base: TerrainField, map: RegionMap, throws: List<Int>): TerrainField {
            val nothingMoves = throws.all { it == 0 }
            val thereIsNoSeam = map.members <= 1
            return if (nothingMoves || thereIsNoSeam) base else Fault(base, map, throws)
        }

        /**
         * Throws that alternate by territory, `throwBlocks` up and the same down — so **adjacent
         * territories always disagree**, and a scarp is twice the number. Independent signs would come out
         * the same half the time for two territories, which is a scarp that silently is not one.
         *
         * [seed] decides only **which parity rises**, which keeps the geology from depending on the order a
         * sentence named its terrains in. With three or more territories some non-adjacent pair shares a
         * side, left alone: two of three levels matching is horst-and-graben, and real.
         */
        fun alternatingThrows(members: Int, throwBlocks: Int, seed: Long): List<Int> {
            val evenTerritoriesRise = XoroshiroRandomSource(seed xor PARITY_SALT).nextBoolean()
            return List(members) { member ->
                val rises = (member % 2 == 0) == evenTerritoriesRise
                if (rises) throwBlocks else -throwBlocks
            }
        }

        // So which side of a scarp rises is decorrelated from everything else this seed decides.
        private const val PARITY_SALT = 0x5CA_12L

        fun codec(self: Codec<TerrainField>): MapCodec<Fault> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Fault::base),
                RegionMap.MAP_CODEC.forGetter(Fault::map),
                Codec.INT.listOf().fieldOf("throws").forGetter(Fault::throws),
            ).apply(instance, ::Fault)
        }
    }
}

/**
 * The wedge of ground near a territory seam, solid upwards from a floor that **rises with distance from
 * it** — the cut a rift is made of (§3.4), and a V rather than a trench. Meant to be [Subtract]ed from a
 * shape, so what it adds to the toolkit is only "near a seam": [RegionMap.blocksFromSeamAt] made into a
 * field.
 *
 * The rim wanders, because a chasm ruled straight across a world reads as a trench someone dug. A noise
 * from [fieldNoise] moves the measured distance either way by up to [rimWander] blocks.
 *
 * **The floor is not put under the waterline.** A rift used to flood by construction; leaving it dry is
 * what lets a sea spill in only where the rift actually cuts a coast, which is the thing worth seeing.
 *
 * **Never an instancing template**, like [Regions]: a seam runs right across an Age, so there is no
 * bounded neighbourhood to scan for.
 */
data class Rift(
    /** Whose seams the rift opens along. A one-member map has none, and this claims nothing at all. */
    val map: RegionMap,
    /**
     * How far either side of a seam the ground is taken, in blocks — so the chasm is twice this across.
     * Proportionate rather than surveyed; see [RegionMap.blocksFromSeamAt] for what that costs.
     */
    val halfWidth: Double,
    /** The lowest level the rift takes, at the seam itself. The chasm's floor is the block beneath it. */
    val floorY: Int,
    /** Where the rift meets the surrounding ground, and so where the V stops cutting. */
    val rimY: Int,
    /** How far the rim wanders either way, in blocks. Zero rules the chasm straight. */
    val rimWander: Double = DEFAULT_RIM_WANDER,
) : TerrainField {
    override val kind = FieldKind.RIFT

    override val horizontalReach = Double.POSITIVE_INFINITY

    override val samplesPerColumn = map.members + 1

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val wandered = map.blocksFromSeamAt(worldX, worldZ) +
            rimNoise.getValue(worldX / RIM_NOISE_STRETCH, 0.0, worldZ / RIM_NOISE_STRETCH) * rimWander
        if (wandered > halfWidth) return Spans.EMPTY
        // Deepest at the seam and rising to the rim, so the chasm comes out V-shaped rather than trenched.
        val acrossToRim = (wandered / halfWidth).coerceIn(0.0, 1.0)
        val floorHere = floorY + ((rimY - floorY) * acrossToRim).roundToInt()
        return Spans.of(floorHere, Spans.HIGHEST_Y)
    }

    private val rimNoise = fieldNoise(RIM_NOISE_SEED, RIM_NOISE_OCTAVE, RIM_NOISE_AMPLITUDES)

    override fun resized(factor: Double, pivotY: Int) = Rift(
        map.resized(factor),
        halfWidth * factor,
        scaledAbout(floorY, factor, pivotY),
        scaledAbout(rimY, factor, pivotY),
        rimWander * factor,
    )

    companion object {
        /**
         * How wide a rift is either side of the seam, so a chasm about twice this across.
         *
         * **The same width as a wall** ([Ridge.DEFAULT_HALF_WIDTH]), which is what walking one settled: at
         * two thirds of it a rift read as a trench beside the landform it is meant to be the equal of.
         */
        const val DEFAULT_HALF_WIDTH = 24.0

        /** How far the rim wanders either way. Enough to break the ruled line without hiding the chasm. */
        const val DEFAULT_RIM_WANDER = 6.0

        // A wavelength of a few tens of blocks: the rim meanders rather than fraying per column.
        private const val RIM_NOISE_SEED = 0x21F7_0FFL
        private const val RIM_NOISE_OCTAVE = -4
        private val RIM_NOISE_AMPLITUDES = listOf(1.0, 0.5)
        private const val RIM_NOISE_STRETCH = 1.0

        /**
         * [base] with a rift opened along [map]'s seams, or [base] itself where there is no seam. The
         * one-member collapse is the same acceptance property [Fault.of] enforces: an Age with one
         * territory must not move.
         */
        fun opened(
            base: TerrainField,
            map: RegionMap,
            floorY: Int,
            rimY: Int,
            halfWidth: Double = DEFAULT_HALF_WIDTH,
        ): TerrainField {
            val thereIsNoSeam = map.members <= 1
            val takesNoGround = halfWidth <= 0.0
            return if (thereIsNoSeam || takesNoGround) base else Subtract(base, Rift(map, halfWidth, floorY, rimY))
        }

        /** No children, so no need for the recursive field codec — the same shape [Slab] has. */
        val CODEC: MapCodec<Rift> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                RegionMap.MAP_CODEC.forGetter(Rift::map),
                Codec.DOUBLE.fieldOf("half_width").forGetter(Rift::halfWidth),
                Codec.INT.fieldOf("floor_y").forGetter(Rift::floorY),
                Codec.INT.fieldOf("rim_y").forGetter(Rift::rimY),
                Codec.DOUBLE.optionalFieldOf("rim_wander", DEFAULT_RIM_WANDER).forGetter(Rift::rimWander),
            ).apply(instance, ::Rift)
        }
    }
}

/**
 * The inverse of a [Rift]: a jagged wall standing *along* a territory seam, with the ground either side
 * left where it was. Meant to be [Union]ed with a shape.
 *
 * Where a scarp steps between two levels and a rift drops between them, this leaves both sides equal and
 * puts the drama in what divides them — so it reads as a wall built along the boundary rather than as
 * geology either side of it.
 *
 * Highest at the seam and falling to nothing at the rim, and the crest wanders exactly as a rift's rim
 * does, so it is a ridge rather than a fence.
 */
data class Ridge(
    /** Whose seams the wall runs along. A one-member map has none, and this claims nothing at all. */
    val map: RegionMap,
    /** How far either side of the seam the wall stands, so it is twice this thick at the base. */
    val halfWidth: Double,
    /** The level the wall is founded on, low enough to meet the ground it stands in. */
    val footingY: Int,
    /** The level the crest reaches at the seam itself. */
    val crestY: Int,
    /** How far the crest wanders either way, in blocks. */
    val crestWander: Double = Rift.DEFAULT_RIM_WANDER,
    /** What share of the half-width stands at full height before the flanks start falling away. */
    val crestShare: Double = DEFAULT_CREST_SHARE,
) : TerrainField {
    override val kind = FieldKind.RIDGE

    override val horizontalReach = Double.POSITIVE_INFINITY

    override val samplesPerColumn = map.members + 1

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val wandered = map.blocksFromSeamAt(worldX, worldZ) +
            crestNoise.getValue(worldX / CREST_NOISE_STRETCH, 0.0, worldZ / CREST_NOISE_STRETCH) * crestWander
        if (wandered > halfWidth) return Spans.EMPTY
        val outToRim = (wandered / halfWidth).coerceIn(0.0, 1.0)
        // Full height across the middle, then Hermite down to the footing. A taper that starts at the
        // crest buries most of its own height in the ground it stands in and leaves a needle showing;
        // the plateau is what makes it read as a wall with flanks rather than a spike.
        val downTheFlank = ((outToRim - crestShare) / (1.0 - crestShare)).coerceIn(0.0, 1.0)
        val standing = 1.0 - downTheFlank
        val profile = standing * standing * (3.0 - 2.0 * standing)
        val topHere = footingY + ((crestY - footingY) * profile).roundToInt()
        return if (topHere <= footingY) Spans.EMPTY else Spans.of(footingY, topHere)
    }

    private val crestNoise = fieldNoise(CREST_NOISE_SEED, CREST_NOISE_OCTAVE, CREST_NOISE_AMPLITUDES)

    override fun resized(factor: Double, pivotY: Int) = Ridge(
        map.resized(factor),
        halfWidth * factor,
        scaledAbout(footingY, factor, pivotY),
        scaledAbout(crestY, factor, pivotY),
        crestWander * factor,
        crestShare,
    )

    companion object {
        /** Wide enough to read as a landform rather than a fence, and a rift now matches it. */
        const val DEFAULT_HALF_WIDTH = 24.0

        /** The flat share of the top. Zero would taper from the crest itself and leave a needle. */
        const val DEFAULT_CREST_SHARE = 0.45

        // Its own seed, or the wall would meander in step with a rift's rim.
        private const val CREST_NOISE_SEED = 0x3B1D_6E5L
        private const val CREST_NOISE_OCTAVE = -4
        private val CREST_NOISE_AMPLITUDES = listOf(1.0, 0.5)
        private const val CREST_NOISE_STRETCH = 1.0

        /** [base] with a wall raised along [map]'s seams, or [base] itself where there is no seam. */
        fun raised(
            base: TerrainField,
            map: RegionMap,
            footingY: Int,
            crestY: Int,
            halfWidth: Double = DEFAULT_HALF_WIDTH,
        ): TerrainField {
            val thereIsNoSeam = map.members <= 1
            val standsNowhere = halfWidth <= 0.0 || crestY <= footingY
            return if (thereIsNoSeam || standsNowhere) base else Union(listOf(base, Ridge(map, halfWidth, footingY, crestY)))
        }

        val CODEC: MapCodec<Ridge> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                RegionMap.MAP_CODEC.forGetter(Ridge::map),
                Codec.DOUBLE.fieldOf("half_width").forGetter(Ridge::halfWidth),
                Codec.INT.fieldOf("footing_y").forGetter(Ridge::footingY),
                Codec.INT.fieldOf("crest_y").forGetter(Ridge::crestY),
                Codec.DOUBLE.optionalFieldOf("crest_wander", Rift.DEFAULT_RIM_WANDER).forGetter(Ridge::crestWander),
                Codec.DOUBLE.optionalFieldOf("crest_share", DEFAULT_CREST_SHARE).forGetter(Ridge::crestShare),
            ).apply(instance, ::Ridge)
        }
    }
}
