package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.levelgen.PositionalRandomFactory
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How an [Instanced] field lays its template copies out across the world: for a queried column, visit
 * every instance origin whose footprint could reach it.
 *
 * [Grid] and [Radial] differ only in *where* origins fall and both place about one per cell; [Scatter]
 * also varies *how many*, which is what separates a layout that clumps from one that merely looks
 * irregular. Randomness comes from a caller-supplied [PositionalRandomFactory], so nothing is stored per
 * instance.
 */
sealed interface Placement {

    val kind: PlacementKind

    /**
     * Invoke [visit] for each surviving instance origin within [templateReach] of ([worldX], [worldZ]), so
     * no covering instance is missed. Each visit carries that instance's own deterministic [RandomSource],
     * already advanced past this placement's draws, for the template choice.
     */
    fun forEachInstanceNear(
        worldX: Int,
        worldZ: Int,
        templateReach: Double,
        random: PositionalRandomFactory,
        visit: (originX: Int, originZ: Int, instanceRandom: RandomSource) -> Unit,
    )

    /** The same layout with every distance multiplied by [factor], so a resized field spreads to match. */
    fun resized(factor: Double): Placement

    companion object {
        val CODEC: Codec<Placement> = PlacementKind.CODEC.dispatch(
            "type",
            { placement: Placement -> placement.kind },
            { kind: PlacementKind -> kind.codec() },
        )
    }
}

enum class PlacementKind(private val makeCodec: () -> MapCodec<out Placement>) : StringRepresentable {
    GRID({ Grid.CODEC }),
    RADIAL({ Radial.CODEC }),
    SCATTER({ Scatter.CODEC });

    fun codec(): MapCodec<out Placement> = makeCodec()

    override fun getSerializedName(): String = name.lowercase()

    companion object {
        val CODEC: Codec<PlacementKind> = StringRepresentable.fromEnum(PlacementKind::values)
    }
}

/**
 * The probability that an instance exists at a point — the unifying parameter that makes a plain grid
 * and a density-gradient grid the same thing. Probability lerps from [atOrigin] (radius 0) to [atEdge]
 * (at and beyond [falloffRadius]). A **uniform** placement is simply [atOrigin] == [atEdge]; a gradient
 * that packs the origin and thins outward is `atOrigin > atEdge`, and the reverse (sparse origin, dense
 * toward the edge — up to overlapping) is `atOrigin < atEdge`.
 */
