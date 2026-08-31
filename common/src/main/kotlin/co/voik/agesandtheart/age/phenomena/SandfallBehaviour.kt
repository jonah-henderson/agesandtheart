package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.location
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.RandomSource
import kotlin.math.pow

/**
 * What one column of sand is like — the inner half of `art/phenomenon/sandfall.json`.
 *
 * **Split from [SandfallBehaviour] along the seam the code already has**: everything here is read by
 * [SandColumn] once it is standing, and everything there is read by [Sandfall] deciding whether to stand
 * one up. (The split was forced by a codec group being capped at sixteen fields, and it is the shape the
 * file wanted regardless.)
 *
 * **What a pack sets is how deep a pass leaves the ground, not how fast sand falls.** The deposit rate is
 * derived from [depth], [halfWidth] and the column's own speed ([depositChanceFor]), so speed stays a free
 * variable instead of a second number to keep in step with this one.
 */
data class ColumnBehaviour(
    /**
     * How many blocks deep one pass leaves the ground it crossed, edge to edge.
     *
     * The number a person can picture, and the only one the deposit rule needs — see [depositChanceFor].
     */
    val depth: Double = DEFAULT_DEPTH,
    /** Half the column's side at its widest, in blocks. `2.5` is the 5×5 first cut. */
    val halfWidth: Double = DEFAULT_HALF_WIDTH,
    /** How fast it wanders, in blocks per tick — rolled once per column and never again. */
    val slowestSpeed: Double = DEFAULT_SLOWEST_SPEED,
    val fastestSpeed: Double = DEFAULT_FASTEST_SPEED,
    /** How long one lives, in ticks. */
    val shortestLife: Int = DEFAULT_SHORTEST_LIFE,
    val longestLife: Int = DEFAULT_LONGEST_LIFE,
    /**
     * What share of a life is spent widening, and the same again narrowing.
     *
     * A **share** rather than a count of ticks, so a short column and a long one both open and close in
     * proportion, and one dial says what two would have.
     */
    val rampShare: Double = DEFAULT_RAMP_SHARE,
    /** How often it may change course, in ticks. Larger is straighter. */
    val turnEvery: Int = DEFAULT_TURN_EVERY,
    /** The most one of those turns may bend it, in degrees. */
    val turnMost: Double = DEFAULT_TURN_MOST,
    /** How far past the footprint sand may land, in blocks. */
    val spillReach: Int = DEFAULT_SPILL_REACH,
    /** What share of the deposit chance survives each block past the edge. */
    val spillFalloff: Double = DEFAULT_SPILL_FALLOFF,
) {
    /**
     * How likely one position under the footprint is to be given a block this tick.
     *
     * **Derived rather than dialled**, which is the whole of why [depth] is what a pack writes. A patch of
     * ground stands under a footprint `2·halfWidth` wide moving at `speed` for `2·halfWidth / speed` ticks,
     * so a chance of `depth · speed / (2 · halfWidth)` leaves `depth` blocks behind. A faster column
     * deposits harder per tick and leaves the same trail.
     */
    fun depositChanceFor(speed: Double): Double =
        (depth * speed / (halfWidth * BOTH_SIDES)).coerceIn(CLOSED, CERTAIN)

    /** How likely a position [past] blocks outside the footprint is to be given a block. */
    fun spillChanceFor(speed: Double, past: Int): Double =
        depositChanceFor(speed) * spillFalloff.pow(past)

    /**
     * How wide the column stands at [age] of a life of [lifetime] — **the spawn animation and the death
     * animation, which are one function read forwards.**
     *
     * Nothing at either end, [halfWidth] in the middle, and the two ramps are the same shape because the
     * brief is that the column closes the way it opened.
     *
     * **The ramp is a share of the life rather than a count of ticks**, so every column opens fully whatever
     * its lifetime and a short one simply opens faster. One dial says what two would have, and a debug
     * column stood up to be looked at does not spend its whole life widening.
     *
     * `min(rising, falling, 1)` is the whole trapezoid, and the min is what handles the one degenerate case:
     * a pack writing [rampShare] above a half has the two ramps overlap, and the column then never quite
     * opens rather than snapping to full width and back.
     *
     * Smoothed rather than linear, so it swells and settles instead of growing at a constant rate.
     */
    fun halfWidthAt(age: Int, lifetime: Int): Double {
        if (age <= 0 || age >= lifetime || lifetime <= 0) return CLOSED
        val ramp = lifetime * rampShare
        if (ramp <= CLOSED) return halfWidth
        val rising = age / ramp
        val falling = (lifetime - age) / ramp
        return halfWidth * smoothed(minOf(rising, falling, FULLY_OPEN))
    }

    /** How far one course change may bend a column, drawn so that small turns are much likelier than large. */
    fun turnedBy(random: RandomSource): Double = (random.nextDouble() - random.nextDouble()) * turnMost

    companion object {
        private const val DEFAULT_DEPTH = 4.5
        private const val DEFAULT_HALF_WIDTH = 2.5
        private const val DEFAULT_SLOWEST_SPEED = 0.03
        private const val DEFAULT_FASTEST_SPEED = 0.09
        private const val DEFAULT_SHORTEST_LIFE = 2400
        private const val DEFAULT_LONGEST_LIFE = 7200
        private const val DEFAULT_RAMP_SHARE = 0.15
        private const val DEFAULT_TURN_EVERY = 120
        private const val DEFAULT_TURN_MOST = 30.0
        private const val DEFAULT_SPILL_REACH = 2
        private const val DEFAULT_SPILL_FALLOFF = 0.4

        private const val BOTH_SIDES = 2.0
        private const val CERTAIN = 1.0
        private const val CLOSED = 0.0
        private const val FULLY_OPEN = 1.0

        /** Hermite's own, so the ramp leaves and arrives at rest. */
        private fun smoothed(openness: Double): Double =
            openness * openness * (SMOOTH_PEAK - SMOOTH_SLOPE * openness)

        private const val SMOOTH_PEAK = 3.0
        private const val SMOOTH_SLOPE = 2.0

        val ORDINARY = ColumnBehaviour()

        val CODEC: Codec<ColumnBehaviour> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.optionalFieldOf("depth", DEFAULT_DEPTH).forGetter(ColumnBehaviour::depth),
                Codec.DOUBLE.optionalFieldOf("half_width", DEFAULT_HALF_WIDTH)
                    .forGetter(ColumnBehaviour::halfWidth),
                Codec.DOUBLE.optionalFieldOf("slowest_speed", DEFAULT_SLOWEST_SPEED)
                    .forGetter(ColumnBehaviour::slowestSpeed),
                Codec.DOUBLE.optionalFieldOf("fastest_speed", DEFAULT_FASTEST_SPEED)
                    .forGetter(ColumnBehaviour::fastestSpeed),
                Codec.INT.optionalFieldOf("shortest_life", DEFAULT_SHORTEST_LIFE)
                    .forGetter(ColumnBehaviour::shortestLife),
                Codec.INT.optionalFieldOf("longest_life", DEFAULT_LONGEST_LIFE)
                    .forGetter(ColumnBehaviour::longestLife),
                Codec.DOUBLE.optionalFieldOf("ramp_share", DEFAULT_RAMP_SHARE)
                    .forGetter(ColumnBehaviour::rampShare),
                Codec.INT.optionalFieldOf("turn_every", DEFAULT_TURN_EVERY)
                    .forGetter(ColumnBehaviour::turnEvery),
                Codec.DOUBLE.optionalFieldOf("turn_most", DEFAULT_TURN_MOST)
                    .forGetter(ColumnBehaviour::turnMost),
                Codec.INT.optionalFieldOf("spill_reach", DEFAULT_SPILL_REACH)
                    .forGetter(ColumnBehaviour::spillReach),
                Codec.DOUBLE.optionalFieldOf("spill_falloff", DEFAULT_SPILL_FALLOFF)
                    .forGetter(ColumnBehaviour::spillFalloff),
            ).apply(instance, ::ColumnBehaviour)
        }
    }
}

