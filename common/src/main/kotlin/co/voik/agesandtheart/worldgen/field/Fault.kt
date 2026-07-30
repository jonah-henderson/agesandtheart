package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * [base], displaced vertically per territory — **a region seam made visible as a scarp** (design
 * §3.4, "Faults: the region seam made visible").
 *
 * A territory map already divides an Age between several shapes; what it does not do is say that one side
 * of a boundary *stands higher than the other*. This does, in the plainest possible way: it asks which
 * territory a column falls in and moves that column's rock by that territory's [throws]. Signed, so a
 * territory can be thrown either way, and a pair of opposite throws makes the drop twice the number.
 *
 * ## Why the throw is a node of its own rather than a lift inside each member
 *
 * `Regions(members.map { Raised(it, throw) }, map)` produces the same blocks in the simple case and is the
 * shape a reader reaches for first. It is nonetheless the wrong place, for one reason that decides it:
 * **erosion is tied to absolute heights.** [Weathered] wraps the whole shape with a profile whose keel and
 * band are world Y values (see `Weathering.raisedBy`), so a territory lifted *before* the wind gets to it
 * is weathered by a profile aimed at where it used to be — its whole surface lands in the wrong erosion
 * band. `Terrain.ALTITUDE` gets away with a plain [Raised] precisely because it lifts the **entire** Age
 * and the profile is raised to match; no single profile can match two territories at two heights.
 *
 * Which turns out to be the geologically honest ordering as well: a fault displaces rock that has already
 * been shaped and worn, rather than changing what was there to shape. So this node wraps the *finished*
 * shape, weathering included, and `AgeGeneration.assemble` builds it last.
 *
 * ## What follows for free, and one thing that does not
 *
 * The **height contract** follows, because `AgeChunkGenerator.getBaseHeight` reads `columnSpans` — so
 * structures and arrival footing are placed against the thrown rock rather than where it used to be.
 * **Carving and hydrology** follow, because they take the field. The **material** follows too, since
 * [Substance] reads the terrain's own territory map at `(x, z)` and a vertical throw moves neither — a
 * thrown-up territory of copper spires arrives as copper with no work. And a territory thrown *below* the
 * waterline **floods**, because `SeaFill.fillsAt` is `y < level`: an unstable Age producing a drowned
 * chasm is design §7.8's star fissure, generated rather than authored.
 *
 * What does **not** follow is vanilla's structure placement, which will happily straddle a scarp: it takes
 * a height at one column and builds from there. Vanilla has the same problem at its own biome-height edges
 * and mostly gets away with it; a scarp is sharper. Worth looking at once in game before judging it broken.
 *
 * ## Never on a blended seam — structural now, not a caution
 *
 * [RegionMap.blend] frays a boundary per column so that two shapes *interlock* and one dissolves into the
 * other. Run a **displacement** through that fray and the interlocking columns are alternately thrown up and
 * dropped, so the seam comes out as a strip of one-block spikes and slots as tall as the throw — salt-and-
 * pepper breakage rather than a cliff. Found by looking at `:common:preview --args=fault`; no offline
 * assertion caught it, and none would have.
 *
 * **It is now unreachable, and not because anything here guards against it.** `Seam` used to be four
 * transition *widths*, with a fault chosen separately and layered over whichever width an Age drew. Jonah's
 * call was to make the softening one of the fault's own **forms** instead — so a seam is a scarp, a rift or a
 * fuzz, and never two at once. A blended map and a throw cannot co-occur, so the picket fence cannot be
 * built. `FaultCheck` asserts the exclusion directly (`noSeamBothBlendsAndDisplaces`), because that
 * property is now the whole of what keeps it away.
 *
 * One interaction to know rather than to guard: a *raised* throw can push rock through the top of an Age's
 * [co.voik.agesandtheart.worldgen.VerticalWindow], which shows up as a flat-topped shape. The only terrain
 * that reaches a ceiling at all is a lifted `spire_islands`, whose central spires already stand at about
 * 374 in a band ending at 383 — and that is a pinned bespoke Age, which can pin `terrain.seam=sheared` if it
 * ever divides. Stated here rather than clamped, because clamping would need to know how high the child
 * reaches.
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
     * Scaling reaches the child, and the throws scale with it.
     *
     * A throw is a distance in the space the child's own heights live in, so leaving it alone would move a
     * territory relative to itself — the same drift [Raised.resized] avoids for a lift.
     */
    override fun resized(factor: Double, pivotY: Int) = Fault(
        base.resized(factor, pivotY),
        map.resized(factor),
        throws.map { scaled(it, factor) },
    )

    companion object {
        /**
         * [base] thrown along [map]'s seams, or [base] itself where there is nothing for a fault to be.
         *
         * Two conditions collapse it, and the second is Phase 4.5 step 9's own acceptance property — *must
         * not move: any Age with one territory*. A one-member map has no seam, so a throw applied to it
         * would displace the whole world uniformly, which is an altitude change wearing a fault's name.
         * Refusing here rather than at the call site means no caller can express that by accident.
         */
        fun of(base: TerrainField, map: RegionMap, throws: List<Int>): TerrainField {
            val nothingMoves = throws.all { it == 0 }
            val thereIsNoSeam = map.members <= 1
            return if (nothingMoves || thereIsNoSeam) base else Fault(base, map, throws)
        }

        /**
         * Throws that alternate by territory, `throwBlocks` up and the same down — so **adjacent
         * territories always disagree**, and a scarp is twice the number.
         *
         * Alternating rather than drawn per territory, and the difference matters: independent signs would
         * come out the *same* half the time for two territories, which is a scarp that silently is not one.
         *
         * What [seed] decides is only **which parity rises**. That is not decoration either — the
         * alternative is that the first territory always rises, which makes the geology depend on the order
         * a sentence happened to name its terrains in, and the resolver promises exactly the opposite (see
         * `ResolverCheck.aFractureObeysItsGuards`, "word order decides nothing"). One coin flip per Age buys
         * that promise back.
         *
         * With three or more territories some non-adjacent pair shares a side, which is left alone: a
         * mosaic where two of three levels match is horst-and-graben, and real.
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
 * The band of ground within [halfWidth] blocks of a territory seam, solid from [floorY] upwards — **the
 * cut a rift is made of** (design §3.4, the second of the two forms a fault takes).
 *
 * Meant to be [Subtract]ed from a shape, which is the pattern `CavernField` already uses and the reason
 * this is expressed as the rock *removed* rather than as a node that removes it: a cut composes, and the
 * toolkit's one hard-won rule is that a node earning its place must not collapse into an arrangement of
 * nodes that already exist (see `Invert`, considered and not built, in `notes/terrain-architecture.md`).
 * What could not be expressed before was **"near a seam"**, and that is all this adds —
 * [RegionMap.blocksFromSeamAt] made into a field.
 *
 * Claiming everything from [floorY] to the top of the world is what makes the chasm's *floor* the thing
 * being described rather than its depth: subtracted from a shape, whatever rock stood in the band goes,
 * however tall it was, and the floor comes out flat at `floorY - 1`. A floor under the waterline therefore
 * fills with whatever the Age's sea is made of, which is design §7.8's star fissure arriving for nothing.
 *
 * **Never an instancing template**, like [Regions] and for the same reason: a seam runs right across an
 * Age, so there is no bounded neighbourhood to scan for.
 */
data class Rift(
    /** Whose seams the rift opens along. A one-member map has none, and this claims nothing at all. */
    val map: RegionMap,
    /**
     * How far either side of a seam the ground is taken, in blocks — so the chasm is twice this across.
     *
     * Read on the same footing as [RegionMap.blend], which is to say proportionate rather than surveyed;
     * see [RegionMap.blocksFromSeamAt] for what that costs.
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
         * How wide a rift is by default, either side of the seam — so a chasm about
         * `2 × DEFAULT_HALF_WIDTH` blocks across.
         *
         * Sized to read as a canyon rather than as a crack or as a missing region: a territory runs about
         * one vanilla biome across (`AgeCharacter.regionBlocks`, a few hundred blocks), so this is a small
         * fraction of one. **Untuned by eye and expected to want moving** — the distance it is measured
         * against is proportionate rather than exact, so the first thing to do with a rift is look at one.
         */
        const val DEFAULT_HALF_WIDTH = 16.0

        /**
         * [base] with a rift opened along [map]'s seams, or [base] itself where there is no seam to open
         * one along.
         *
         * Collapsing on a one-member map is not just an optimisation: it is the same acceptance property
         * [Fault.of] enforces. An Age with one territory must not move.
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