data class Density(
    val atOrigin: Double,
    val atEdge: Double,
    val falloffRadius: Double,
    /**
     * How far a low-frequency noise swings the probability either way, on top of the radial figure.
     *
     * **This is what turns a sprinkle into an archipelago.** A single probability scatters instances
     * evenly over the whole world, which reads as texture; swinging it across its range gives regions
     * packed with them and regions with none, which reads as somewhere. Zero leaves the radial behaviour
     * exactly as it was.
     */
    val patchiness: Double = NO_PATCHES,
    /** Blocks per unit of that noise — how wide one crowded or empty region runs. */
    val patchScale: Double = DEFAULT_PATCH_SCALE,
    val patchSeed: Long = 0L,
) {

    fun keepProbability(worldX: Int, worldZ: Int): Double {
        val radial = if (atOrigin == atEdge) atOrigin else {
            val radius = sqrt(worldX.toDouble() * worldX + worldZ.toDouble() * worldZ)
            val fraction = (radius / falloffRadius).coerceIn(0.0, 1.0)
            atOrigin + (atEdge - atOrigin) * fraction
        }
        if (patchiness <= NO_PATCHES) return radial
        // Added rather than scaled, and clamped: what an archipelago wants is stretches at nearly one and
        // stretches at nearly nothing, which multiplying a mid probability could never reach.
        val patch = patches.getValue(worldX / patchStretch, 0.0, worldZ / patchStretch).coerceIn(-1.0, 1.0)
        return (radial + patch * patchiness).coerceIn(0.0, 1.0)
    }

    private val patchStretch = patchScale.coerceAtLeast(SMALLEST_STRETCH)
    private val patches = fieldNoise(patchSeed, PATCH_OCTAVE, PATCH_AMPLITUDES)

    /** Probabilities are unitless, so only the distances resize — the falloff and the patches alike. */
    fun resized(factor: Double) = copy(falloffRadius = falloffRadius * factor, patchScale = patchScale * factor)

    companion object {
        val CODEC: MapCodec<Density> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("at_origin").forGetter(Density::atOrigin),
                Codec.DOUBLE.fieldOf("at_edge").forGetter(Density::atEdge),
                Codec.DOUBLE.fieldOf("falloff_radius").forGetter(Density::falloffRadius),
                Codec.DOUBLE.optionalFieldOf("patchiness", NO_PATCHES).forGetter(Density::patchiness),
                Codec.DOUBLE.optionalFieldOf("patch_scale", DEFAULT_PATCH_SCALE).forGetter(Density::patchScale),
                Codec.LONG.optionalFieldOf("patch_seed", 0L).forGetter(Density::patchSeed),
            ).apply(instance, ::Density)
        }

        /** No patches at all: the radial figure stands, which is every placement written before this. */
        const val NO_PATCHES = 0.0

        /** Regions a few thousand blocks across, which is several cells of anything worth patching. */
        const val DEFAULT_PATCH_SCALE = 90.0

        private const val PATCH_OCTAVE = -5
        private val PATCH_AMPLITUDES = listOf(1.0, 0.5)

        fun uniform(probability: Double = 1.0) = Density(probability, probability, 1.0)

        /** Crowded in places and empty in others, about a mean of [probability]. */
        fun patchy(probability: Double, patchiness: Double, patchScale: Double, seed: Long) =
            Density(probability, probability, 1.0, patchiness, patchScale, seed)
    }
}

/**
 * A Cartesian lattice: one candidate instance per lattice point, [spacing] blocks apart, offset by up to
 * [jitter] and kept with probability [density].
 *
 * **The lattice is centred on the world origin**, so `(0, 0)` is always a lattice point rather than the
 * corner between four. Arrival happens at the origin and a per-dimension spawn point cannot be set after
 * the fact (a runtime level gets `DerivedLevelData`, whose `setSpawn` does nothing), so an Age that wants
 * somewhere to stand must put it there during *generation*.
 */
data class Grid(val spacing: Double, val jitter: Double, val density: Density) : Placement {
    override val kind = PlacementKind.GRID

    override fun forEachInstanceNear(
        worldX: Int,
        worldZ: Int,
        templateReach: Double,
        random: PositionalRandomFactory,
        visit: (originX: Int, originZ: Int, instanceRandom: RandomSource) -> Unit,
    ) {
        // An instance can sit up to `jitter` from its lattice point and reach `templateReach` beyond that.
        // Flooring both ends is deliberately generous — it may scan one surplus cell, never one too few.
        val pad = templateReach + jitter
        val minCellX = floor((worldX - pad) / spacing).toInt()
        val maxCellX = floor((worldX + pad) / spacing).toInt()
        val minCellZ = floor((worldZ - pad) / spacing).toInt()
        val maxCellZ = floor((worldZ + pad) / spacing).toInt()

        for (cellX in minCellX..maxCellX) {
            for (cellZ in minCellZ..maxCellZ) {
                val instanceRandom = random.at(cellX, 0, cellZ)
                val originX = (cellX * spacing + jittered(instanceRandom, jitter)).roundToInt()
                val originZ = (cellZ * spacing + jittered(instanceRandom, jitter)).roundToInt()
                if (instanceRandom.nextDouble() < density.keepProbability(originX, originZ)) {
                    visit(originX, originZ, instanceRandom)
                }
            }
        }
    }

    override fun resized(factor: Double) =
        Grid(spacing * factor, jitter * factor, density.resized(factor))

    companion object {
        val CODEC: MapCodec<Grid> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("spacing").forGetter(Grid::spacing),
                Codec.DOUBLE.fieldOf("jitter").forGetter(Grid::jitter),
                Density.CODEC.forGetter(Grid::density),
            ).apply(instance, ::Grid)
        }
    }
}

