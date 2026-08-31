package co.voik.agesandtheart.age.phenomena

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.checkAll
import net.minecraft.util.RandomSource
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.math.abs

/**
 * The arithmetic a sandfall is made of — the ramp, the derived deposit rate, the turn and the spill.
 *
 * **All of it offline, which is the point of it living on [SandfallBehaviour] rather than on the entity.**
 * A column is an entity and needs a world to walk in; how wide it stands and how much sand it leaves are
 * pure functions of numbers, and those are the parts that can be wrong in a way nobody would see.
 */
class SandfallCheck : FunSpec({

    val ordinary = ColumnBehaviour.ORDINARY

    test("the shipped sandfall reads, and says what the defaults say") {
        val shipped = Path.of("src/main/resources/data/agesandtheart/art/phenomenon/sandfall.json")
        val read = SandfallBehaviour.CODEC
            .parse(JsonOps.INSTANCE, JsonParser.parseString(shipped.readText()))
            .getOrThrow { problem -> IllegalStateException("the shipped sandfall would not read: $problem") }
        check(read == SandfallBehaviour.ORDINARY) {
            "the file and the defaults disagree, so one of them is not what was walked: $read"
        }
    }

    test("a file that says one thing leaves the rest alone") {
        val partial = SandfallBehaviour.CODEC
            .parse(JsonOps.INSTANCE, JsonParser.parseString("""{"column": {"depth": 12.0}, "at_most": 4}"""))
            .getOrThrow { problem -> IllegalStateException("a partial file would not read: $problem") }
        check(partial.column.depth == 12.0) { "the one field written did not land: $partial" }
        check(partial.atMost == 4) { "the one outer field written did not land: $partial" }
        check(partial.column.halfWidth == ordinary.halfWidth) { "writing one field moved another: $partial" }
        check(partial.betweenSpawns == SandfallBehaviour.ORDINARY.betweenSpawns) {
            "writing one field moved another: $partial"
        }
    }

    /** A file with no `column` at all is still a sandfall, and an unwritten one is the ordinary column. */
    test("a file that says nothing about a column still reads") {
        val bare = SandfallBehaviour.CODEC
            .parse(JsonOps.INSTANCE, JsonParser.parseString("""{"at_most": 3}"""))
            .getOrThrow { problem -> IllegalStateException("a bare file would not read: $problem") }
        check(bare.column == ColumnBehaviour.ORDINARY) { "an unwritten column was not the ordinary one: $bare" }
    }

    /**
     * **The column opens from nothing and closes to nothing**, which is the whole of the spawn and death
     * animations. A ramp that did not reach zero would leave a stub of sand hanging in the air at the end.
     */
    test("a column is closed at both ends of its life and open in the middle") {
        val lifetime = 2400
        check(ordinary.halfWidthAt(0, lifetime) == 0.0) { "a column was already open when it arrived" }
        check(ordinary.halfWidthAt(lifetime, lifetime) == 0.0) { "a column was still open when it died" }
        check(ordinary.halfWidthAt(lifetime / 2, lifetime) == ordinary.halfWidth) {
            "a column did not reach its full width halfway through its life"
        }
    }

    /** The brief's "the starting animation basically occurs in reverse" — the same shape both ways. */
    test("it closes the way it opened") {
        val lifetime = 2400
        for (into in 1..lifetime / 2) {
            val opening = ordinary.halfWidthAt(into, lifetime)
            val closing = ordinary.halfWidthAt(lifetime - into, lifetime)
            check(abs(opening - closing) < A_HAIR) {
                "at $into ticks in it was $opening wide, and $into ticks from the end it was $closing"
            }
        }
    }

    test("it only ever widens on the way in and narrows on the way out") {
        val lifetime = 1800
        val widths = (0..lifetime).map { ordinary.halfWidthAt(it, lifetime) }
        val widest = widths.indexOf(widths.max())
        check(widths.take(widest).zipWithNext().all { (earlier, later) -> later >= earlier }) {
            "a column narrowed while it was still opening"
        }
        check(widths.drop(widest).zipWithNext().all { (earlier, later) -> later <= earlier }) {
            "a column widened after it had begun to close"
        }
    }

    /**
     * **The ramp is a share of the life, not a count of ticks**, so a column stood up for ten seconds and one
     * that walks for five minutes are the same width at the same fraction of the way through. That is what
     * lets one dial say what two would have, and it is what makes a debug column worth looking at.
     */
    test("two columns of different lifetimes are alike at the same point in their lives") {
        val brief = 200
        val long = 7200
        for (tenth in 1..9) {
            val early = ordinary.halfWidthAt(brief * tenth / 10, brief)
            val late = ordinary.halfWidthAt(long * tenth / 10, long)
            check(abs(early - late) < A_HAIR) {
                "a tenth of $tenth into its life one column was $early wide and the other $late"
            }
        }
    }

    /**
     * **The one degenerate case, and the min is what handles it**: a pack writing a ramp longer than half a
     * life has the two ramps overlap, and the column should then never quite open rather than snapping to
     * full width and back.
     */
    test("a ramp longer than half a life leaves a column that never fully opens") {
        val overlapping = ColumnBehaviour(rampShare = 0.9)
        val lifetime = 1200
        val widest = (0..lifetime).maxOf { overlapping.halfWidthAt(it, lifetime) }
        check(widest < overlapping.halfWidth) { "a column with overlapping ramps still opened fully" }
        check(widest > 0.0) { "a column with overlapping ramps never opened at all" }
    }

    /**
     * **The rate is derived so that a pass leaves what the pack asked for, at any speed** — the one claim
     * the whole deposit rule rests on. A patch stands under the footprint for `2·halfWidth / speed` ticks,
     * so the chance times the ticks must be the depth.
     */
    test("one pass leaves the depth the pack asked for, however fast the column walks") {
        checkAll(
            Arb.numericDouble(0.005, 0.5),
            Arb.numericDouble(0.5, 40.0),
            Arb.numericDouble(1.0, 8.0),
        ) { speed, depth, halfWidth ->
            val behaviour = ColumnBehaviour(depth = depth, halfWidth = halfWidth)
            val chance = behaviour.depositChanceFor(speed)
            val ticksUnderIt = halfWidth * 2 / speed
            val left = chance * ticksUnderIt
            // Only where the chance did not have to be clamped: past certainty a pass cannot leave more,
            // which is the rule working rather than failing.
            if (chance < 1.0) {
                check(abs(left - depth) < A_HAIR) {
                    "at $speed a pass over a $halfWidth-wide column left $left where $depth was asked for"
                }
            }
        }
    }

    /**
     * **A pack writing nonsense gets a probability anyway.** Every other dial is read from a file and a
     * negative depth or an enormous one should give a column that deposits nothing or deposits always,
     * rather than a chance that is not a number.
     */
    test("a chance is a probability, however extreme the dials") {
        checkAll(Arb.numericDouble(0.001, 4.0), Arb.numericDouble(-50.0, 200.0)) { speed, depth ->
            val chance = ColumnBehaviour(depth = depth).depositChanceFor(speed)
            check(chance in 0.0..1.0) { "a deposit chance of $chance is not a probability" }
        }
    }

    test("spill falls off with every block past the edge, and never rises") {
        val speed = 0.05
        val onIt = ordinary.spillChanceFor(speed, 0)
        check(onIt == ordinary.depositChanceFor(speed)) { "the edge itself was not the full chance" }
        val out = (0..ordinary.spillReach).map { ordinary.spillChanceFor(speed, it) }
        check(out.zipWithNext().all { (nearer, further) -> further < nearer }) {
            "spill did not fall off with distance: $out"
        }
    }

    /**
     * The brief's cap, and it is a cap rather than the thing that makes a column read as straight — that is
     * [SandfallBehaviour.turnEvery]. The draw is two uniforms subtracted, so small turns are much likelier
     * than large ones and the extremes are reached about never.
     */
    test("a turn never exceeds what the pack allows, and is usually small") {
        val random = RandomSource.create(SETTLED_SEED)
        val turns = List(TURNS_DRAWN) { ordinary.turnedBy(random) }
        check(turns.all { abs(it) <= ordinary.turnMost }) {
            "a turn of ${turns.maxOf(::abs)} exceeded the ${ordinary.turnMost} allowed"
        }
        val gentle = turns.count { abs(it) < ordinary.turnMost / 2 }
        check(gentle > turns.size * MOSTLY_GENTLE) {
            "only $gentle of ${turns.size} turns were gentle, so a column would not read as walking straight"
        }
    }

    /**
     * Every range the spawner draws from must be non-empty, because `nextInt` on an empty one throws rather
     * than returning the single value — which would be a crash on the tick loop for a one-character typo.
     */
    test("every range a column is drawn from has room in it") {
        val sending = SandfallBehaviour.ORDINARY
        check(ordinary.shortestLife < ordinary.longestLife) { "a life cannot be drawn from an empty range" }
        check(ordinary.slowestSpeed < ordinary.fastestSpeed) { "a speed cannot be drawn from an empty range" }
        check(sending.nearestSpawn < sending.furthestSpawn) { "a distance cannot be drawn from an empty range" }
    }
}) {
    private companion object {
        const val A_HAIR = 1e-9
        const val SETTLED_SEED = 20260831L
        const val TURNS_DRAWN = 20000
        const val MOSTLY_GENTLE = 0.7
    }
}
