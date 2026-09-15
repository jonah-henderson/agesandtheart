package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.abs

/**
 * [base], hollowed by **Minecraft's own noise caves** — the cheese chambers, the spaghetti tunnels, the
 * entrances that open onto a hillside, and the pillars left standing in the middle of a cavern.
 *
 * **Vanilla's arithmetic, our evaluation.** The cave shapes are a stack of density functions in
 * `NoiseRouterData`, and this is that stack written out as plain maths over [FieldNoise]'s noises. Nothing
 * here is invented: the octaves and amplitudes are the ones vanilla registers, the formulae are its
 * `spaghetti2D`, `spaghettiRoughnessFunction`, `entrances`, `pillars` and `underground` transcribed, and the
 * quantised rarity tables are copied outright.
 *
 * **Why transcribed and not called.** Three things were in the way, and only one of them turned out to be
 * real. A `DensityFunction` answers `(x, y, z) -> double` where this toolkit answers `(x, z) -> Spans` — but
 * caves are irreducibly volumetric anyway, so per-voxel is what they cost either way. Seed-binding looked
 * like the blocker and is not: `RandomState.getOrCreateNoise` is public and the Age already holds a real
 * `RandomState`, so vanilla's *noises* were always reachable. What is genuinely closed is the builders —
 * `NoiseRouterData.underground` and its parts are `private static`, and their caching lives in `NoiseChunk`
 * rather than in the tree, so calling them would buy a tree we cannot cache and cannot afford to walk.
 *
 * **A wrapper rather than a shape to subtract**, for the reason [Weathered] is one: the cut depends on how
 * deep in the rock a block sits. Vanilla reads that off its own terrain density and swaps between two rules
 * at a threshold — only *entrances* cut through the shallow rock, the full cave set below it — which is what
 * keeps chambers from opening the ground out from under a forest. A field tree has no density to read, but
 * it has something better: the run of rock the block is actually in, so [entranceReach] is that same rule
 * said in blocks.
 */
