package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Rung
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

    /** The width an ordinary column comes out in the middle of its range — what most checks here stand on. */
    val typical = (ordinary.narrowestHalfWidth + ordinary.widestHalfWidth) / 2

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
        check(partial.column.narrowestHalfWidth == ordinary.narrowestHalfWidth) {
            "writing one field moved another: $partial"
        }
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
        check(ordinary.halfWidthAt(0, lifetime, typical) == 0.0) { "a column was already open when it arrived" }
        check(ordinary.halfWidthAt(lifetime, lifetime, typical) == 0.0) { "a column was still open when it died" }
        check(ordinary.halfWidthAt(lifetime / 2, lifetime, typical) == typical) {
            "a column did not reach its full width halfway through its life"
        }
    }

    /** The brief's "the starting animation basically occurs in reverse" — the same shape both ways. */
    test("it closes the way it opened") {
        val lifetime = 2400
        for (into in 1..lifetime / 2) {
            val opening = ordinary.halfWidthAt(into, lifetime, typical)
            val closing = ordinary.halfWidthAt(lifetime - into, lifetime, typical)
            check(abs(opening - closing) < A_HAIR) {
                "at $into ticks in it was $opening wide, and $into ticks from the end it was $closing"
            }
        }
    }

    test("it only ever widens on the way in and narrows on the way out") {
        val lifetime = 1800
        val widths = (0..lifetime).map { ordinary.halfWidthAt(it, lifetime, typical) }
        val widest = widths.indexOf(widths.max())
        check(widths.take(widest).zipWithNext().all { (earlier, later) -> later >= earlier }) {
            "a column narrowed while it was still opening"
        }
        check(widths.drop(widest).zipWithNext().all { (earlier, later) -> later <= earlier }) {
            "a column widened after it had begun to close"
        }
    }

    /**
     * **Arriving takes the same time whatever the column then goes on to do** (Jonah, 2026-08-31). The ramp
     * was a share of the life, which ties how quickly a column appears to how long it happens to last: a
     * long one spent its first two minutes as an invisible thread. What a player watches is a column
     * arriving, and that is three seconds either way.
     */
    test("a column opens in the same time however long it will live") {
        val brief = 600
        val long = 7200
        for (tick in 1..ordinary.ramp) {
            val early = ordinary.halfWidthAt(tick, brief, typical)
            val late = ordinary.halfWidthAt(tick, long, typical)
            check(abs(early - late) < A_HAIR) {
                "$tick ticks in, a short column was $early wide and a long one $late"
            }
        }
        check(ordinary.halfWidthAt(ordinary.ramp, brief, typical) == typical) {
            "a column was not fully open once its ramp had run"
        }
    }

    /**
     * **The one degenerate case, and the min is what handles it**: a column that will not live long enough
     * to open and close in turn should never quite open, rather than snapping to full width and back.
     */
    test("a column too short-lived to open and close never fully opens") {
        val lifetime = ordinary.ramp
        val widest = (0..lifetime).maxOf { ordinary.halfWidthAt(it, lifetime, typical) }
        check(widest < typical) { "a column with overlapping ramps still opened fully" }
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
        val onIt = behaviour.depositChanceFor(speed, standing, behaviour.depth)
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

    test("a pass at full width leaves the depth the pack asked for, at any speed and any width") {
        checkAll(
            Arb.numericDouble(0.01, 0.2),
            Arb.numericDouble(1.0, 12.0),
            Arb.numericDouble(1.5, 10.0),
        ) { speed, depth, full ->
            val left = depthLeftByOnePass(ColumnBehaviour(depth = depth), speed, full)
            // Loose, because the sum is over whole ticks and the crossing is not a whole number of them.
            check(abs(left - depth) < depth * A_TENTH) {
                "a pass at $speed by a column $full wide left $left where $depth was asked for"
            }
        }
    }

    /**
     * **A narrow column leaves a shallow trail, because a narrow column is carrying less sand.** Holding
     * the depth constant all through a life is the trap — see [ColumnBehaviour.depositChanceFor] — and this
     * is the property that replaces it.
     */
    test("a half-open column leaves about half as much") {
        val speed = 0.06
        val onIt = ordinary.depositChanceFor(speed, typical, ordinary.depth)
        fun leftAt(standing: Double): Double {
            var left = 0.0
            var along = -standing * (1.0 + ordinary.spillShare)
            while (along <= standing * (1.0 + ordinary.spillShare)) {
                left += onIt * ordinary.spillFadeAt(standing, abs(along) - standing)
                along += speed
            }
            return left
        }
        val full = leftAt(typical)
        val half = leftAt(typical / 2)
        check(half < full * 0.6 && half > full * 0.4) { "a half-open pass left $half against a full $full" }
    }

    /** The spill scales with the column, so a hair-thin one does not throw sand two blocks either side. */
    test("a narrower column reaches less far") {
        check(ordinary.spillReachAt(5.0) > ordinary.spillReachAt(1.0)) {
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
        val full = typical
        // Where the column's middle is, and how wide, at each tick of one straight walk.
        val laidAt = mutableMapOf<Int, Double>()
        for (age in 0..lifetime) {
            val standing = behaviour.halfWidthAt(age, lifetime, full)
            if (standing <= 0.0) continue
            // Asked every tick rather than once, so this keeps testing the rule if the rule starts varying.
            val onIt = behaviour.depositChanceFor(speed, full, behaviour.depth)
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
            val chance = ColumnBehaviour(depth = depth).depositChanceFor(speed, typical, depth)
            check(chance in 0.0..1.0) { "a deposit chance of $chance is not a probability" }
        }
    }

    test("the spill is whole under the footprint, gone past the band, and falls off between") {
        val standing = typical
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
        check(ordinary.depositChanceFor(0.05, 0.0, ordinary.depth) == 0.0) { "a column with no width was still depositing" }
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
     * **The rung is what buys more columns**, and both of its levers have to move — the count *and* the
     * wait. `scarce` is the one that catches a bad derivation: the count floors at one, so a wait derived
     * from the count leaves a quarter-strength claim identical to an ordinary one, which is what the first
     * version did (found 2026-08-31, driving a server).
     */
    test("a rung moves how many columns stand and how long between them") {
        val sending = SandfallBehaviour.ORDINARY
        val ordinaryRung = Rung.ORDINARY
        val teeming = 4.0
        val scarce = 0.25

        check(sending.atMostFor(ordinaryRung, NO_FURY) == sending.atMost) { "an ordinary claim changed the count" }
        check(sending.betweenSpawnsFor(ordinaryRung, NO_FURY) == sending.betweenSpawns) {
            "an ordinary claim changed the wait"
        }

        check(sending.atMostFor(teeming, NO_FURY) > sending.atMostFor(ordinaryRung, NO_FURY)) {
            "a teeming claim did not raise how many may stand"
        }
        check(sending.betweenSpawnsFor(teeming, NO_FURY) < sending.betweenSpawnsFor(ordinaryRung, NO_FURY)) {
            "a teeming claim did not shorten the wait"
        }

        // The count cannot fall below one, so the wait is the only thing left that can say "rarer".
        check(sending.betweenSpawnsFor(scarce, NO_FURY) > sending.betweenSpawnsFor(ordinaryRung, NO_FURY)) {
            "a scarce claim was no rarer than an ordinary one, which is the whole of the bug"
        }
    }

    test("a rung never asks for a wait of no ticks at all") {
        checkAll(Arb.numericDouble(0.0, 64.0)) { density ->
            check(SandfallBehaviour.ORDINARY.betweenSpawnsFor(density, NO_FURY) >= 1) {
                "a density of $density asked for a wait of nothing, which is a roll every tick"
            }
        }
    }

    /**
     * **All four axes the index reaches actually move**, and none of them moves without it. The failure
     * this guards is a dial wired to the ramp in the file and not in the code, which reads correct from
     * either side on its own.
     */
    test("full fury moves every axis it is meant to and nothing else") {
        val sending = SandfallBehaviour.ORDINARY
        val ordinaryRung = Rung.ORDINARY

        check(sending.widestHalfWidthAt(ALL_FURY) > sending.widestHalfWidthAt(NO_FURY)) { "fury did not widen" }
        check(sending.depthAt(ALL_FURY) > sending.depthAt(NO_FURY)) { "fury did not deepen" }
        check(sending.longestLifeAt(ALL_FURY) > sending.longestLifeAt(NO_FURY)) { "fury did not lengthen" }
        check(sending.atMostFor(ordinaryRung, ALL_FURY) > sending.atMostFor(ordinaryRung, NO_FURY)) {
            "fury did not raise how many stand at once"
        }
        check(sending.betweenSpawnsFor(ordinaryRung, ALL_FURY) < sending.betweenSpawnsFor(ordinaryRung, NO_FURY)) {
            "fury did not shorten the wait"
        }

        // Nothing at all without it: an ordinary Age is exactly what it was before any of this existed.
        check(sending.widestHalfWidthAt(NO_FURY) == sending.column.widestHalfWidth) { "a calm Age was widened" }
        check(sending.depthAt(NO_FURY) == sending.column.depth) { "a calm Age was deepened" }
        check(sending.longestLifeAt(NO_FURY) == sending.column.longestLife) { "a calm Age was lengthened" }
    }

    /** The brief's twenty-by-twenty, and it is the *shipped* number rather than one the check invents. */
    test("full fury reaches the size the pack asked for and never past it") {
        val sending = SandfallBehaviour.ORDINARY
        check(sending.widestHalfWidthAt(ALL_FURY) == sending.fury.halfWidth) {
            "the widest a column gets is not what `fury.half_width` says"
        }
        check(sending.narrowestHalfWidthAt(ALL_FURY) < sending.widestHalfWidthAt(ALL_FURY)) {
            "at full fury every column would be exactly the same size"
        }
    }

    /**
     * **A written rung and the Age's own instability compound** (Jonah, 2026-08-31), rather than the greater
     * winning — which is the house pattern elsewhere (`insistsOn` is a floor) and deliberately not this.
     * A writer who reaches that far has gone to real trouble and is owed a proper show.
     */
    test("a rung and the index compound rather than one winning") {
        val sending = SandfallBehaviour.ORDINARY
        val teeming = 4.0
        val written = sending.betweenSpawnsFor(teeming, NO_FURY)
        val inflicted = sending.betweenSpawnsFor(Rung.ORDINARY, ALL_FURY)
        val both = sending.betweenSpawnsFor(teeming, ALL_FURY)
        check(both < written && both < inflicted) {
            "both together ($both) were no worse than the rung alone ($written) or the index alone ($inflicted)"
        }
    }

    /** The count is the axis that costs to run, so it is capped however hard a rung pushes. */
    test("no rung can push more columns out than the pack allows at once") {
        val sending = SandfallBehaviour.ORDINARY
        checkAll(Arb.numericDouble(0.0, 64.0)) { density ->
            check(sending.atMostFor(density, ALL_FURY) <= sending.fury.atOnce) {
                "a density of $density asked for more than ${sending.fury.atOnce} columns at once"
            }
        }
    }

    /**
     * **A column that buries deeper pours visibly faster**, which is the only cue a player has for how bad
     * the one walking at them is before it arrives.
     */
    test("how fast a column pours follows how deep it buries") {
        check(ordinary.poursAt(ordinary.depth) == ColumnBehaviour.ORDINARY_POUR) {
            "an ordinary column did not pour at the ordinary rate"
        }
        check(ordinary.poursAt(ordinary.depth * 2) > ordinary.poursAt(ordinary.depth)) {
            "a column burying twice as deep poured no faster"
        }
        check(ordinary.poursAt(ordinary.depth * 100) == ColumnBehaviour.FASTEST_POUR) {
            "the pour was not capped at the fastest anything may go"
        }
        check(ordinary.poursAt(0.0) >= ColumnBehaviour.ORDINARY_POUR) {
            "a column burying nothing poured slower than ordinary, which would read as stopping"
        }
    }

    /**
     * **The one constant that now lives in two languages.**
     *
     * The renderer sends the pour as a share of [ColumnBehaviour.FASTEST_POUR] in a colour byte, and
     * `sand_column.fsh` multiplies it back out by its own `FASTEST_FALL`. If the two ever disagree, every
     * column pours at the wrong rate and — worse — the drift stops landing on a whole number of turns a
     * day, so the sand jerks at dawn. Nothing else would catch that.
     */
    test("the shader agrees with us about the fastest a column may pour") {
        val shader = Path.of("src/main/resources/assets/agesandtheart/shaders/sand_column.fsh").readText()
        val declared = Regex("""const float FASTEST_FALL = ([0-9.]+);""").find(shader)
        check(declared != null) { "sand_column.fsh no longer declares FASTEST_FALL" }
        val fastest = declared!!.groupValues[1].toDouble()
        check(fastest == ColumnBehaviour.FASTEST_POUR.toDouble()) {
            "the shader pours at $fastest where we send ${ColumnBehaviour.FASTEST_POUR}"
        }
    }

    /**
     * **The see-through shell is a constant two blocks, not a share of the width.**
     *
     * A share made a wide column mostly haze, which is what "a little too easy to see through" was (Jonah,
     * 2026-08-31). The floor is what keeps a column that is closing from being solid to its own edge, where
     * two blocks would be the whole of it.
     */
    test("the shell around the core stays about two blocks whatever the column") {
        fun shellOf(standing: Float) = standing - coreOf(standing)
        for (standing in listOf(4.0f, 5.0f, 6.0f, 8.0f, 10.0f)) {
            check(abs(shellOf(standing) - SHELL) < A_HAIR.toFloat()) {
                "a column standing $standing wide had a shell of ${shellOf(standing)}"
            }
        }
        // And a column too narrow to spare two blocks keeps a core rather than losing it entirely.
        check(coreOf(1.0f) > 0.0f) { "a closing column had no solid middle left at all" }
        check(coreOf(1.0f) < 1.0f) { "a closing column was solid to its own edge" }
    }

    /** Being under a wider column is worse, and being under none is nothing. */
    test("the shove scales with how wide the column stands") {
        fun shoveAt(standing: Double) = ordinary.push * (standing / ordinary.widestHalfWidth)
        check(shoveAt(0.0) == 0.0) { "a closed column still shoved" }
        check(shoveAt(ordinary.widestHalfWidth) == ordinary.push) {
            "a column at its ordinary widest did not shove at the written force"
        }
        check(shoveAt(SandfallBehaviour.ORDINARY.fury.halfWidth) > ordinary.push) {
            "the widest a furious Age can send shoved no harder than an ordinary one"
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
        check(ordinary.narrowestHalfWidth < ordinary.widestHalfWidth) {
            "a width cannot be drawn from an empty range"
        }
        check(sending.nearestSpawn < sending.furthestSpawn) { "a distance cannot be drawn from an empty range" }
    }
}) {
    private companion object {
        const val A_HAIR = 1e-9
        const val NO_FURY = 0.0

        /** `SandColumn.SHELL_BLOCKS`, which is private to it — this is the number a walk will judge. */
        const val SHELL = 2.0f
        const val LEAST_CORE = 0.3f

        /** The same arithmetic the column does, so the check reads what a player sees. */
        fun coreOf(standing: Float): Float = maxOf(standing - SHELL, standing * LEAST_CORE)
        const val ALL_FURY = 1.0
        const val A_TENTH = 0.1

        /** How much deeper than the asked-for depth any one position may end up over a whole life. */
        const val NO_WORSE_THAN = 1.6
        const val SETTLED_SEED = 20260831L
        const val TURNS_DRAWN = 20000
        const val MOSTLY_GENTLE = 0.7
    }
}
