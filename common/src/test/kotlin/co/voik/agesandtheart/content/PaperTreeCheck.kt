package co.voik.agesandtheart.content

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import co.voik.agesandtheart.content.PaperTreeHealth.Band
import co.voik.agesandtheart.content.PaperTreeLeavesBlock.Blight
import net.minecraft.core.Direction

/**
 * The paper tree's rules and the two things its shape must never get wrong (design §7.1.2).
 *
 * **What the shape looks like is walked, not checked** — only what would break the tree: every log touching
 * the one it grows from, so the regrowth that follows those links can reach it, and every leaf close enough
 * to a log to hold, so nothing a tree grows decays on its own.
 */
class PaperTreeCheck : FunSpec({

    test("the middle of the range is the band, and either end is out of it") {
        check(PaperTreeHealth.bandOf(PaperTreeHealth.SETTLED) == Band.SUITS) { "a new root starts out of band" }
        check(PaperTreeHealth.bandOf(0) == Band.SERE) { "a root that has dried right out is not sere" }
        check(PaperTreeHealth.bandOf(15) == Band.DROWNED) { "a root soaked through is not drowned" }
    }

    test("always wet drowns and always dry dries out") {
        fun settlesAt(isWet: Boolean) =
            (1..PaperTreeHealth.SAMPLES).fold(PaperTreeHealth.SETTLED_HISTORY) { memory, _ ->
                PaperTreeHealth.remembered(memory, isWet)
            }
        val wet = PaperTreeHealth.moistureOf(settlesAt(isWet = true))
        val dry = PaperTreeHealth.moistureOf(settlesAt(isWet = false))
        check(PaperTreeHealth.bandOf(wet) == Band.DROWNED) { "wet a whole tide did not drown" }
        check(PaperTreeHealth.bandOf(dry) == Band.SERE) { "dry a whole tide did not dry out" }
    }

    /**
     * The case the counter model failed (`PaperTreeHealth`): a root at the waterline is wet a third or two
     * thirds of a tide, never exactly half, and it must read inside its band at every phase of it.
     */
    test("a tide that wets the roots a third or two thirds of the time never marks a leaf") {
        for (wetLooks in listOf(THIRD, TWO_THIRDS, PaperTreeHealth.SAMPLES / 2)) {
            var memory = PaperTreeHealth.SETTLED_HISTORY
            var strain = 0
            repeat(PaperTreeHealth.SAMPLES * TIDES) { look ->
                memory = PaperTreeHealth.remembered(memory, isWet = look % PaperTreeHealth.SAMPLES < wetLooks)
                val moisture = PaperTreeHealth.moistureOf(memory)
                strain = PaperTreeHealth.strained(strain, PaperTreeHealth.bandOf(moisture))
                check(PaperTreeHealth.stageOf(strain) == PaperTreeHealth.Stage.HEALTHY) {
                    "wet $wetLooks looks a tide marked the tree at look $look, moisture $moisture, strain $strain"
                }
            }
        }
    }

    test("a heart looks exactly once a minute") {
        for (place in PLACES) {
            val minutes = (0L..<SAMPLE_MINUTES * PaperTreeHealth.SAMPLE_EVERY)
                .filter { PaperTreeHealth.isTimeToLook(it, place) }
                .map { it / PaperTreeHealth.SAMPLE_EVERY }
            check(minutes == (0L..<SAMPLE_MINUTES).toList()) { "a heart at $place looked in minutes $minutes" }
        }
    }

    /**
     * The case a fixed moment failed: dispensers on a clock wetting the roots half of every cycle, where the
     * cycle divides the minute. Looking at one moment of it every time read the same half every time.
     */
    test("dispensers on a fast clock never mark a leaf, whatever its period") {
        val periods = (2..PaperTreeHealth.SAMPLE_EVERY).filter { PaperTreeHealth.SAMPLE_EVERY % it == 0 && it % 2 == 0 }
        for (period in periods) for (place in PLACES) {
            var memory = PaperTreeHealth.SETTLED_HISTORY
            var strain = 0
            for (tick in 0L..<PaperTreeHealth.SAMPLES.toLong() * TIDES * PaperTreeHealth.SAMPLE_EVERY) {
                if (!PaperTreeHealth.isTimeToLook(tick, place)) continue
                memory = PaperTreeHealth.remembered(memory, isWet = tick % period < period / 2)
                strain = PaperTreeHealth.strained(strain, PaperTreeHealth.bandOf(PaperTreeHealth.moistureOf(memory)))
                check(PaperTreeHealth.stageOf(strain) == PaperTreeHealth.Stage.HEALTHY) {
                    "a $period-tick clock marked the tree at $place at tick $tick, strain $strain"
                }
            }
        }
    }

    test("a sapling's moisture hands the heart the same moisture") {
        for (moisture in 0..15) {
            val handed = PaperTreeHealth.moistureOf(PaperTreeHealth.historyFor(moisture))
            val keepsItsBand = PaperTreeHealth.bandOf(handed) == PaperTreeHealth.bandOf(moisture)
            check(keepsItsBand) { "$moisture came out as $handed" }
        }
    }

    test("the tree fails from the outside in, the root last") {
        val strains = (0..20).map { PaperTreeHealth.strained(-it + 1, Band.SERE) }
        val stages = strains.map(PaperTreeHealth::stageOf)
        check(stages == stages.sortedBy { it.ordinal }) { "the stages came out of order: $stages" }
        check(stages.last() == PaperTreeHealth.Stage.ROOT_DYING) { "enough strain never reached the root" }
    }

    test("back in its band, a strained tree recovers") {
        val eased = (1..20).fold(8) { strain, _ -> PaperTreeHealth.strained(strain, Band.SUITS) }
        check(eased == 0) { "strain did not ease back to nothing: $eased" }
    }

    test("the outermost leaves turn first, brown for sere and yellow for drowned") {
        val stage = PaperTreeHealth.Stage.OUTER_LEAVES_TURNING
        check(PaperTreeHealth.blightOf(stage, 1, drowning = false) == Blight.HEALTHY) { "an inner leaf turned" }
        check(PaperTreeHealth.blightOf(stage, 5, drowning = false) == Blight.BROWNING) { "sere is not brown" }
        check(PaperTreeHealth.blightOf(stage, 5, drowning = true) == Blight.YELLOWING) { "drowned is not yellow" }
    }

    test("every log touches the one it grows from") {
        for (seed in SEEDS) {
            val shape = PaperTreeShape.grownFrom(HEART, seed)
            shape.logs.forEachIndexed { index, log ->
                val from = if (log.grownFrom == PaperTreeShape.FROM_THE_ROOT) HEART else shape.logs[log.grownFrom].at
                check(Direction.entries.any { from.relative(it) == log.at }) {
                    "seed $seed: log $index at ${log.at} does not touch ${from} it grows from"
                }
            }
        }
    }

    test("every root is joined to the tree by a face or an edge") {
        fun touchesByAFaceOrAnEdge(one: BlockPos, other: BlockPos): Boolean {
            val apart = listOf(one.x - other.x, one.y - other.y, one.z - other.z).map(Math::abs)
            return apart.all { it <= 1 } && apart.count { it == 1 } in 1..2
        }
        for (seed in SEEDS) {
            val shape = PaperTreeShape.grownFrom(HEART, seed)
            val tree = shape.logs.map { it.at }.toSet() + HEART
            val joined = tree.toMutableSet()
            var grew = true
            while (grew) {
                val reached = shape.roots.filter { root -> root !in joined && joined.any { touchesByAFaceOrAnEdge(root, it) } }
                joined += reached
                grew = reached.isNotEmpty()
            }
            val loose = shape.roots.filterNot { it in joined }
            check(loose.isEmpty()) { "seed $seed: roots at $loose touch the tree only at a corner, or not at all" }
        }
    }

    test("every leaf is close enough to a log to hold, and none is inside one") {
        for (seed in SEEDS) {
            val shape = PaperTreeShape.grownFrom(HEART, seed)
            val logs = shape.logs.map { it.at }.toSet()
            val farthest = shape.leaves.values.max()
            check(shape.leaves.values.all { it in 1..6 }) { "seed $seed: a leaf would decay at $farthest" }
            check(shape.leaves.keys.none { it in logs }) { "seed $seed: a leaf stands in a log" }
            check(shape.roots.none { it in logs || it == HEART }) { "seed $seed: a root stands in the trunk" }
            check(shape.terraces.size in 2..3) { "seed $seed: ${shape.terraces.size} terraces" }
        }
    }
})

private val HEART = BlockPos(0, 64, 0)
private val SEEDS = 0L..<200L

/** A third and two thirds of a tide's looks. */
private const val THIRD = 7
private const val TWO_THIRDS = 13
private const val TIDES = 10

/** Hearts at the origin, in the negatives, and far out — what `BlockPos.asLong` packs differently. */
private val PLACES = listOf(HEART, BlockPos(-3, -40, -7), BlockPos(29_000_000, 300, -29_000_000)).map { it.asLong() }
private const val SAMPLE_MINUTES = 50L
