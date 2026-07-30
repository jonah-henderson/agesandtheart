package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * [base], displaced vertically per territory — a region seam made visible as a scarp (design §3.4). It
 * asks which territory a column falls in and moves that column's rock by that territory's [throws].
 *
 * **A node of its own rather than a [Raised] inside each member**, because erosion is tied to absolute
 * heights: [Weathered]'s keel and band are world Y values, so a territory lifted before the wind reaches
 * it is weathered by a profile aimed at where it used to be. So this wraps the *finished* shape,
 * weathering included, and `AgeGeneration.assemble` builds it last — which is also the geologically
 * honest ordering.
 *
 * The height contract, carving, hydrology and [Substance] all follow for free. Vanilla's structure
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

    // Moving rock vertically changes nothing horizontally — the same reasoning as [Raised].
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
 * The band of ground within [halfWidth] blocks of a territory seam, solid from [floorY] upwards — the cut
 * a rift is made of (§3.4). Meant to be [Subtract]ed from a shape, so what it adds to the toolkit is only
 * "near a seam": [RegionMap.blocksFromSeamAt] made into a field.
 *
 * Claiming everything from [floorY] upwards is what makes the chasm's *floor* the thing described rather
 * than its depth — whatever rock stood in the band goes, and the floor comes out flat at `floorY - 1`.
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
    /** The lowest level the rift takes, so the chasm's floor is the block beneath it. */
    val floorY: Int,
) : TerrainField {
    override val kind = FieldKind.RIFT

    override val horizontalReach = Double.POSITIVE_INFINITY

    override val samplesPerColumn = map.members

    override fun columnSpans(worldX: Int, worldZ: Int): Spans =
        if (map.blocksFromSeamAt(worldX, worldZ) > halfWidth) Spans.EMPTY
        else Spans.of(floorY, Spans.HIGHEST_Y)

    override fun resized(factor: Double, pivotY: Int) = Rift(
        map.resized(factor),
        halfWidth * factor,
        scaledAbout(floorY, factor, pivotY),
    )

    companion object {
        /**
         * How wide a rift is either side of the seam, so a chasm about twice this across. Sized to read as
         * a canyon against a territory a few hundred blocks wide. **Untuned by eye** — the distance is
         * proportionate rather than exact, so the first thing to do with a rift is look at one.
         */
        const val DEFAULT_HALF_WIDTH = 16.0

        /**
         * [base] with a rift opened along [map]'s seams, or [base] itself where there is no seam. The
         * one-member collapse is the same acceptance property [Fault.of] enforces: an Age with one
         * territory must not move.
         */
        fun opened(
            base: TerrainField,
            map: RegionMap,
            floorY: Int,
            halfWidth: Double = DEFAULT_HALF_WIDTH,
        ): TerrainField {
            val thereIsNoSeam = map.members <= 1
            val takesNoGround = halfWidth <= 0.0
            return if (thereIsNoSeam || takesNoGround) base else Subtract(base, Rift(map, halfWidth, floorY))
        }

        /** No children, so no need for the recursive field codec — the same shape [Slab] has. */
        val CODEC: MapCodec<Rift> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                RegionMap.MAP_CODEC.forGetter(Rift::map),
                Codec.DOUBLE.fieldOf("half_width").forGetter(Rift::halfWidth),
                Codec.INT.fieldOf("floor_y").forGetter(Rift::floorY),
            ).apply(instance, ::Rift)
        }
    }
}
