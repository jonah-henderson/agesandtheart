package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.location
import com.google.gson.JsonParser
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import co.voik.agesandtheart.age.aspect.Rung
import net.minecraft.util.RandomSource
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToInt

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
    /**
     * Half a column's side at its widest, in blocks — rolled once per column, so no two are quite alike.
     *
     * `5.0` is a ten-by-ten column. **The first cut was half that and was wrong** (Jonah, 2026-08-31, seen):
     * five blocks is honestly thin, and by a hundred and forty blocks out a column was a bright thread —
     * at odds with the phenomenon's best property, which is that you can see one coming from across the Age.
     */
    val narrowestHalfWidth: Double = DEFAULT_NARROWEST_HALF_WIDTH,
    val widestHalfWidth: Double = DEFAULT_WIDEST_HALF_WIDTH,
    /** How fast it wanders, in blocks per tick — rolled once per column and never again. */
    val slowestSpeed: Double = DEFAULT_SLOWEST_SPEED,
    val fastestSpeed: Double = DEFAULT_FASTEST_SPEED,
    /** How long one lives, in ticks. */
    val shortestLife: Int = DEFAULT_SHORTEST_LIFE,
    val longestLife: Int = DEFAULT_LONGEST_LIFE,
    /**
     * How long a column takes to open, in ticks, and the same again to close.
     *
     * **A count of ticks and not a share of the life** (Jonah, 2026-08-31). A share reads well on paper and
     * is wrong in play: it ties how quickly a column arrives to how long it happens to last, so a long one
     * spends its first two minutes as an invisible thread and a short one snaps open. What a player is
     * watching is a column arriving, and that should take the same three seconds whatever the column then
     * goes on to do.
     */
    val ramp: Int = DEFAULT_RAMP,
    /** How often it may change course, in ticks. Larger is straighter. */
    val turnEvery: Int = DEFAULT_TURN_EVERY,
    /** The most one of those turns may bend it, in degrees. */
    val turnMost: Double = DEFAULT_TURN_MOST,
    /**
     * How far past the footprint sand may land, as a share of the column's **own** half-width.
     *
     * **Proportional rather than a count of blocks**, and that is a correction rather than a preference
     * (measured on a driven server, 2026-08-31). A fixed band read fine at full width and was ruinous at
     * the ends of a life: [depositChanceFor] scales as `1/width` to keep the depth constant, so a
     * hair-thin column hit a band that had not shrunk with it at a rate that had risen to match a column
     * four times its size. One pass left fourteen blocks where four and a half were asked for, and a
     * column too narrow to see laid nearly a full trail. Scaling the band with the column makes the whole
     * footprint linear in its width, so the thin ends leave thin trails.
     */
    val spillShare: Double = DEFAULT_SPILL_SHARE,
    /**
     * How near somebody has to be for real sand to fall, in blocks.
     *
     * **Beyond this the sand is simply placed**, which is `decisions.md`'s rule and not an optimisation:
     * every block in flight is an entity, so a curtain of them at storm scale is thousands of entities
     * alive at once. What they are worth spending on is the few metres where somebody can watch one land.
     */
    val dramaReach: Double = DEFAULT_DRAMA_REACH,
    /**
     * What share of the sand landing near somebody arrives as a real falling block.
     *
     * **It replaces a deposit rather than adding one**, so a trail is exactly as deep whether anybody was
     * watching or not — the drama changes the *delivery* and never the amount.
     */
    val dramaShare: Double = DEFAULT_DRAMA_SHARE,
    /** How many motes of dust hang in a column each tick, for somebody near enough to see them. */
    val dust: Int = DEFAULT_DUST,
    /**
     * How hard the falling sand drives what is standing in it downward, per tick, for a column of the
     * ordinary width — a wider one pushes harder in proportion.
     *
     * There is no counterplay to being *under* it, and that is the design: a sandfall's answer is not to be
     * where it is. What it does to somebody who ignores that should be immediate and not a nudge.
     */
    val push: Double = DEFAULT_PUSH,
    /** How often the column is heard, in ticks. */
    val betweenSounds: Int = DEFAULT_BETWEEN_SOUNDS,
) {
    /**
     * How likely one position under the footprint is to be given a block this tick.
     *
     * **Derived rather than dialled**, which is the whole of why [depth] is what a pack writes. A patch of
     * ground stands under a footprint `2·halfWidth` wide moving at `speed` for `2·halfWidth / speed` ticks,
     * so a chance of `depth · speed / (2 · fullHalfWidth)` leaves that many blocks behind. A faster column
     * deposits harder per tick and leaves the same trail, and a wider one deposits more slowly per position
     * and leaves the same trail over more ground.
     *
     * **[fullHalfWidth] is the width this column will reach, and how wide it stands *now* only decides
     * which positions are covered** (measured on a driven server, 2026-08-31). Scaling the rate by the current width instead —
     * to hold the depth constant all through a life — reads plausibly and is a trap: as a column closes,
     * `1/width` saturates the chance at certainty while the footprint shrinks to a single position, so a
     * dying column stops walking and **drills a tower of sand straight up**, seventeen blocks of it. A
     * narrow column should leave a narrow *and shallow* trail, because a narrow column is carrying less
     * sand — not the same sand through a smaller hole.
     *
     * **The spill is subtracted, so [depth] is what a pass actually leaves.** A patch in the middle is
     * under the footprint *and* then inside the band on the way out, so it collects `spillShare / 2` of a
     * pass over again; taking that off here is what keeps the number a pack writes honest, rather than a
     * number a pack writes and then measures.
     */
    fun depositChanceFor(speed: Double, fullHalfWidth: Double, depth: Double): Double {
        if (fullHalfWidth <= CLOSED) return CLOSED
        val alsoFromTheSpill = FULLY_OPEN + spillShare / BOTH_SIDES
        return (depth * speed / (fullHalfWidth * BOTH_SIDES * alsoFromTheSpill)).coerceIn(CLOSED, CERTAIN)
    }

    /**
     * What share of the deposit survives [past] blocks outside a column standing [standing] wide — one
     * inside it, nothing at the outer limit, and a straight line between.
     *
     * The straight line is what makes the trail's edge ragged rather than cut: near the footprint almost
     * everything lands, and a block or two out only the occasional one does.
     */
    fun spillFadeAt(standing: Double, past: Double): Double {
        if (past <= CLOSED) return FULLY_OPEN
        val band = standing * spillShare
        if (band <= CLOSED) return CLOSED
        return (FULLY_OPEN - past / band).coerceAtLeast(CLOSED)
    }

    /** How far out a position can possibly be given a block, in whole blocks. */
    fun spillReachAt(standing: Double): Int = ceil(standing * (FULLY_OPEN + spillShare)).toInt()

    /**
     * How fast a column of this [depth] pours, in whole turns of the roil a day — **what ties what you can
     * see to what it is doing to the ground.**
     *
     * A column that buries deeper visibly streams harder, which is the only cue a player has for how bad
     * the one walking at them is before it arrives. Whole turns because the shader's drift is a day
     * fraction that wraps at dawn, and quantised to a colour channel's step for the same reason — see
     * `sand_column.fsh`.
     */
    fun poursAt(depth: Double): Int {
        if (this.depth <= CLOSED) return ORDINARY_POUR
        return (ORDINARY_POUR * depth / this.depth).roundToInt().coerceIn(ORDINARY_POUR, FASTEST_POUR)
    }

    /**
     * How wide a column of [fullHalfWidth] stands at [age] of a life of [lifetime] — **the spawn animation
     * and the death animation, which are one function read forwards.**
     *
     * Nothing at either end, [fullHalfWidth] in the middle, and the two ramps are the same shape because the
     * brief is that the column closes the way it opened. [ramp] is a count of ticks, so arriving takes the
     * same three seconds whether the column then walks for two minutes or for six.
     *
     * `min(rising, falling, 1)` is the whole trapezoid, and the min is what handles the degenerate case: a
     * column that will not live long enough to open and close in turn never quite opens, rather than
     * snapping to full width and back.
     *
     * Smoothed rather than linear, so it swells and settles instead of growing at a constant rate.
     */
    fun halfWidthAt(age: Int, lifetime: Int, fullHalfWidth: Double): Double {
        if (age <= 0 || age >= lifetime || lifetime <= 0) return CLOSED
        if (ramp <= NO_TICKS) return fullHalfWidth
        val rising = age.toDouble() / ramp
        val falling = (lifetime - age).toDouble() / ramp
        return fullHalfWidth * smoothed(minOf(rising, falling, FULLY_OPEN))
    }

    /** How far one course change may bend a column, drawn so that small turns are much likelier than large. */
    fun turnedBy(random: RandomSource): Double = (random.nextDouble() - random.nextDouble()) * turnMost

    companion object {
        private const val DEFAULT_DEPTH = 4.5
        private const val DEFAULT_NARROWEST_HALF_WIDTH = 4.0
        private const val DEFAULT_WIDEST_HALF_WIDTH = 6.0
        private const val DEFAULT_SLOWEST_SPEED = 0.03
        private const val DEFAULT_FASTEST_SPEED = 0.09
        private const val DEFAULT_SHORTEST_LIFE = 2400
        private const val DEFAULT_LONGEST_LIFE = 7200
        /** Three seconds, and the same again to close. */
        private const val DEFAULT_RAMP = 60
        private const val DEFAULT_TURN_EVERY = 120
        private const val DEFAULT_TURN_MOST = 30.0
        private const val DEFAULT_SPILL_SHARE = 0.8
        private const val DEFAULT_DRAMA_REACH = 32.0
        private const val DEFAULT_DRAMA_SHARE = 0.12
        private const val DEFAULT_DUST = 8
        private const val DEFAULT_PUSH = 0.7
        private const val DEFAULT_BETWEEN_SOUNDS = 4

        /** What an ordinary column pours at, and the fastest anything may — see [poursAt]. */
        const val ORDINARY_POUR = 900
        const val FASTEST_POUR = 2550

        private const val BOTH_SIDES = 2.0
        private const val CERTAIN = 1.0
        private const val CLOSED = 0.0
        private const val FULLY_OPEN = 1.0
        private const val NO_TICKS = 0

        /** Hermite's own, so the ramp leaves and arrives at rest. */
        private fun smoothed(openness: Double): Double =
            openness * openness * (SMOOTH_PEAK - SMOOTH_SLOPE * openness)

        private const val SMOOTH_PEAK = 3.0
        private const val SMOOTH_SLOPE = 2.0

        val ORDINARY = ColumnBehaviour()

        val CODEC: Codec<ColumnBehaviour> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.optionalFieldOf("depth", DEFAULT_DEPTH).forGetter(ColumnBehaviour::depth),
                Codec.DOUBLE.optionalFieldOf("narrowest_half_width", DEFAULT_NARROWEST_HALF_WIDTH)
                    .forGetter(ColumnBehaviour::narrowestHalfWidth),
                Codec.DOUBLE.optionalFieldOf("widest_half_width", DEFAULT_WIDEST_HALF_WIDTH)
                    .forGetter(ColumnBehaviour::widestHalfWidth),
                Codec.DOUBLE.optionalFieldOf("slowest_speed", DEFAULT_SLOWEST_SPEED)
                    .forGetter(ColumnBehaviour::slowestSpeed),
                Codec.DOUBLE.optionalFieldOf("fastest_speed", DEFAULT_FASTEST_SPEED)
                    .forGetter(ColumnBehaviour::fastestSpeed),
                Codec.INT.optionalFieldOf("shortest_life", DEFAULT_SHORTEST_LIFE)
                    .forGetter(ColumnBehaviour::shortestLife),
                Codec.INT.optionalFieldOf("longest_life", DEFAULT_LONGEST_LIFE)
                    .forGetter(ColumnBehaviour::longestLife),
                Codec.INT.optionalFieldOf("ramp", DEFAULT_RAMP).forGetter(ColumnBehaviour::ramp),
                Codec.INT.optionalFieldOf("turn_every", DEFAULT_TURN_EVERY)
                    .forGetter(ColumnBehaviour::turnEvery),
                Codec.DOUBLE.optionalFieldOf("turn_most", DEFAULT_TURN_MOST)
                    .forGetter(ColumnBehaviour::turnMost),
                Codec.DOUBLE.optionalFieldOf("spill_share", DEFAULT_SPILL_SHARE)
                    .forGetter(ColumnBehaviour::spillShare),
                Codec.DOUBLE.optionalFieldOf("drama_reach", DEFAULT_DRAMA_REACH)
                    .forGetter(ColumnBehaviour::dramaReach),
                Codec.DOUBLE.optionalFieldOf("drama_share", DEFAULT_DRAMA_SHARE)
                    .forGetter(ColumnBehaviour::dramaShare),
                Codec.INT.optionalFieldOf("dust", DEFAULT_DUST).forGetter(ColumnBehaviour::dust),
                Codec.DOUBLE.optionalFieldOf("push", DEFAULT_PUSH).forGetter(ColumnBehaviour::push),
                Codec.INT.optionalFieldOf("between_sounds", DEFAULT_BETWEEN_SOUNDS)
                    .forGetter(ColumnBehaviour::betweenSounds),
            ).apply(instance, ::ColumnBehaviour)
        }
    }
}