data class Caved(
    val base: TerrainField,
    val seed: Long,
    /** The band caves are cut in. Rock outside it is left whole, however deep it stands. */
    val fromY: Int,
    val toY: Int,
    /**
     * How far under its own rock a block must lie before the **whole** cave set reaches it. Above that only
     * [entrances] cut, which is what opens a tunnel onto a hillside without hollowing out the hillside.
     */
    val entranceReach: Int = DEFAULT_ENTRANCE_REACH,
    /** Whether the cheese chambers are cut — the large open caverns. */
    val cheese: Boolean = true,
    /** And whether pillars are left standing in them. Nothing to cut if there are no chambers. */
    val pillars: Boolean = true,
) : TerrainField {
    override val kind = FieldKind.CAVED

    override val horizontalReach = base.horizontalReach

    // The base's own cost, plus a voxel walk of the band at a dozen-odd noises each. Reported honestly so
    // [Intersect] still orders sensibly around it; this is the most expensive node in the toolkit.
    override val samplesPerColumn =
        base.samplesPerColumn + (toY - fromY + 1).coerceAtLeast(0) * NOISES_PER_VOXEL

    private val memo = ColumnMemo(::hollow)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans = memo.spansAt(worldX, worldZ)

    /**
     * The rock with its caves taken out — **walked only where there is rock to walk**, which is the whole of
     * what makes this affordable at all. A cave field asked about the open air above a mountain would pay a
     * dozen noise lookups a block for three hundred blocks of nothing.
     */
    private fun hollow(worldX: Int, worldZ: Int): Spans {
        val rock = base.columnSpans(worldX, worldZ)
        if (rock.ranges.isEmpty() || toY < fromY) return rock
        val lowest = maxOf(rock.ranges.first().first, fromY)
        val highest = minOf(rock.ranges.last().last, toY)
        if (highest < lowest) return rock

        val cut = scratch.get()
        cut.fill(worldX, worldZ, lowest, highest - lowest + 1)

        // Ascending runs throughout, so [Spans.ofAscending] needs no normalising pass.
        val kept = ArrayList<IntRange>(EXPECTED_RUNS)
        var runStart: Int? = null
        for (range in rock.ranges) {
            for (y in range) {
                val survives = y < lowest || y > highest || !cut.takes(y - lowest, range.last - y)
                if (survives) {
                    if (runStart == null) runStart = y
                } else if (runStart != null) {
                    kept += runStart..y - 1
                    runStart = null
                }
            }
            // A gap in the base ends a run whatever the caves did, or two slabs weld into one.
            runStart?.let { kept += it..range.last }
            runStart = null
        }
        return Spans.ofAscending(kept)
    }

    /**
     * One column's worth of cave, worked out **one noise at a time across the whole column** rather than
     * one voxel at a time across every noise.
     *
     * This is the difference between the two evaluation tiers, and here it is the whole performance story.
     * Walking down a column asking fourteen different noises about each block in turn touches fourteen
     * unrelated permutation tables per step, and the measured cost came out eight times what the noise
     * arithmetic alone should be. A pass per noise reads one table straight through. Vanilla does the same
     * thing through `fillArray` for exactly this reason.
     *
     * What it gives up is the early exit — every term is now computed for every block, where before finding
     * a cave in the first term skipped the other two. That was worth something on the tenth or so of blocks
     * that *are* cave, and nothing on the rest. What it keeps is the two gates that matter, because both are
     * conditional *passes* rather than conditional branches inside one: the nine-octave chamber body is only
     * sampled where its gate leaves room, and the three pillar noises only where something was opened.
     *
     * Thread-confined and reused, for the same reason every other scratch here is.
     */
    private inner class ColumnCaves(height: Int) {
        private val rough = DoubleArray(height)
        private val tunnels = DoubleArray(height)
        private val mouths = DoubleArray(height)
        private val chambers = DoubleArray(height)
        private val standing = DoubleArray(height)
        private val working = DoubleArray(height)
        private val opened = BooleanArray(height)
        private val mouthed = BooleanArray(height)

        /** Whether the caves take the block [index] up from the band's floor, [buried] under its own rock. */
        fun takes(index: Int, buried: Int): Boolean =
            if (buried < entranceReach) mouthed[index] else opened[index]

        fun fill(worldX: Int, worldZ: Int, lowest: Int, height: Int) {
            val x = worldX.toDouble()
            val z = worldZ.toDouble()

            // The walls' texture, wanted by both the tunnels and the mouths.
            forEachSample(height) { i -> rough[i] = spaghettiRoughness.getValue(x, (lowest + i).toDouble(), z) }
            forEachSample(height) { i ->
                val depth = mapped(
                    spaghettiRoughnessModulator.getValue(x, (lowest + i).toDouble(), z),
                    ROUGHNESS_FROM,
                    ROUGHNESS_TO,
                )
                rough[i] = depth * (abs(rough[i]) - ROUGHNESS_BIAS)
            }

            // The winding flat tunnels. The sampler's own coordinates depend on the modulator's reading, so
            // the two cannot share a pass — but each is still one table read straight down the column.
            forEachSample(height) { i ->
                working[i] = rarityOfFlatSpaghetti(
                    spaghetti2dModulator.getValue(x * 2.0, (lowest + i).toDouble(), z * 2.0),
                )
            }
            forEachSample(height) { i ->
                val rarity = working[i]
                tunnels[i] = rarity * abs(spaghetti2d.getValue(x / rarity, (lowest + i) / rarity, z / rarity))
            }
            forEachSample(height) { i ->
                working[i] = mapped(
                    spaghetti2dThickness.getValue(x * 2.0, (lowest + i).toDouble(), z * 2.0),
                    SPAGHETTI_2D_THICKNESS_FROM,
                    SPAGHETTI_2D_THICKNESS_TO,
                )
            }
            // One reading for the whole column: vanilla scales this noise's Y by zero, so it is flat.
            val elevation = SPAGHETTI_2D_ELEVATION_REACH * spaghetti2dElevation.getValue(x, 0.0, z)
            forEachSample(height) { i ->
                val thickness = working[i]
                val fromTheBand = abs(
                    elevation + gradient(lowest + i, BAND_FROM_Y, BAND_TO_Y, BAND_AT_FLOOR, BAND_AT_TOP),
                )
                val walls = (fromTheBand + thickness).let { it * it * it }
                val core = tunnels[i] + SPAGHETTI_2D_THICKNESS_SHARE * thickness
                tunnels[i] = maxOf(core, walls).coerceIn(-1.0, 1.0) + rough[i]
            }

            // The tunnels that reach the surface.
            forEachSample(height) { i ->
                working[i] = rarityOfRoundSpaghetti(
                    spaghetti3dRarity.getValue(x * 2.0, (lowest + i).toDouble(), z * 2.0),
                )
            }
            forEachSample(height) { i ->
                val rarity = working[i]
                mouths[i] = rarity * abs(spaghetti3dFirst.getValue(x / rarity, (lowest + i) / rarity, z / rarity))
            }
            forEachSample(height) { i ->
                val rarity = working[i]
                val second = rarity * abs(spaghetti3dSecond.getValue(x / rarity, (lowest + i) / rarity, z / rarity))
                mouths[i] = maxOf(mouths[i], second)
            }
            forEachSample(height) { i ->
                val thickness = mapped(
                    spaghetti3dThickness.getValue(x, (lowest + i).toDouble(), z),
                    SPAGHETTI_3D_THICKNESS_FROM,
                    SPAGHETTI_3D_THICKNESS_TO,
                )
                mouths[i] = (mouths[i] + thickness).coerceIn(-1.0, 1.0) + rough[i]
            }
            forEachSample(height) { i ->
                val y = lowest + i
                val mouth = caveEntrance.getValue(x * ENTRANCE_XZ, y * ENTRANCE_Y, z * ENTRANCE_XZ) +
                    ENTRANCE_BIAS + gradient(y, ENTRANCE_FROM_Y, ENTRANCE_TO_Y, ENTRANCE_AT_FLOOR, ENTRANCE_AT_TOP)
                mouths[i] = minOf(mouth, mouths[i])
            }

            // The chambers: the cheap gate for every block, the nine-octave body only where it can matter.
            if (cheese) {
                forEachSample(height) { i ->
                    val layer = caveLayer.getValue(x, (lowest + i) * CAVE_LAYER_Y, z)
                    chambers[i] = CHEESE_LAYER_WEIGHT * layer * layer
                }
                forEachSample(height) { i ->
                    if (chambers[i] <= CHEESE_BODY_REACH) {
                        val body = caveCheese.getValue(x, (lowest + i) * CAVE_CHEESE_Y, z)
                        chambers[i] += (CHEESE_BIAS + body).coerceIn(-1.0, 1.0)
                    }
                }
            } else {
                chambers.fill(SOLID_ENOUGH, 0, height)
            }

            // The pillars left standing in a chamber, **on the same lattice as everything else** — vanilla
            // folds them into the density it interpolates, so its pillars are smoothed by the cell too.
            // Read at full resolution instead they come out as a thicket of needles: nothing rounds off the
            // thin ones and every marginal reading survives.
            if (pillars) {
                forEachSample(height) { i ->
                    standing[i] = PILLAR_SHAFT_WEIGHT *
                        pillar.getValue(x * PILLAR_XZ, (lowest + i) * PILLAR_Y, z * PILLAR_XZ)
                }
                forEachSample(height) { i ->
                    standing[i] += mapped(
                        pillarRareness.getValue(x, (lowest + i).toDouble(), z),
                        PILLAR_RARENESS_FROM,
                        PILLAR_RARENESS_TO,
                    )
                }
                forEachSample(height) { i ->
                    val thickness = mapped(
                        pillarThickness.getValue(x, (lowest + i).toDouble(), z),
                        PILLAR_THICKNESS_FROM,
                        PILLAR_THICKNESS_TO,
                    )
                    standing[i] *= thickness * thickness * thickness
                }
                interpolate(standing, height)
            }

            // **Filled in between the samples, which is the other half of the tier.** Everything above ran
            // on a lattice [SAMPLE_STRIDE] blocks apart; a cave is a noise field whose features are far
            // larger than that, which is exactly the case interpolation is valid for — and it is what
            // vanilla does, on a lattice twice as coarse again.
            interpolate(tunnels, height)
            interpolate(mouths, height)
            interpolate(chambers, height)

            for (i in 0..<height) {
                val cut = mouths[i] <= 0.0 || tunnels[i] <= 0.0 || chambers[i] <= 0.0
                val filledByAPillar = pillars && standing[i] >= PILLAR_STANDS
                mouthed[i] = mouths[i] <= 0.0 && !filledByAPillar
                opened[i] = cut && !filledByAPillar
            }
        }
    }

    private val scratch = ThreadLocal.withInitial { ColumnCaves((toY - fromY + 1).coerceAtLeast(1)) }

    /**
     * Every index the cave densities are actually sampled at: the lattice, and always the last block of the
     * column so the interpolation has both ends to work between.
     */
    private inline fun forEachSample(height: Int, sample: (Int) -> Unit) {
        var i = 0
        while (i < height) {
            sample(i)
            i += SAMPLE_STRIDE
        }
        if ((height - 1) % SAMPLE_STRIDE != 0) sample(height - 1)
    }

    /** The gaps between the samples, filled straight. */
    private fun interpolate(values: DoubleArray, height: Int) {
        var from = 0
        while (from < height - 1) {
            val to = minOf(from + SAMPLE_STRIDE, height - 1)
            val span = to - from
            val step = (values[to] - values[from]) / span
            for (offset in 1..<span) values[from + offset] = values[from] + step * offset
            from = to
        }
    }

    // Vanilla's own octaves and amplitudes, each salted apart so one Age's caves are its own.
    private val spaghetti2d = fieldNoise(seed xor 0x5A_02DL, -7, listOf(1.0))
    private val spaghetti2dElevation = fieldNoise(seed xor 0x5A_E1EL, -8, listOf(1.0))
    private val spaghetti2dModulator = fieldNoise(seed xor 0x5A_D0DEL, -11, listOf(1.0))
    private val spaghetti2dThickness = fieldNoise(seed xor 0x5A_71C0L, -11, listOf(1.0))
    private val spaghetti3dFirst = fieldNoise(seed xor 0x5A_3D1L, -7, listOf(1.0))
    private val spaghetti3dSecond = fieldNoise(seed xor 0x5A_3D2L, -7, listOf(1.0))
    private val spaghetti3dRarity = fieldNoise(seed xor 0x5A_3AAL, -11, listOf(1.0))
    private val spaghetti3dThickness = fieldNoise(seed xor 0x5A_3771L, -8, listOf(1.0))
    private val spaghettiRoughness = fieldNoise(seed xor 0x5A_6006L, -5, listOf(1.0))
    private val spaghettiRoughnessModulator = fieldNoise(seed xor 0x5A_6D00L, -8, listOf(1.0))
    private val caveEntrance = fieldNoise(seed xor 0xCA_E17L, -7, listOf(0.4, 0.5, 1.0))
    private val caveLayer = fieldNoise(seed xor 0xCA_1A1EL, -8, listOf(1.0))
    private val caveCheese =
        fieldNoise(seed xor 0xCA_C4EEL, -8, listOf(0.5, 1.0, 2.0, 1.0, 2.0, 1.0, 0.0, 2.0, 0.0))
    private val pillar = fieldNoise(seed xor 0x91_11A2L, -7, listOf(1.0, 1.0))
    private val pillarRareness = fieldNoise(seed xor 0x91_2A2EL, -8, listOf(1.0))
    private val pillarThickness = fieldNoise(seed xor 0x91_71CCL, -8, listOf(1.0))

    /** Caves are a cut, so scaling reaches the child and the band moves with it; the noises do not scale. */
    override fun resized(factor: Double, pivotY: Int) = copy(
        base = base.resized(factor, pivotY),
        fromY = scaledAbout(fromY, factor, pivotY),
        toY = scaledAbout(toY, factor, pivotY),
        entranceReach = scaled(entranceReach, factor),
    )

    companion object {
        private const val EXPECTED_RUNS = 8

        /** How far under its own rock the whole cave set reaches. Vanilla's shallow rule, said in blocks. */
        const val DEFAULT_ENTRANCE_REACH = 12

        /**
         * Roughly how many noise lookups a voxel costs. Reported rather than measured, and only ever read as
         * a *relative* cost — but it is an order of magnitude above every other node, which is the thing a
         * caller has to know.
         */
        private const val NOISES_PER_VOXEL = 13

        /**
         * How far apart the cave densities are actually sampled, in blocks — the whole of what makes this
         * node affordable, and the tier-2 evaluation this toolkit named and had never built.
         *
         * A cave's features are tens of blocks across, so sampling every eighth and interpolating between
         * loses nothing an eye can find while dropping the noise work by the same factor.
         *
         * **Eight because that is vanilla's own cave lattice**, and vanilla is the reference here rather
         * than our exact evaluation. Measured on `riverlands` against a same-session vanilla of ~82: no
         * lattice 480 ms/chunk, four 218, eight 180. Eight does hold about fourteen per cent less cave than
         * evaluating every block — but every block is not what vanilla draws, so the comparison that matters
         * is the other one, and this is *still* the finer sampling: vanilla interpolates horizontally on a
         * four-wide cell as well, where this is exact in X and Z.
         */
        private const val SAMPLE_STRIDE = 8

        /** What a cheese term reads where there is no cheese: firmly solid, so the min never picks it. */
        private const val SOLID_ENOUGH = 1.0

        /** Above this a pillar fills its chamber back in. Vanilla's `rangeChoice` bound. */
        private const val PILLAR_STANDS = 0.03

        // Vanilla's constants, transcribed. Names describe what each does; the arithmetic is not ours to
        // justify, only to reproduce.
        private const val CAVE_LAYER_Y = 8.0
        private const val CAVE_CHEESE_Y = 0.6666666666666666
        private const val CHEESE_LAYER_WEIGHT = 4.0
        private const val CHEESE_BIAS = 0.27

        /** How far the clamped body can pull the sum down — one, so a gate past it can never open. */
        private const val CHEESE_BODY_REACH = 1.0

        private const val SPAGHETTI_2D_ELEVATION_REACH = 8.0
        private const val SPAGHETTI_2D_THICKNESS_FROM = -0.6
        private const val SPAGHETTI_2D_THICKNESS_TO = -1.3
        private const val SPAGHETTI_2D_THICKNESS_SHARE = 0.083
        private const val BAND_FROM_Y = -64
        private const val BAND_TO_Y = 320
        private const val BAND_AT_FLOOR = 8.0
        private const val BAND_AT_TOP = -40.0

        private const val ROUGHNESS_FROM = 0.0
        private const val ROUGHNESS_TO = -0.1
        private const val ROUGHNESS_BIAS = 0.4

        private const val SPAGHETTI_3D_THICKNESS_FROM = -0.065
        private const val SPAGHETTI_3D_THICKNESS_TO = -0.088
        private const val ENTRANCE_XZ = 0.75
        private const val ENTRANCE_Y = 0.5
        private const val ENTRANCE_BIAS = 0.37
        private const val ENTRANCE_FROM_Y = -10
        private const val ENTRANCE_TO_Y = 30
        private const val ENTRANCE_AT_FLOOR = 0.3
        private const val ENTRANCE_AT_TOP = 0.0

        private const val PILLAR_XZ = 7.0
        private const val PILLAR_Y = 0.3
        private const val PILLAR_SHAFT_WEIGHT = 2.0
        private const val PILLAR_RARENESS_FROM = 0.0
        private const val PILLAR_RARENESS_TO = -2.0
        private const val PILLAR_THICKNESS_FROM = 0.0
        private const val PILLAR_THICKNESS_TO = 1.1

        /** A unit reading moved onto [from]..[to] — vanilla's `mapFromUnitTo`. */
        private fun mapped(unit: Double, from: Double, to: Double): Double =
            (from + to) * 0.5 + (to - from) * 0.5 * unit

        /** Vanilla's `yClampedGradient`: [atFloor] at [fromY], [atTop] at [toY], flat outside. */
        private fun gradient(worldY: Int, fromY: Int, toY: Int, atFloor: Double, atTop: Double): Double {
            val along = ((worldY - fromY).toDouble() / (toY - fromY)).coerceIn(0.0, 1.0)
            return atFloor + (atTop - atFloor) * along
        }

        /**
         * How coarsely a tunnel is sampled where the modulator reads this — vanilla's quantised rarity, and
         * **quantised rather than smooth on purpose**: it is what gives the tunnels a handful of distinct
         * calibres instead of one that varies continuously into mush.
         */
        private fun rarityOfFlatSpaghetti(value: Double): Double = when {
            value < -0.75 -> 0.5
            value < -0.5 -> 0.75
            value < 0.5 -> 1.0
            value < 0.75 -> 2.0
            else -> 3.0
        }

        private fun rarityOfRoundSpaghetti(value: Double): Double = when {
            value < -0.5 -> 0.75
            value < 0.0 -> 1.0
            value < 0.5 -> 1.5
            else -> 2.0
        }

        /** [base] hollowed, or [base] itself where the band leaves nothing to cut. */
        fun of(base: TerrainField, seed: Long, fromY: Int, toY: Int): TerrainField =
            if (toY < fromY) base else Caved(base, seed, fromY, toY)

        fun codec(self: Codec<TerrainField>): MapCodec<Caved> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Caved::base),
                Codec.LONG.fieldOf("seed").forGetter(Caved::seed),
                Codec.INT.fieldOf("from_y").forGetter(Caved::fromY),
                Codec.INT.fieldOf("to_y").forGetter(Caved::toY),
                Codec.INT.optionalFieldOf("entrance_reach", DEFAULT_ENTRANCE_REACH).forGetter(Caved::entranceReach),
                Codec.BOOL.optionalFieldOf("cheese", true).forGetter(Caved::cheese),
                Codec.BOOL.optionalFieldOf("pillars", true).forGetter(Caved::pillars),
            ).apply(instance, ::Caved)
        }
    }
}