/**
 * Concentric rings: instances spaced ~[arcSpacing] apart around rings [ringSpacing] apart, each offset
 * up to [jitter] blocks and kept with probability [density]. Leaves an empty centre (the innermost ring
 * is at [ringSpacing]); a radial [density] fades the rings in or out with distance.
 */
data class Radial(
    val ringSpacing: Double,
    val arcSpacing: Double,
    val jitter: Double,
    val density: Density,
) : Placement {
    override val kind = PlacementKind.RADIAL

    override fun forEachInstanceNear(
        worldX: Int,
        worldZ: Int,
        templateReach: Double,
        random: PositionalRandomFactory,
        visit: (originX: Int, originZ: Int, instanceRandom: RandomSource) -> Unit,
    ) {
        val worldRadius = sqrt(worldX.toDouble() * worldX + worldZ.toDouble() * worldZ)
        val reach = templateReach + jitter
        val innerRing = maxOf(1, floor((worldRadius - reach) / ringSpacing).toInt())
        val outerRing = floor((worldRadius + reach) / ringSpacing).toInt()
        if (outerRing < innerRing) return
        val queryAngle = atan2(worldZ.toDouble(), worldX.toDouble())

        for (ring in innerRing..outerRing) {
            val ringRadius = ring * ringSpacing
            val count = maxOf(1, Math.round(TWO_PI * ringRadius / arcSpacing).toInt())
            val angleStep = TWO_PI / count
            val halfWindow = reach / ringRadius // arc-length reach expressed as an angle (ringRadius > 0)

            val fullRing = halfWindow >= PI
            val minSlot = floor((queryAngle - halfWindow) / angleStep).toInt()
            val maxSlot = ceil((queryAngle + halfWindow) / angleStep).toInt()
            if (fullRing || maxSlot - minSlot + 1 >= count) {
                for (aspect in 0..<count) emitSlot(ring, aspect, ringRadius, angleStep, random, visit)
            } else {
                for (rawSlot in minSlot..maxSlot) emitSlot(ring, Math.floorMod(rawSlot, count), ringRadius, angleStep, random, visit)
            }
        }
    }

    private fun emitSlot(
        ring: Int,
        aspect: Int,
        ringRadius: Double,
        angleStep: Double,
        random: PositionalRandomFactory,
        visit: (originX: Int, originZ: Int, instanceRandom: RandomSource) -> Unit,
    ) {
        val instanceRandom = random.at(ring, 0, aspect)
        val angle = aspect * angleStep + jittered(instanceRandom, jitter / ringRadius)
        val radius = ringRadius + jittered(instanceRandom, jitter)
        val originX = (cos(angle) * radius).roundToInt()
        val originZ = (sin(angle) * radius).roundToInt()
        if (instanceRandom.nextDouble() < density.keepProbability(originX, originZ)) {
            visit(originX, originZ, instanceRandom)
        }
    }

    override fun resized(factor: Double) =
        Radial(ringSpacing * factor, arcSpacing * factor, jitter * factor, density.resized(factor))

    companion object {
        val CODEC: MapCodec<Radial> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("ring_spacing").forGetter(Radial::ringSpacing),
                Codec.DOUBLE.fieldOf("arc_spacing").forGetter(Radial::arcSpacing),
                Codec.DOUBLE.fieldOf("jitter").forGetter(Radial::jitter),
                Density.CODEC.forGetter(Radial::density),
            ).apply(instance, ::Radial)
        }
    }
}

/**
 * Free scatter: each [cellSize] square of the world draws its **own number** of instances, anywhere
 * inside itself. The cell is bookkeeping — a way to ask a deterministic question about a bounded piece
 * of an unbounded world — not a position anything is placed at.
 *
 * **What a jittered [Grid] cannot do.** However hard it is jittered a grid still places about one
 * instance per cell, so positions look random close up while the *count* stays even and the layout keeps
 * a rhythm at a distance. Here the count itself varies, so a least of zero gives genuinely empty
 * stretches and knots of several together. If you do not want clumping, [Grid] is cheaper.
 *
 * **It gives up [Grid]'s origin guarantee**: nothing is promised at `(0, 0)`, so an Age placed only this
 * way may have nothing to stand on where a player arrives. `Ages.findFooting` copes, but "you arrive on
 * an island" stops being true by construction.
 *
 * Cost is scanned cells times instances per cell, so [mostPerCell] multiplies every column near anything.
 */
