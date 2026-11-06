package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.lang.Math.floorDiv
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [base], hollowed by **Minecraft's own noise caves** — the cheese chambers, the spaghetti tunnels, the
 * entrances that open onto a hillside, and the pillars left standing in the middle of a cavern.
 *
 * **Vanilla's arithmetic, our evaluation.** The cave shapes are a stack of density functions in
 * `NoiseRouterData`, and this is that stack written out as plain maths over noises from [fieldNoise]. Nothing
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
     * the `entrances` cut, which is what opens a tunnel onto a hillside without hollowing out the hillside.
     */
    val entranceReach: Int = DEFAULT_ENTRANCE_REACH,
    /** Whether the cheese chambers are cut — the large open caverns. */
    val cheese: Boolean = true,
    /** And whether pillars are left standing in them. Nothing to cut if there are no chambers. */
    val pillars: Boolean = true,
    /**
     * How big the caves are against vanilla's: every noise is read this many times coarser, so each tunnel,
     * chamber and pillar grows by it while the band they are cut through stays the height it is.
     */
    val featureScale: Double = VANILLA_SCALE,
    /**
     * Whether the caves are read on a grid across as well as up, as vanilla reads its own: worked out at every
     * [GRID_STEP]th column and drawn between, rather than in every column. Softer in the finest detail, and several
     * times cheaper. False on an Age written before generator 22, whose caves stay as they were.
     */
    val gridded: Boolean = false,
    /**
     * How far past vanilla's threshold a density still opens: above nought, every tunnel, mouth and chamber is cut
     * wider and more of them meet; below it, fewer and narrower.
     */
    val opening: Double = VANILLA_OPENING,
) : TerrainField {
    override val kind = FieldKind.CAVED

    override val horizontalReach = base.horizontalReach

    // The base's own cost, plus a voxel walk of the band at a dozen-odd noises each. Reported honestly so
    // [Intersect] still orders sensibly around it; this is the most expensive node in the toolkit.
    override val samplesPerColumn =
        base.samplesPerColumn + (toY - fromY + 1).coerceAtLeast(0) * NOISES_PER_VOXEL

    /** Remembered per thread, and behind that across every thread: see [SharedColumns]. */
    private val memo = ColumnMemo(SharedColumns(::hollow)::at)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans = memo.at(worldX, worldZ)

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
        if (gridded) cut.fillFromGrid(worldX, worldZ, lowest, highest - lowest + 1) else cut.fill(worldX, worldZ, lowest, highest - lowest + 1)

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
     * The cave densities at a run of heights in one column, worked out **one noise at a time across the run** rather
     * than one height at a time across every noise.
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
    private inner class Densities(size: Int) {
        val rough = DoubleArray(size)
        val tunnels = DoubleArray(size)
        val mouths = DoubleArray(size)
        val chambers = DoubleArray(size)
        val standing = DoubleArray(size)
        private val working = DoubleArray(size)

        /** The densities at the first [count] of [heights] in the column at ([worldX], [worldZ]). */
        fun sample(worldX: Int, worldZ: Int, heights: IntArray, count: Int) {
            // Where the noises are read. The height gradients stay in world blocks, so the band still spans
            // the world however big the caves in it are.
            val x = worldX / featureScale
            val z = worldZ / featureScale
            fun noiseY(index: Int) = heights[index] / featureScale
            fun forEachHeight(sample: (Int) -> Unit) {
                for (i in 0..<count) sample(i)
            }

            // The walls' texture, wanted by both the tunnels and the mouths.
            forEachHeight { i -> rough[i] = spaghettiRoughness.get(x, noiseY(i), z).toDouble() }
            forEachHeight { i ->
                val depth = mapped(
                    spaghettiRoughnessModulator.get(x, noiseY(i), z).toDouble(),
                    ROUGHNESS_FROM,
                    ROUGHNESS_TO,
                )
                rough[i] = depth * (abs(rough[i]) - ROUGHNESS_BIAS)
            }

            // The winding flat tunnels. The sampler's own coordinates depend on the modulator's reading, so
            // the two cannot share a pass — but each is still one table read straight down the column.
            forEachHeight { i ->
                working[i] = rarityOfFlatSpaghetti(
                    spaghetti2dModulator.get(x * 2.0, noiseY(i), z * 2.0).toDouble(),
                )
            }
            forEachHeight { i ->
                val rarity = working[i]
                tunnels[i] = rarity * abs(spaghetti2d.get(x / rarity, noiseY(i) / rarity, z / rarity).toDouble())
            }
            forEachHeight { i ->
                working[i] = mapped(
                    spaghetti2dThickness.get(x * 2.0, noiseY(i), z * 2.0).toDouble(),
                    SPAGHETTI_2D_THICKNESS_FROM,
                    SPAGHETTI_2D_THICKNESS_TO,
                )
            }
            // One reading for the whole column: vanilla scales this noise's Y by zero, so it is flat.
            val elevation = SPAGHETTI_2D_ELEVATION_REACH * spaghetti2dElevation.get(x, 0.0, z).toDouble()
            forEachHeight { i ->
                val thickness = working[i]
                val fromTheBand = abs(
                    elevation + gradient(heights[i], BAND_FROM_Y, BAND_TO_Y, BAND_AT_FLOOR, BAND_AT_TOP),
                )
                val walls = (fromTheBand + thickness).let { it * it * it }
                val core = tunnels[i] + SPAGHETTI_2D_THICKNESS_SHARE * thickness
                tunnels[i] = maxOf(core, walls).coerceIn(-1.0, 1.0) + rough[i]
            }

            // The tunnels that reach the surface.
            forEachHeight { i ->
                working[i] = rarityOfRoundSpaghetti(
                    spaghetti3dRarity.get(x * 2.0, noiseY(i), z * 2.0).toDouble(),
                )
            }
            forEachHeight { i ->
                val rarity = working[i]
                mouths[i] = rarity * abs(spaghetti3dFirst.get(x / rarity, noiseY(i) / rarity, z / rarity).toDouble())
            }
            forEachHeight { i ->
                val rarity = working[i]
                val second = rarity * abs(spaghetti3dSecond.get(x / rarity, noiseY(i) / rarity, z / rarity).toDouble())
                mouths[i] = maxOf(mouths[i], second)
            }
            forEachHeight { i ->
                val thickness = mapped(
                    spaghetti3dThickness.get(x, noiseY(i), z).toDouble(),
                    SPAGHETTI_3D_THICKNESS_FROM,
                    SPAGHETTI_3D_THICKNESS_TO,
                )
                mouths[i] = (mouths[i] + thickness).coerceIn(-1.0, 1.0) + rough[i]
            }
            forEachHeight { i ->
                val y = heights[i]
                val mouth = caveEntrance.get(x * ENTRANCE_XZ, noiseY(i) * ENTRANCE_Y, z * ENTRANCE_XZ).toDouble() +
                    ENTRANCE_BIAS + gradient(y, ENTRANCE_FROM_Y, ENTRANCE_TO_Y, ENTRANCE_AT_FLOOR, ENTRANCE_AT_TOP)
                mouths[i] = minOf(mouth, mouths[i])
            }

            // The chambers: the cheap gate for every block, the nine-octave body only where it can matter.
            if (cheese) {
                forEachHeight { i ->
                    val layer = caveLayer.get(x, noiseY(i) * CAVE_LAYER_Y, z).toDouble()
                    chambers[i] = CHEESE_LAYER_WEIGHT * layer * layer
                }
                forEachHeight { i ->
                    if (chambers[i] <= CHEESE_BODY_REACH) {
                        val body = caveCheese.get(x, noiseY(i) * CAVE_CHEESE_Y, z).toDouble()
                        chambers[i] += (CHEESE_BIAS + body).coerceIn(-1.0, 1.0)
                    }
                }
            } else {
                chambers.fill(SOLID_ENOUGH, 0, count)
            }

            // The pillars left standing in a chamber, **on the same lattice as everything else** — vanilla
            // folds them into the density it interpolates, so its pillars are smoothed by the cell too.
            // Read at full resolution instead they come out as a thicket of needles: nothing rounds off the
            // thin ones and every marginal reading survives.
            if (pillars) {
                forEachHeight { i ->
                    standing[i] = PILLAR_SHAFT_WEIGHT *
                        pillar.get(x * PILLAR_XZ, noiseY(i) * PILLAR_Y, z * PILLAR_XZ).toDouble()
                }
                forEachHeight { i ->
                    standing[i] += mapped(
                        pillarRareness.get(x, noiseY(i), z).toDouble(),
                        PILLAR_RARENESS_FROM,
                        PILLAR_RARENESS_TO,
                    )
                }
                forEachHeight { i ->
                    val thickness = mapped(
                        pillarThickness.get(x, noiseY(i), z).toDouble(),
                        PILLAR_THICKNESS_FROM,
                        PILLAR_THICKNESS_TO,
                    )
                    standing[i] *= thickness * thickness * thickness
                }
            }
        }
    }

    /** One column's cave, height by height: sampled on its own lattice, or drawn from the grid round it. */
    private inner class ColumnCaves(height: Int) {
        private val tunnels = DoubleArray(height)
        private val mouths = DoubleArray(height)
        private val chambers = DoubleArray(height)
        private val standing = DoubleArray(height)
        private val opened = BooleanArray(height)
        private val mouthed = BooleanArray(height)
        private val sampledAt = IntArray(height)
        private val samples = Densities(height)

        // The grid blended across this column, at each of the grid's heights it spans.
        private val acrossTunnels = DoubleArray(height / FINEST_STRIDE + 2)
        private val acrossMouths = DoubleArray(height / FINEST_STRIDE + 2)
        private val acrossChambers = DoubleArray(height / FINEST_STRIDE + 2)
        private val acrossStanding = DoubleArray(height / FINEST_STRIDE + 2)

        /** Whether the caves take the block [index] up from the band's floor, [buried] under its own rock. */
        fun takes(index: Int, buried: Int): Boolean =
            if (buried < entranceReach) mouthed[index] else opened[index]

        /** Sampled on a lattice of its own, anchored at [lowest], and filled in straight between. */
        fun fill(worldX: Int, worldZ: Int, lowest: Int, height: Int) {
            var count = 0
            forEachSample(height) { i -> sampledAt[count++] = lowest + i }
            samples.sample(worldX, worldZ, sampledAt, count)
            for (j in 0..<count) {
                val i = sampledAt[j] - lowest
                tunnels[i] = samples.tunnels[j]
                mouths[i] = samples.mouths[j]
                chambers[i] = samples.chambers[j]
                standing[i] = samples.standing[j]
            }

            // **Filled in between the samples, which is the other half of the tier.** Everything above ran
            // on a lattice [SAMPLE_STRIDE] blocks apart; a cave is a noise field whose features are far
            // larger than that, which is exactly the case interpolation is valid for — and it is what
            // vanilla does, on a lattice twice as coarse again.
            if (pillars) interpolate(standing, height)
            interpolate(tunnels, height)
            interpolate(mouths, height)
            interpolate(chambers, height)
            decide(height)
        }

        /**
         * Drawn from the four grid columns round this one, as vanilla draws its caves: across the cell between them at
         * each of the grid's heights, and straight up between those, as [fill] fills between its own samples.
         */
        fun fillFromGrid(worldX: Int, worldZ: Int, lowest: Int, height: Int) {
            val westX = floorDiv(worldX, gridStep) * gridStep
            val northZ = floorDiv(worldZ, gridStep) * gridStep
            val east = (worldX - westX).toFloat() / gridStep
            val south = (worldZ - northZ).toFloat() / gridStep
            val firstStep = floorDiv(lowest - fromY, sampleStride)
            val lastStep = floorDiv(lowest + height - 1 - fromY, sampleStride) + 1
            // A column on a grid line takes nothing from the corners across it, so they are not worked out: a lookup
            // standing on the grid, as the stronghold search's and the biome cells' do, costs one grid column.
            val onAWestLine = east == 0.0f
            val onANorthLine = south == 0.0f
            val northWest = gridColumnAt(westX, northZ).covering(firstStep, lastStep)
            val northEast = if (onAWestLine) northWest else gridColumnAt(westX + gridStep, northZ).covering(firstStep, lastStep)
            val southWest = if (onANorthLine) northWest else gridColumnAt(westX, northZ + gridStep).covering(firstStep, lastStep)
            val southEast = when {
                onAWestLine -> southWest
                onANorthLine -> northEast
                else -> gridColumnAt(westX + gridStep, northZ + gridStep).covering(firstStep, lastStep)
            }

            // Each corner's weight in this column.
            val northWestShare = (1 - east) * (1 - south)
            val northEastShare = east * (1 - south)
            val southWestShare = (1 - east) * south
            val southEastShare = east * south
            fun blended(northWestValue: Float, northEastValue: Float, southWestValue: Float, southEastValue: Float): Double =
                (northWestValue * northWestShare + northEastValue * northEastShare +
                    southWestValue * southWestShare + southEastValue * southEastShare).toDouble()

            for (step in firstStep..lastStep) {
                val i = step - firstStep
                val nw = step - northWest.firstStep
                val ne = step - northEast.firstStep
                val sw = step - southWest.firstStep
                val se = step - southEast.firstStep
                acrossTunnels[i] = blended(northWest.tunnels[nw], northEast.tunnels[ne], southWest.tunnels[sw], southEast.tunnels[se])
                acrossMouths[i] = blended(northWest.mouths[nw], northEast.mouths[ne], southWest.mouths[sw], southEast.mouths[se])
                acrossChambers[i] =
                    blended(northWest.chambers[nw], northEast.chambers[ne], southWest.chambers[sw], southEast.chambers[se])
                if (pillars) {
                    acrossStanding[i] =
                        blended(northWest.standing[nw], northEast.standing[ne], southWest.standing[sw], southEast.standing[se])
                }
            }

            for (i in 0..<height) {
                val aboveTheFloor = lowest + i - fromY
                val step = floorDiv(aboveTheFloor, sampleStride)
                val below = step - firstStep
                val up = (aboveTheFloor - step * sampleStride).toDouble() / sampleStride
                tunnels[i] = lerp(up, acrossTunnels[below], acrossTunnels[below + 1])
                mouths[i] = lerp(up, acrossMouths[below], acrossMouths[below + 1])
                chambers[i] = lerp(up, acrossChambers[below], acrossChambers[below + 1])
                if (pillars) standing[i] = lerp(up, acrossStanding[below], acrossStanding[below + 1])
            }
            decide(height)
        }

        private fun decide(height: Int) {
            for (i in 0..<height) {
                val cut = mouths[i] <= opening || tunnels[i] <= opening || chambers[i] <= opening
                val filledByAPillar = pillars && standing[i] >= PILLAR_STANDS
                mouthed[i] = mouths[i] <= opening && !filledByAPillar
                opened[i] = cut && !filledByAPillar
            }
        }
    }

    /** The densities a grid column holds, at every [sampleStride]th height from [fromY], from its [firstStep]th on. */
    private class GridSamples(
        val firstStep: Int,
        val tunnels: FloatArray,
        val mouths: FloatArray,
        val chambers: FloatArray,
        val standing: FloatArray,
    ) {
        val steps: Int get() = tunnels.size

        fun covers(from: Int, to: Int) = steps > 0 && from >= firstStep && to < firstStep + steps
    }

    /**
     * One column of the grid, holding as many heights as anything has asked of it, shared by every thread. Grown by
     * what is missing, with headroom over it so the columns round it rarely grow it again, and published whole, so a
     * reader never sees one half-written.
     */
    private inner class GridColumn(private val worldX: Int, private val worldZ: Int) {
        @Volatile
        private var held = GridSamples(0, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))

        @Volatile
        var lastUsed = 0L

        fun covering(fromStep: Int, toStep: Int): GridSamples {
            held.takeIf { it.covers(fromStep, toStep) }?.let { return it }
            synchronized(this) {
                val now = held
                if (now.covers(fromStep, toStep)) return now
                val first = if (now.steps == 0) fromStep else minOf(now.firstStep, fromStep)
                val last = (if (now.steps == 0) toStep else maxOf(now.firstStep + now.steps - 1, toStep)) + HEADROOM_STEPS
                val count = last - first + 1
                val grown = GridSamples(first, FloatArray(count), FloatArray(count), FloatArray(count), FloatArray(count))
                val keptFrom = now.firstStep - first
                fun keep(from: FloatArray, into: FloatArray) = from.copyInto(into, destinationOffset = keptFrom)
                keep(now.tunnels, grown.tunnels)
                keep(now.mouths, grown.mouths)
                keep(now.chambers, grown.chambers)
                keep(now.standing, grown.standing)
                // The steps not yet worked out: below what was held, and above it.
                val missing = (0..<count).filter { now.steps == 0 || it < keptFrom || it >= keptFrom + now.steps }
                val heights = IntArray(missing.size) { fromY + (first + missing[it]) * sampleStride }
                val densities = Densities(missing.size).apply { sample(worldX, worldZ, heights, missing.size) }
                for ((j, index) in missing.withIndex()) {
                    grown.tunnels[index] = densities.tunnels[j].toFloat()
                    grown.mouths[index] = densities.mouths[j].toFloat()
                    grown.chambers[index] = densities.chambers[j].toFloat()
                    grown.standing[index] = densities.standing[j].toFloat()
                }
                held = grown
                return grown
            }
        }
    }

    private val grid = ConcurrentHashMap<Long, GridColumn>()
    private val gridClock = AtomicLong()
    private val trimmingTheGrid = AtomicBoolean(false)

    /** The grid column at ([worldX], [worldZ]); when the store is full, the columns used longest ago go. */
    private fun gridColumnAt(worldX: Int, worldZ: Int): GridColumn {
        val key = (worldX.toLong() shl Int.SIZE_BITS) or (worldZ.toLong() and UNSIGNED_INT)
        val column = grid[key] ?: grid.computeIfAbsent(key) { GridColumn(worldX, worldZ) }
        column.lastUsed = gridClock.incrementAndGet()
        if (grid.size > MOST_GRID_COLUMNS) forgetTheOldestOfTheGrid()
        return column
    }

    private fun forgetTheOldestOfTheGrid() {
        if (!trimmingTheGrid.compareAndSet(false, true)) return
        try {
            val byAge = grid.entries.map { it.key to it.value.lastUsed }.sortedBy { it.second }
            for ((key, _) in byAge.take(byAge.size / GRID_FORGET_SHARE)) grid.remove(key)
        } finally {
            trimmingTheGrid.set(false)
        }
    }

    /** How far apart the grid's columns stand: vanilla's own cell, and finer for smaller caves, as [sampleStride] is. */
    private val gridStep = (GRID_STEP * featureScale).roundToInt().coerceIn(FINEST_GRID_STEP, GRID_STEP)

    private val scratch = ThreadLocal.withInitial { ColumnCaves((toY - fromY + 1).coerceAtLeast(1)) }

    /**
     * Every index the cave densities are actually sampled at: the lattice, and always the last block of the
     * column so the interpolation has both ends to work between.
     */
    private inline fun forEachSample(height: Int, sample: (Int) -> Unit) {
        var i = 0
        while (i < height) {
            sample(i)
            i += sampleStride
        }
        if ((height - 1) % sampleStride != 0) sample(height - 1)
    }

    /**
     * [SAMPLE_STRIDE] for vanilla-sized caves, and finer for smaller ones: the lattice is only safe while
     * the features are far larger than it, so it shrinks with them. Never coarser, whatever the size.
     */
    private val sampleStride = (SAMPLE_STRIDE * featureScale).roundToInt().coerceIn(FINEST_STRIDE, SAMPLE_STRIDE)

    /** The gaps between the samples, filled straight. */
    private fun interpolate(values: DoubleArray, height: Int) {
        var from = 0
        while (from < height - 1) {
            val to = minOf(from + sampleStride, height - 1)
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
         * four-wide cell as well, as the [gridded] caves do, where the column by column ones are exact in X and Z.
         */
        private const val SAMPLE_STRIDE = 8
        /** Half vanilla's lattice, so the smallest caves cost at most twice the noise work. */
        private const val FINEST_STRIDE = 4

        /** How far apart the grid's columns stand for vanilla-sized caves: vanilla's own cell is four blocks across. */
        private const val GRID_STEP = 4
        private const val FINEST_GRID_STEP = 1

        /**
         * How many grid columns are kept at most, about a thousand chunks' worth: a column a cell, sixteen to a chunk,
         * a few hundred bytes each.
         */
        private const val MOST_GRID_COLUMNS = 16384

        /** How much of the grid's store goes when it is full. */
        private const val GRID_FORGET_SHARE = 4

        /** How many of the grid's heights a column works out over what it was asked, so its neighbours rarely grow it. */
        private const val HEADROOM_STEPS = 4

        private const val UNSIGNED_INT = 0xFFFF_FFFFL

        private fun lerp(share: Double, from: Double, to: Double) = from + (to - from) * share

        /** Vanilla's own caves, at vanilla's own size. */
        const val VANILLA_SCALE = 1.0
        const val VANILLA_OPENING = 0.0

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
        fun of(
            base: TerrainField,
            seed: Long,
            fromY: Int,
            toY: Int,
            gridded: Boolean = false,
            featureScale: Double = VANILLA_SCALE,
            opening: Double = VANILLA_OPENING,
        ): TerrainField =
            if (toY < fromY) base else Caved(base, seed, fromY, toY, gridded = gridded, featureScale = featureScale, opening = opening)

        fun codec(self: Codec<TerrainField>): MapCodec<Caved> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Caved::base),
                Codec.LONG.fieldOf("seed").forGetter(Caved::seed),
                Codec.INT.fieldOf("from_y").forGetter(Caved::fromY),
                Codec.INT.fieldOf("to_y").forGetter(Caved::toY),
                Codec.INT.optionalFieldOf("entrance_reach", DEFAULT_ENTRANCE_REACH).forGetter(Caved::entranceReach),
                Codec.BOOL.optionalFieldOf("cheese", true).forGetter(Caved::cheese),
                Codec.BOOL.optionalFieldOf("pillars", true).forGetter(Caved::pillars),
                Codec.DOUBLE.optionalFieldOf("feature_scale", VANILLA_SCALE).forGetter(Caved::featureScale),
                Codec.BOOL.optionalFieldOf("gridded", false).forGetter(Caved::gridded),
                Codec.DOUBLE.optionalFieldOf("opening", VANILLA_OPENING).forGetter(Caved::opening),
            ).apply(instance, ::Caved)
        }
    }
}
