package co.voik.agesandtheart.age.phenomena

import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * The shapes a cave-in gives way in — that each is itself, that none is a template, and that none of them
 * can lose ground to the sweep's own early-out.
 *
 * **Offline, and that is the point of how [Swathe] is split.** A swathe is a pure function of a seed and a
 * reach with no level in it anywhere, so what a collapse is about to take can be read off without a server,
 * and the two properties that would be invisible in play — the contract and the bounds below — can be
 * asserted rather than walked.
 */
class CaveInShapeCheck : FunSpec({

    /**
     * **The contract the sweep rests on** (`CaveIn.crackWhatIsExposed`): a column below nought is skipped
     * whole, without a single course being tested. A shape whose [Swathe.takes] reached past its own plan
     * would silently lose whatever the sweep skipped, and the hole it left would look like a shape nobody
     * drew rather than like a bug.
     */
    test("no shape takes a block in a column its plan says is outside it") {
        forEveryShape { shape, seed, swathe ->
            val reached = mutableListOf<String>()
            for (awayX in -swathe.reachesOut..swathe.reachesOut) {
                for (awayZ in -swathe.reachesOut..swathe.reachesOut) {
                    val howCentral = swathe.howCentral(awayX, awayZ)
                    if (howCentral >= OUTSIDE_IT) continue
                    for (awayY in -swathe.reachesDown..swathe.reachesUp) {
                        if (swathe.takes(awayX, awayY, awayZ, howCentral)) {
                            reached += "($awayX, $awayY, $awayZ) at $howCentral"
                        }
                    }
                }
            }
            check(reached.isEmpty()) {
                "${shape.key} on seed $seed takes ${reached.size} blocks the sweep would never offer it, " +
                    "first ${reached.first()}"
            }
        }
    }

    /**
     * **Out and down, the bounds are the sweep's own loop**, so a shape that meant to reach past them is
     * not clipped — it is never asked, and quietly comes out as a different shape from the one drawn.
     *
     * **Up is not a bound and is not checked**, which is the one asymmetry here: at and above where a
     * collapse began every shape takes its whole plan, so there is no height at which a shape stops wanting
     * ground. `reachesUp` is where the sweep stops asking — how much of the slope over a hillside cave-in
     * goes — rather than where the shape ends.
     */
    test("no shape wants ground past the reach or the depth it declares") {
        forEveryShape { shape, seed, swathe ->
            val wanted = mutableListOf<String>()
            val out = swathe.reachesOut + A_MARGIN
            val down = swathe.reachesDown + A_MARGIN
            for (awayX in -out..out) {
                for (awayZ in -out..out) {
                    val howCentral = swathe.howCentral(awayX, awayZ)
                    if (howCentral < OUTSIDE_IT) continue
                    val pastTheReach = maxOf(abs(awayX), abs(awayZ)) > swathe.reachesOut
                    for (awayY in -down..AT_THE_TOP) {
                        if (!pastTheReach && awayY >= -swathe.reachesDown) continue
                        if (swathe.takes(awayX, awayY, awayZ, howCentral)) wanted += "($awayX, $awayY, $awayZ)"
                        if (wanted.size >= ENOUGH_TO_SEE) break
                    }
                }
            }
            check(wanted.size == NONE_OF_THEM) {
                "${shape.key} on seed $seed wanted ground past its own bounds " +
                    "(out ${swathe.reachesOut}, down ${swathe.reachesDown}), at ${wanted.firstOrNull()}"
            }
        }
    }

    /** A shape that takes nothing is a cave-in that cracks the ground and then thinks better of it. */
    test("every shape takes ground worth taking") {
        forEveryShape { shape, seed, swathe ->
            val taken = blocksTakenBy(swathe)
            check(taken.size >= WORTH_TAKING) {
                "${shape.key} on seed $seed took ${taken.size} blocks, which is not a collapse"
            }
        }
    }

    /**
     * **No shape is a stamp.** Two collapses of a kind share the rule and nothing else — dimensions,
     * wander and roughening all come off the seed — so the same shape twice must not lay the same ground
     * twice, nor even the same amount of it.
     *
     * The bowl varies least, on purpose: a bowl is a bowl, and what is drawn of one is how wide and how
     * deep rather than anything about its form. So this asks that no two are *identical* rather than that
     * any two are unalike, which is the property that actually matters and the only one the square can
     * honour at all.
     */
    test("two collapses of a kind are not the same collapse") {
        for (shape in CollapseShape.entries) {
            val plans = A_FEW_SEEDS.map { blocksTakenBy(Swathe.of(shape, it, ORDINARY_REACH)).toSet() }
            check(plans.distinct().size == plans.size) {
                "two ${shape.key}s on different seeds laid exactly the same ground"
            }
            check(plans.map { it.size }.distinct().size > ONE_ANSWER) {
                "every ${shape.key} took ${plans.first().size} blocks, whatever its seed"
            }
        }
    }

    /**
     * **The one shape with no roughening of any kind**, which is the whole of what it is for: everything
     * else is built to look like it happened and this is built to look like something did it.
     */
    test("a square is exactly square, and its floor is level") {
        val swathe = Swathe.of(CollapseShape.SQUARE, A_SEED, ORDINARY_REACH)
        val floor = blocksTakenBy(swathe).filter { (_, awayY, _) -> awayY == -swathe.reachesDown }
        val acrossX = floor.map { it.first }
        val acrossZ = floor.map { it.third }
        check(acrossX.min() == acrossZ.min() && acrossX.max() == acrossZ.max()) {
            "the square's floor ran ${acrossX.min()}..${acrossX.max()} by ${acrossZ.min()}..${acrossZ.max()}"
        }
        val side = acrossX.max() - acrossX.min() + ONE_BLOCK
        check(floor.size == side * side) {
            "the square's floor had ${floor.size} blocks where a $side by $side needs ${side * side}"
        }
    }

    /** The dial says how big, so a swathe drawn at twice the reach has to take materially more ground. */
    test("reach is what says how big") {
        for (shape in CollapseShape.entries) {
            val ordinary = blocksTakenBy(Swathe.of(shape, A_SEED, ORDINARY_REACH)).size
            val furious = blocksTakenBy(Swathe.of(shape, A_SEED, ORDINARY_REACH * 2)).size
            check(furious > ordinary * MEANINGFULLY_MORE) {
                "${shape.key} at twice the reach took $furious blocks against $ordinary"
            }
        }
    }

    /**
     * The draw has to be a pure function of the seed, or a cave-in reloaded mid-collapse would go on
     * cutting a different shape from the one it started — which is why the weights are read in the enum's
     * own order rather than the map's.
     */
    test("which shape a seed draws is the same answer every time") {
        val behaviour = TectonicsBehaviour.ORDINARY
        for (seed in MANY_SEEDS) {
            check(behaviour.shapeDrawnFrom(seed) == behaviour.shapeDrawnFrom(seed)) {
                "seed $seed drew two different shapes"
            }
        }
    }

    /** Every shape happens, and the rare one is genuinely rare. */
    test("the shipped weights put a square at about one collapse in a hundred") {
        val behaviour = TectonicsBehaviour.ORDINARY
        val drawn = MANY_SEEDS.map(behaviour::shapeDrawnFrom)
        check(drawn.toSet() == CollapseShape.entries.toSet()) {
            "the shipped weights never drew ${CollapseShape.entries.toSet() - drawn.toSet()}"
        }
        val squares = drawn.count { it == CollapseShape.SQUARE }.toDouble() / drawn.size
        check(squares > NONE && squares < AT_MOST_RARE) {
            "a square came up ${(squares * A_PERCENT).toInt() / TO_ONE_PLACE}% of the time"
        }
    }

    /** A shape left out of the weights is a shape a pack has turned off. */
    test("a shape weighted at nothing never comes up") {
        val onlyBolts = TectonicsBehaviour.ORDINARY.copy(shapes = mapOf(CollapseShape.BOLT to ONE_ANSWER))
        check(MANY_SEEDS.all { onlyBolts.shapeDrawnFrom(it) == CollapseShape.BOLT }) {
            "a weighting of one shape drew another"
        }
    }

    /** Both dials run from the walked numbers to the furious ones, and neither runs backwards. */
    test("instability only ever makes a collapse bigger and quicker") {
        val behaviour = TectonicsBehaviour.ORDINARY
        val reaches = EVERY_FURY.map(behaviour::reachAt)
        val paces = EVERY_FURY.map(behaviour::paceAt)
        check(reaches == reaches.sorted()) { "reach did not grow with the tearing: $reaches" }
        check(paces == paces.sortedDescending()) { "the fall did not quicken with the tearing: $paces" }
        check(reaches.first() == behaviour.reach) { "a coherent Age got an unwalked reach: ${reaches.first()}" }
        check(reaches.last() == behaviour.fury.reach) { "the worst Age fell short: ${reaches.last()}" }
    }
}) {
    private companion object {
        const val OUTSIDE_IT = 0.0
        const val ORDINARY_REACH = 24
        const val ONE_BLOCK = 1
        const val ONE_ANSWER = 1

        /** Far enough past a shape's own bounds to catch one that meant to keep going. */
        const val A_MARGIN = 8
        const val AT_THE_TOP = 0
        const val NONE_OF_THEM = 0

        /** Naming a couple is the diagnosis; the whole list is only noise in a failure. */
        const val ENOUGH_TO_SEE = 4

        /** Fewer than this is a crack in the ground rather than a way into it. */
        const val WORTH_TAKING = 200

        /** Twice the reach is eight times the ground, so this is a long way below what it should manage. */
        const val MEANINGFULLY_MORE = 2

        const val NONE = 0.0
        const val AT_MOST_RARE = 0.03
        const val A_PERCENT = 1000.0
        const val TO_ONE_PLACE = 10.0

        const val A_SEED = 0x51D3L
        const val ANOTHER_SEED = 0x7EA5L

        val A_FEW_SEEDS = listOf(A_SEED, ANOTHER_SEED, 0L, -1L, 1234567L)
        val MANY_SEEDS = (1L..4000L).map { it * 2654435761L + 17L }
        val EVERY_FURY = listOf(0.0, 0.25, 0.5, 0.75, 1.0)

        /** Every block one swathe would take, as offsets from where the collapse began. */
        fun blocksTakenBy(swathe: Swathe): List<Triple<Int, Int, Int>> = buildList {
            for (awayX in -swathe.reachesOut..swathe.reachesOut) {
                for (awayZ in -swathe.reachesOut..swathe.reachesOut) {
                    val howCentral = swathe.howCentral(awayX, awayZ)
                    if (howCentral < OUTSIDE_IT) continue
                    for (awayY in -swathe.reachesDown..swathe.reachesUp) {
                        if (swathe.takes(awayX, awayY, awayZ, howCentral)) add(Triple(awayX, awayY, awayZ))
                    }
                }
            }
        }

        fun forEveryShape(judge: (CollapseShape, Long, Swathe) -> Unit) {
            for (shape in CollapseShape.entries) {
                for (seed in A_FEW_SEEDS) judge(shape, seed, Swathe.of(shape, seed, ORDINARY_REACH))
            }
        }
    }
}
