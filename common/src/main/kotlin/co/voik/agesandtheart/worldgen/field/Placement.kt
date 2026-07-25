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
 * How an [Instanced] field lays its template copies out across the world. The contract is one method:
 * for a queried column, visit every instance origin whose footprint could reach it. Strategies differ
 * only in *where* the origins fall — a Cartesian [Grid] or concentric [Radial] rings — but both share a
 * [Density] (see `notes/terrain-architecture.md`).
 *
 * Placement is data (serialised via [CODEC]); instance randomness comes from a [PositionalRandomFactory]
 * (Minecraft's own deterministic per-coordinate RNG — well-mixed and thread-safe) supplied by the
 * caller, so nothing is stored per instance.
 */
sealed interface Placement {

    val kind: PlacementKind

    /**
     * Invoke [visit] for each surviving instance origin near ([worldX], [worldZ]) — every origin within
     * [templateReach] (the template's [TerrainField.horizontalReach]) of the column, so no covering
     * instance is missed. Each visit carries the instance's own deterministic [RandomSource] (from
     * [random]`.at(cell)`), already advanced past this placement's own draws, for template choice.
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
    RADIAL({ Radial.CODEC });

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
data class Density(val atOrigin: Double, val atEdge: Double, val falloffRadius: Double) {

    fun keepProbability(worldX: Int, worldZ: Int): Double {
        if (atOrigin == atEdge) return atOrigin
        val radius = sqrt(worldX.toDouble() * worldX + worldZ.toDouble() * worldZ)
        val fraction = (radius / falloffRadius).coerceIn(0.0, 1.0)
        return atOrigin + (atEdge - atOrigin) * fraction
    }

    /** Probabilities are unitless, so only the distance over which they fall off resizes. */
    fun resized(factor: Double) = copy(falloffRadius = falloffRadius * factor)

    companion object {
        val CODEC: MapCodec<Density> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("at_origin").forGetter(Density::atOrigin),
                Codec.DOUBLE.fieldOf("at_edge").forGetter(Density::atEdge),
                Codec.DOUBLE.fieldOf("falloff_radius").forGetter(Density::falloffRadius),
            ).apply(instance, ::Density)
        }

        /** Every cell, everywhere — the plain regular/jittered grid. */
        fun uniform(probability: Double = 1.0) = Density(probability, probability, 1.0)

        fun radial(atOrigin: Double, atEdge: Double, falloffRadius: Double) = Density(atOrigin, atEdge, falloffRadius)
    }
}

/**
 * A Cartesian lattice: one candidate instance per cell of side [spacing], at the cell centre plus a
 * random offset up to [jitter] blocks, kept with probability [density]. `jitter = 0` + `Density.uniform()`
 * is an exactly-regular grid; a radial [density] packs or thins the grid by distance from the origin.
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
        // A cell's instance can sit up to `jitter` from centre and reach `templateReach` beyond that.
        val pad = templateReach + jitter
        val minCellX = floor((worldX - pad) / spacing).toInt()
        val maxCellX = floor((worldX + pad) / spacing).toInt()
        val minCellZ = floor((worldZ - pad) / spacing).toInt()
        val maxCellZ = floor((worldZ + pad) / spacing).toInt()

        for (cellX in minCellX..maxCellX) {
            for (cellZ in minCellZ..maxCellZ) {
                val instanceRandom = random.at(cellX, 0, cellZ)
                val originX = (cellX * spacing + spacing / 2 + jittered(instanceRandom, jitter)).roundToInt()
                val originZ = (cellZ * spacing + spacing / 2 + jittered(instanceRandom, jitter)).roundToInt()
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
                for (slot in 0..<count) emitSlot(ring, slot, ringRadius, angleStep, random, visit)
            } else {
                for (rawSlot in minSlot..maxSlot) emitSlot(ring, Math.floorMod(rawSlot, count), ringRadius, angleStep, random, visit)
            }
        }
    }

    private fun emitSlot(
        ring: Int,
        slot: Int,
        ringRadius: Double,
        angleStep: Double,
        random: PositionalRandomFactory,
        visit: (originX: Int, originZ: Int, instanceRandom: RandomSource) -> Unit,
    ) {
        val instanceRandom = random.at(ring, 0, slot)
        val angle = slot * angleStep + jittered(instanceRandom, jitter / ringRadius)
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

private const val TWO_PI = 2.0 * PI

/** A random signed offset in [-amount, amount], one RNG draw. */
private fun jittered(random: RandomSource, amount: Double): Double = (random.nextDouble() * 2.0 - 1.0) * amount
