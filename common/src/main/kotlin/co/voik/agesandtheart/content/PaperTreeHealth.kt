package co.voik.agesandtheart.content

import it.unimi.dsi.fastutil.HashCommon
import net.minecraft.world.level.block.state.properties.IntegerProperty
import kotlin.math.abs

/**
 * Whether a paper tree suits where it stands, and how far gone it is where it does not (design §7.1.2).
 *
 * **Moisture is the share of the last tide that its roots spent wet**: the heart looks once a minute and
 * keeps the last [SAMPLES] looks, one vanilla tide's worth. The healthy band is the middle; below it the tree
 * is sere and above it drowned, so a root always wet drowns and one always dry dries out, and only
 * alternation keeps it. A tide wets a root at the waterline a third or two thirds of each cycle, and either
 * reads inside the band at every phase of it.
 *
 * **Not farmland's model, which the design first named**, and measured out of it: a level that a random tick
 * raises when wet and lowers when dry has no pull toward the middle, so a tide's third-or-two-thirds drifts it
 * steadily to an end, and even an exact half is a random walk that leaves the band in the end; the random
 * tick's own spacing adds as much noise again. A share over a whole tide cancels the tide's own rhythm and
 * reads its duty cycle, which is what "alternation" means.
 *
 * **Strain is how long it has been out of its band, and which way** — negative sere, positive drowned — and
 * what it has reached is how the tree shows it, from the extremities in. Back in its band, strain eases, the
 * leaves green again, and what was lost regrows; past the logs, nothing comes back.
 *
 * Pure, so the rules read and can be checked without a world.
 */
object PaperTreeHealth {

    /** The heart's and the sapling's moisture, shown on the block so its tint can say which band it is in. */
    val MOISTURE: IntegerProperty = IntegerProperty.create("moisture", DRIEST, WETTEST)

    /** Where a sapling or a newly grown root starts, in the middle of its band. */
    const val SETTLED = 8

    /** How many looks the heart remembers — a vanilla tide's worth, at one a [SAMPLE_EVERY]. */
    const val SAMPLES = 20

    /** A minute between looks, so [SAMPLES] of them are one moon's cycle, twenty minutes. */
    const val SAMPLE_EVERY = 1200

    /** A new heart's memory: wet and dry by turns, which reads as the middle of its band. */
    const val SETTLED_HISTORY = 0b01010101010101010101

    /**
     * Whether a heart at [place] looks at [gameTime]: once a minute, at a different moment of each. **A fixed
     * moment let a redstone clock pick the tree's moisture**, since a clock whose period divides the minute —
     * nearly every fast one — was caught at the same point of its cycle every look, and dispensers on a clock
     * (the farm the design wants) read as always wet or always dry and killed the tree with nothing to see.
     *
     * **The moment walks the minute by the golden ratio, not at random.** A random moment reads a fast clock
     * as coin flips, and twenty flips stray: in a simulated hundred hours a clock wet two fifths of the time
     * killed the tree outright. The golden stride spreads any twenty looks evenly over any cycle, so a clock
     * reads as its share of wet, as a tide does. Each heart starts at its own place in the walk, so a grove
     * does not all look on the same tick.
     */
    fun isTimeToLook(gameTime: Long, place: Long): Boolean {
        val minute = Math.floorDiv(gameTime, SAMPLE_EVERY)
        val turn = (minute * GOLDEN_STRIDE + HashCommon.mix(place)) and WHOLE_TURN
        val moment = (turn * SAMPLE_EVERY) ushr Int.SIZE_BITS
        return Math.floorMod(gameTime, SAMPLE_EVERY).toLong() == moment
    }

    /** Measured: a polar sun lights the open air to 14, and noon in the overworld to 15. */
    private const val GROWS_IN_LIGHT_FROM = 12
    private const val GROWS_IN_LIGHT_UP_TO = 14

    private const val REMEMBERED = (1 shl SAMPLES) - 1

    /** [history] with one more look at the end of it, and the oldest forgotten. */
    fun remembered(history: Int, isWet: Boolean): Int = ((history shl 1) or if (isWet) 1 else 0) and REMEMBERED

    /**
     * A memory that reads as [moisture] — what a sapling hands the heart it grows into. The wet looks are
     * spread evenly through it, so the tree's first minutes weigh wet and dry alike rather than remembering
     * a soaking it never had.
     */
    fun historyFor(moisture: Int): Int {
        val wetLooks = Math.round(SAMPLES.toFloat() * moisture / WETTEST).coerceIn(0, SAMPLES)
        var history = 0
        for (look in 0..<SAMPLES) {
            val isWet = (look + 1) * wetLooks / SAMPLES > look * wetLooks / SAMPLES
            if (isWet) history = history or (1 shl look)
        }
        return history
    }