/**
 * What the worst an Age can be makes of a sandfall — the far end of every dial, reached at full instability.
 *
 * **A separate record because these are one idea**: everything here is "and at its very worst", and reading
 * them beside the ordinary numbers is how a pack author sees the span they are tuning. (It is also what
 * keeps [ColumnBehaviour] inside a codec group's sixteen fields, which is a real limit and not a style.)
 *
 * Nothing here is reached without the budget for it. [co.voik.agesandtheart.age.Manifestation.SANDFALL]
 * prices the steps and `Price.most` caps them, so this is the top of a ramp rather than a switch.
 */
data class Fury(
    /** Half a column's side at the worst — `10.0` is the twenty-by-twenty the brief asks for. */
    val halfWidth: Double = DEFAULT_FURY_HALF_WIDTH,
    /** How deep one pass deposits at the worst. */
    val depth: Double = DEFAULT_FURY_DEPTH,
    /** How long one lives at the worst, in ticks. */
    val life: Int = DEFAULT_FURY_LIFE,
    /** How many may stand at once at the worst — **and the ceiling, whatever a rung asks on top.** */
    val atOnce: Int = DEFAULT_FURY_AT_ONCE,
    /** How long between them at the worst, in ticks. */
    val betweenSpawns: Int = DEFAULT_FURY_BETWEEN_SPAWNS,
) {
    companion object {
        private const val DEFAULT_FURY_HALF_WIDTH = 10.0
        private const val DEFAULT_FURY_DEPTH = 11.0
        private const val DEFAULT_FURY_LIFE = 12000
        private const val DEFAULT_FURY_AT_ONCE = 6
        private const val DEFAULT_FURY_BETWEEN_SPAWNS = 400

        val ORDINARY = Fury()

        val CODEC: Codec<Fury> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.optionalFieldOf("half_width", DEFAULT_FURY_HALF_WIDTH).forGetter(Fury::halfWidth),
                Codec.DOUBLE.optionalFieldOf("depth", DEFAULT_FURY_DEPTH).forGetter(Fury::depth),
                Codec.INT.optionalFieldOf("life", DEFAULT_FURY_LIFE).forGetter(Fury::life),
                Codec.INT.optionalFieldOf("at_once", DEFAULT_FURY_AT_ONCE).forGetter(Fury::atOnce),
                Codec.INT.optionalFieldOf("between_spawns", DEFAULT_FURY_BETWEEN_SPAWNS)
                    .forGetter(Fury::betweenSpawns),
            ).apply(instance, ::Fury)
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
    /** What the greatest instability makes of one — see [Fury]. */
    val fury: Fury = Fury.ORDINARY,
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
    /**
     * How many columns may stand at once — the rung multiplying [atMost], on top of however far the Age's
     * instability has pushed that number toward [Fury.atOnce].
     *
     * **They compound rather than one winning** (Jonah, 2026-08-31): `teeming sandfall` written into an Age
     * that is also coming apart is worse than either alone. [Fury.atOnce] is the ceiling on the result
     * whatever the rung asks, because this is the axis that costs — see the plan's §11 measurement.
     */
    fun atMostFor(density: Double, fury: Double): Int {
        val furious = lerp(atMost.toDouble(), this.fury.atOnce.toDouble(), fury)
        return Happenings.timesFor(density, furious.roundToInt()).coerceAtMost(this.fury.atOnce)
    }

    /**
     * How long between one column and the next at a claim's [density], in ticks.
     *
     * **Read off the density rather than off [atMostFor], which is a correction** (2026-08-31, driven).
     * Deriving the wait from the *count* looked tidier — the two could then never disagree — and made
     * `scarce sandfall` do nothing at all: the count floors at one, so a quarter-strength claim came out
     * with the same one column at the same interval as an ordinary one. Both are honest functions of the
     * density instead, and the one that can still move is the one that moves.
     */
    fun betweenSpawnsFor(density: Double, fury: Double): Int {
        val furious = lerp(betweenSpawns.toDouble(), this.fury.betweenSpawns.toDouble(), fury)
        if (density <= NO_CLAIM) return furious.roundToInt().coerceAtLeast(AT_ONCE)
        return (furious / (density / Rung.ORDINARY)).roundToInt().coerceAtLeast(AT_ONCE)
    }

    /** How wide a column may grow, at this much fury — the range's far end. */
    fun widestHalfWidthAt(fury: Double): Double = lerp(column.widestHalfWidth, this.fury.halfWidth, fury)

    /** And its near end, kept in proportion so a fierce Age still sends columns of differing sizes. */
    fun narrowestHalfWidthAt(fury: Double): Double =
        column.narrowestHalfWidth * (widestHalfWidthAt(fury) / column.widestHalfWidth)

    /** How deep one pass deposits, at this much fury. */
    fun depthAt(fury: Double): Double = lerp(column.depth, this.fury.depth, fury)

    /** How long a column lives, at this much fury — both ends of the range, kept in proportion. */
    fun longestLifeAt(fury: Double): Int = lerp(column.longestLife.toDouble(), this.fury.life.toDouble(), fury).roundToInt()

    fun shortestLifeAt(fury: Double): Int =
        (column.shortestLife.toDouble() * longestLifeAt(fury) / column.longestLife).roundToInt()

    companion object {
        /** Straight between the ordinary number and the furious one; [howFar] is clamped by its caller. */
        private fun lerp(ordinary: Double, furious: Double, howFar: Double): Double =
            ordinary + (furious - ordinary) * howFar

        private const val NO_CLAIM = 0.0
        private const val AT_ONCE = 1
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
                Fury.CODEC.optionalFieldOf("fury", Fury.ORDINARY).forGetter(SandfallBehaviour::fury),
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