/**
 * How a sandfall comes — **datapack content** (`art/phenomenon/sandfall.json`), like [Intensity] and
 * [PhenomenonBehaviour] beside it, and cached on the resource manager's identity for the same reason.
 *
 * This half is the weather that sends columns; [column] is what one of them is like once it is standing.
 */
data class SandfallBehaviour(
    val column: ColumnBehaviour = ColumnBehaviour.ORDINARY,
    /** How many may stand in an Age at once, before the claim's rung multiplies it. */
    val atMost: Int = DEFAULT_AT_MOST,
    /** How long between one column and the next, in ticks, before the rung divides it. */
    val betweenSpawns: Int = DEFAULT_BETWEEN_SPAWNS,
    /** How far from a player one is stood up, in blocks — far enough to be seen coming. */
    val nearestSpawn: Int = DEFAULT_NEAREST_SPAWN,
    val furthestSpawn: Int = DEFAULT_FURTHEST_SPAWN,
    /** How far a column may be from everyone and still count as watched, in blocks. */
    val forgottenAt: Double = DEFAULT_FORGOTTEN_AT,
    /**
     * How long a column may go unwatched before it is given up, in ticks.
     *
     * **A grace rather than an instant**, because "nobody is near it" is true for a tick every time
     * somebody steps out of range and comes back, and a column that died of that would vanish out from
     * under a player who walked around a hill.
     */
    val forgottenAfter: Int = DEFAULT_FORGOTTEN_AFTER,
) {
    companion object {
        private const val DEFAULT_AT_MOST = 1
        private const val DEFAULT_BETWEEN_SPAWNS = 3600
        private const val DEFAULT_NEAREST_SPAWN = 96
        private const val DEFAULT_FURTHEST_SPAWN = 192
        private const val DEFAULT_FORGOTTEN_AT = 320.0
        private const val DEFAULT_FORGOTTEN_AFTER = 600

        /** What a sandfall nobody tuned comes at, so an absent file is a default rather than a dead one. */
        val ORDINARY = SandfallBehaviour()

        val CODEC: Codec<SandfallBehaviour> = RecordCodecBuilder.create { instance ->
            instance.group(
                ColumnBehaviour.CODEC.optionalFieldOf("column", ColumnBehaviour.ORDINARY)
                    .forGetter(SandfallBehaviour::column),
                Codec.INT.optionalFieldOf("at_most", DEFAULT_AT_MOST).forGetter(SandfallBehaviour::atMost),
                Codec.INT.optionalFieldOf("between_spawns", DEFAULT_BETWEEN_SPAWNS)
                    .forGetter(SandfallBehaviour::betweenSpawns),
                Codec.INT.optionalFieldOf("nearest_spawn", DEFAULT_NEAREST_SPAWN)
                    .forGetter(SandfallBehaviour::nearestSpawn),
                Codec.INT.optionalFieldOf("furthest_spawn", DEFAULT_FURTHEST_SPAWN)
                    .forGetter(SandfallBehaviour::furthestSpawn),
                Codec.DOUBLE.optionalFieldOf("forgotten_at", DEFAULT_FORGOTTEN_AT)
                    .forGetter(SandfallBehaviour::forgottenAt),
                Codec.INT.optionalFieldOf("forgotten_after", DEFAULT_FORGOTTEN_AFTER)
                    .forGetter(SandfallBehaviour::forgottenAfter),
            ).apply(instance, ::SandfallBehaviour)
        }

        private const val FILE = "art/phenomenon/sandfall.json"

        /** What this server currently says a sandfall does, cached on the resource manager as the corpus is. */
        fun of(server: MinecraftServer): SandfallBehaviour {
            val resources = server.resourceManager
            loaded?.let { (from, known) -> if (from === resources) return known }
            return read(resources).also { loaded = resources to it }
        }

        private var loaded: Pair<ResourceManager, SandfallBehaviour>? = null

        private fun read(resources: ResourceManager): SandfallBehaviour {
            val file = FILE.location()
            val resource = resources.getResource(file).orElse(null) ?: return ORDINARY
            val read = runCatching {
                resource.open().use { CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(it.reader())).getOrThrow() }
            }
            // A pack that writes nonsense gets the ordinary column and a line in the log, rather than a
            // server that will not start over a number.
            read.onFailure { Constants.LOG.warn("Could not read '{}': {}", file, it.message) }
            return read.getOrNull() ?: ORDINARY
        }
    }
}