    /** The moisture [history] comes to: the share of its looks that were wet, on the block's scale. */
    fun moistureOf(history: Int): Int = Math.round(WETTEST.toFloat() * Integer.bitCount(history) / SAMPLES)

    enum class Band { SERE, SUITS, DROWNED }

    /** How far the tree has gone, in the order design §7.1.2 lists it. */
    enum class Stage { HEALTHY, OUTER_LEAVES_TURNING, ALL_LEAVES_TURNING, LEAVES_FALLEN, LOGS_DYING, ROOT_DYING }

    fun bandOf(moisture: Int): Band = when {
        moisture < HEALTHY_FROM -> Band.SERE
        moisture > HEALTHY_TO -> Band.DROWNED
        else -> Band.SUITS
    }

    /** A sapling's moisture after one random tick, a step toward wet or toward dry — a counter lives long enough. */
    fun moistened(moisture: Int, isWet: Boolean): Int =
        (moisture + if (isWet) 1 else -1).coerceIn(DRIEST, WETTEST)

    /**
     * Whether [light] lets a sapling grow and a tree heal: about what a polar sun gives, 14 of 15 in the
     * open (design §7.1.2). Out of it nothing advances, and nothing dies of it either.
     */
    fun isLitToGrow(light: Int): Boolean = light in GROWS_IN_LIGHT_FROM..GROWS_IN_LIGHT_UP_TO

    /** Strain after one tick in [band]: out of it, a step further its way; in it, eased back toward none. */
    fun strained(strain: Int, band: Band): Int = when (band) {
        Band.SUITS -> if (abs(strain) <= RECOVERY) 0 else strain - RECOVERY * Integer.signum(strain)
        Band.SERE -> (minOf(strain, 0) - 1).coerceAtLeast(-MOST_STRAIN)
        Band.DROWNED -> (maxOf(strain, 0) + 1).coerceAtMost(MOST_STRAIN)
    }

    /**
     * What [strain] has done to the tree. The root is last and only once there is no living log left to
     * lose, so a tree dying from the top down loses its wood before the thing that could grow it again.
     */
    fun stageOf(strain: Int): Stage {
        val howFar = abs(strain)
        return when {
            howFar >= ROOT_DIES_AT -> Stage.ROOT_DYING
            howFar >= LOGS_DIE_AT -> Stage.LOGS_DYING
            howFar >= LEAVES_FALL_AT -> Stage.LEAVES_FALLEN
            howFar >= ALL_LEAVES_AT -> Stage.ALL_LEAVES_TURNING
            howFar >= OUTER_LEAVES_AT -> Stage.OUTER_LEAVES_TURNING
            else -> Stage.HEALTHY
        }
    }

    /**
     * A leaf's colour at [stage], given its [distance] from a log and whether the tree is drowning: **brown
     * when sere, yellow going to black when drowned**, the outermost first.
     */
    fun blightOf(stage: Stage, distance: Int, drowning: Boolean): PaperTreeLeavesBlock.Blight {
        val isOutermost = distance >= OUTERMOST
        val turned = if (drowning) PaperTreeLeavesBlock.Blight.YELLOWING else PaperTreeLeavesBlock.Blight.BROWNING
        val gone = if (drowning) PaperTreeLeavesBlock.Blight.BLACKENED else PaperTreeLeavesBlock.Blight.BROWNING
        return when (stage) {
            Stage.HEALTHY -> PaperTreeLeavesBlock.Blight.HEALTHY
            Stage.OUTER_LEAVES_TURNING -> if (isOutermost) turned else PaperTreeLeavesBlock.Blight.HEALTHY
            else -> if (isOutermost) gone else turned
        }
    }

    private const val DRIEST = 0
    private const val WETTEST = 15

    /**
     * The band, in the middle of sixteen: under a fifth wet is sere and over four fifths drowned. A third and
     * two thirds sit well inside at every phase of a tide, and a root always wet leaves the band a quarter of
     * an hour after it starts. For playtest to tune.
     */
    private const val HEALTHY_FROM = 3
    private const val HEALTHY_TO = 12

    /** How much strain a tick back in its band takes off: recovery is quicker than decline. */
    private const val RECOVERY = 2

    private const val OUTER_LEAVES_AT = 2
    private const val ALL_LEAVES_AT = 4
    private const val LEAVES_FALL_AT = 6
    private const val LOGS_DIE_AT = 8
    private const val ROOT_DIES_AT = 12
    private const val MOST_STRAIN = 16

    /** A leaf this far from a log is on the outside of its terrace. */
    private const val OUTERMOST = 3

    /** The golden ratio's share of a turn, a turn being 2^32 — the stride [isTimeToLook] walks the minute by. */
    private const val GOLDEN_STRIDE = 0x9E3779B9L
    private const val WHOLE_TURN = 0xFFFFFFFFL
}
