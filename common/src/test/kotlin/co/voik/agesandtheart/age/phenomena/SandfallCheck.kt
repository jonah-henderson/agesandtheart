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
     * **The one claim the whole deposit rule rests on: a pass leaves the depth the pack asked for.**
     *
     * Not the chance in isolation — that was checked before and passed while the rule was badly wrong. What
     * matters is the chance *integrated over the crossing*, spill included, and this marches a column over
     * one patch of ground and sums it. A driven server said fourteen blocks where four and a half were
     * asked for (2026-08-31); this says the same thing offline and in a tenth of a second.
     */
    fun depthLeftByOnePass(behaviour: ColumnBehaviour, speed: Double, standing: Double): Double {
        val onIt = behaviour.depositChanceFor(speed)
        val reach = standing * (1.0 + behaviour.spillShare)
        var left = 0.0
        var along = -reach
        // One term per tick of the crossing, which is what the column does: it steps `speed` and rolls once.
        while (along <= reach) {
            left += onIt * behaviour.spillFadeAt(standing, abs(along) - standing)
            along += speed
        }
        return left
    }

    test("a pass at full width leaves the depth the pack asked for, at any speed") {
        checkAll(Arb.numericDouble(0.01, 0.2), Arb.numericDouble(1.0, 12.0)) { speed, depth ->
            val behaviour = ColumnBehaviour(depth = depth)
            val left = depthLeftByOnePass(behaviour, speed, behaviour.halfWidth)
            // Loose, because the sum is over whole ticks and the crossing is not a whole number of them.
            check(abs(left - depth) < depth * A_TENTH) {
                "a pass at $speed left $left where $depth was asked for"
            }
        }
    }

    /**
     * **A narrow column leaves a shallow trail, because a narrow column is carrying less sand.** Holding
     * the depth constant all through a life is the trap — see [ColumnBehaviour.depositChanceFor] — and this
     * is the property that replaces it.
     */
    test("a half-width column leaves about half as much") {
        val speed = 0.06
        val full = depthLeftByOnePass(ordinary, speed, ordinary.halfWidth)
        val half = depthLeftByOnePass(ordinary, speed, ordinary.halfWidth / 2)
        check(half < full * 0.6 && half > full * 0.4) { "a half-width pass left $half against a full $full" }
    }

    /** The spill scales with the column, so a hair-thin one does not throw sand two blocks either side. */
    test("a narrower column reaches less far") {
        check(ordinary.spillReachAt(2.5) > ordinary.spillReachAt(1.0)) {
            "a narrow column reached as far as a wide one"
        }
        check(ordinary.spillReachAt(0.2) <= 1) { "a hair-thin column was still throwing sand a block out" }
    }

    /**
     * **Nothing may pile up in one place, and this is the check that would have found the tower.**
     *
     * A whole life, simulated: the column widens, walks, and closes, and every tick every position under it
     * is offered a block. The rule as first written scaled the rate by the *current* width, so a closing
     * column saturated at certainty over a footprint that had shrunk to one position — it stopped walking
     * and drilled seventeen blocks of sand straight up. Nothing that checked the chance, the ramp or a
     * single crossing could see it; only walking a life could.
     */
    test("no position is buried far past the depth, over a whole life") {
        val behaviour = ordinary
        val lifetime = 500
        val speed = 0.06
        // Where the column's middle is, and how wide, at each tick of one straight walk.
        val laidAt = mutableMapOf<Int, Double>()
        for (age in 0..lifetime) {
            val standing = behaviour.halfWidthAt(age, lifetime)
            if (standing <= 0.0) continue
            // Asked every tick rather than once, so this keeps testing the rule if the rule starts varying.
            val onIt = behaviour.depositChanceFor(speed)
            val middle = age * speed
            val reach = behaviour.spillReachAt(standing)
            for (position in (middle - reach).toInt()..(middle + reach).toInt() + 1) {
                val past = abs(position + 0.5 - middle) - standing
                val chance = onIt * behaviour.spillFadeAt(standing, past)
                if (chance > 0.0) laidAt[position] = (laidAt[position] ?: 0.0) + chance
            }
        }
        val deepest = laidAt.values.max()
        check(deepest < behaviour.depth * NO_WORSE_THAN) {
            "one position was buried $deepest deep where ${behaviour.depth} was asked for: ${laidAt.toSortedMap()}"
        }
    }

    test("a chance is a probability, however extreme the dials") {
        checkAll(Arb.numericDouble(0.001, 4.0), Arb.numericDouble(-50.0, 200.0)) { speed, depth ->
            val chance = ColumnBehaviour(depth = depth).depositChanceFor(speed)
            check(chance in 0.0..1.0) { "a deposit chance of $chance is not a probability" }
        }
    }

    test("the spill is whole under the footprint, gone past the band, and falls off between") {
        val standing = ordinary.halfWidth
        val band = standing * ordinary.spillShare
        check(ordinary.spillFadeAt(standing, -1.0) == 1.0) { "a position under the footprint was faded" }
        check(ordinary.spillFadeAt(standing, 0.0) == 1.0) { "the edge itself was faded" }
        check(ordinary.spillFadeAt(standing, band) == 0.0) { "the outer limit still deposited" }
        check(ordinary.spillFadeAt(standing, band * 2) == 0.0) { "sand landed past the outer limit" }
        val across = (0..10).map { ordinary.spillFadeAt(standing, band * it / 10.0) }
        check(across.zipWithNext().all { (nearer, further) -> further < nearer }) {
            "the spill did not fall off across the band: $across"
        }
    }

    test("a column of no width at all deposits nothing") {
        check(ColumnBehaviour(halfWidth = 0.0).depositChanceFor(0.05) == 0.0) {
            "a column with no width was still depositing"
        }
        check(ordinary.spillFadeAt(0.0, 1.0) == 0.0) { "a closed column was still spilling" }
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
        const val A_TENTH = 0.1

        /** How much deeper than the asked-for depth any one position may end up over a whole life. */
        const val NO_WORSE_THAN = 1.6
        const val SETTLED_SEED = 20260831L
        const val TURNS_DRAWN = 20000
        const val MOSTLY_GENTLE = 0.7
    }
}