data class Scatter(
    val cellSize: Double,
    /** Zero is the interesting floor: it is what buys empty ground. */
    val leastPerCell: Int,
    val mostPerCell: Int,
    /**
     * Kept for what it does that counts cannot: [Density]'s *radial* form thins or packs the scatter with
     * distance from the origin, which is a different question from how many a cell holds.
     */
    val density: Density,
) : Placement {
    override val kind = PlacementKind.SCATTER

    // Coerced rather than required: this arrives from a codec, and a decoded field tree that throws in
    // its constructor would fail an Age's *load* rather than report a bad number. Nonsense clamps to the
    // nearest sane layout instead.
    private val fewest = leastPerCell.coerceAtLeast(0)
    private val most = mostPerCell.coerceAtLeast(fewest)

    override fun forEachInstanceNear(
        worldX: Int,
        worldZ: Int,
        templateReach: Double,
        random: PositionalRandomFactory,
        visit: (originX: Int, originZ: Int, instanceRandom: RandomSource) -> Unit,
    ) {
        // No jitter term, unlike [Grid]: a cell already contains every instance it draws, so a cell whose
        // own bounds come within reach of the column is exactly the set that can touch it.
        val minCellX = floor((worldX - templateReach) / cellSize).toInt()
        val maxCellX = floor((worldX + templateReach) / cellSize).toInt()
        val minCellZ = floor((worldZ - templateReach) / cellSize).toInt()
        val maxCellZ = floor((worldZ + templateReach) / cellSize).toInt()

        for (cellX in minCellX..maxCellX) {
            for (cellZ in minCellZ..maxCellZ) {
                // The count gets its own aspect in the positional RNG's spare axis so it cannot correlate
                // with any instance's own stream; the instances then take aspects 0, 1, 2… of the same
                // cell. Every draw below is a pure function of (cell, index), which is what keeps two
                // chunk workers agreeing about a cell they both overlap.
                val counting = random.at(cellX, COUNT_SLOT, cellZ)
                val count = if (most == fewest) fewest else fewest + counting.nextInt(most - fewest + 1)
                for (index in 0..<count) {
                    val instanceRandom = random.at(cellX, index, cellZ)
                    val originX = ((cellX + instanceRandom.nextDouble()) * cellSize).roundToInt()
                    val originZ = ((cellZ + instanceRandom.nextDouble()) * cellSize).roundToInt()
                    if (instanceRandom.nextDouble() < density.keepProbability(originX, originZ)) {
                        visit(originX, originZ, instanceRandom)
                    }
                }
            }
        }
    }

    // Counts are unitless — a bigger world holds the same number per (bigger) cell, which is what keeps
    // a resized scatter looking like the same scatter rather than a denser one.
    override fun resized(factor: Double) = copy(cellSize = cellSize * factor, density = density.resized(factor))

    companion object {
        // Any value no instance index can take. Instances count up from zero, so below zero is free.
        private const val COUNT_SLOT = -1

        val CODEC: MapCodec<Scatter> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("cell_size").forGetter(Scatter::cellSize),
                Codec.INT.fieldOf("least_per_cell").forGetter(Scatter::leastPerCell),
                Codec.INT.fieldOf("most_per_cell").forGetter(Scatter::mostPerCell),
                Density.CODEC.forGetter(Scatter::density),
            ).apply(instance, ::Scatter)
        }
    }
}

private const val TWO_PI = 2.0 * PI

/** A random signed offset in [-amount, amount], one RNG draw. */
private fun jittered(random: RandomSource, amount: Double): Double = (random.nextDouble() * 2.0 - 1.0) * amount
