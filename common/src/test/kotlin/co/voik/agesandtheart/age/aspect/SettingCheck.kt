package co.voik.agesandtheart.age.aspect

import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * What a word may ask of a ranged axis, and which asks can fail (design §3.3).
 *
 * The distinction these hold is the one that decides where instability comes from: a **demand** can be
 * refused, a **limit** yields to anything already inside it, and a **nudge** cannot fail at all. Getting
 * that wrong in either direction is expensive — every word competing means two that merely lean the same
 * way fracture a world, and nothing competing means a world can be temperate and scorching at once.
 */
class SettingCheck : FunSpec({

    fun settled(vararg spelled: String): Span? =
        Setting.settle(spelled.map { Setting.read(it) ?: error("'$it' is not a setting") })

    // Bands are arithmetic on doubles, so ends compare to a hair rather than to the bit. Checking these
    // exactly is checking the order the additions happened in.
    infix fun Span?.matches(other: Span): Boolean =
        this != null && abs(least - other.least) < A_HAIR && abs(most - other.most) < A_HAIR

    test("every form reads back as what it is") {
        check(Setting.read("-0.25..0.45") is Setting.Fixed) { "a band is a demand" }
        check(Setting.read(">0.4") == Setting.Bound(least = 0.4)) { "a floor is a limit" }
        check(Setting.read("<0.2") == Setting.Bound(most = 0.2)) { "a ceiling is a limit" }
        check(Setting.read("+0.3") == Setting.Shift(0.3)) { "a rise is a nudge" }
        check(Setting.read("-0.3") == Setting.Shift(-0.3)) { "a fall is a nudge" }
        check(Setting.read("~0.2") == Setting.Spread(0.2)) { "a spread is a spread" }
        check(Setting.read("nonsense") == null) { "nonsense is nothing" }
    }

    /**
     * **A bare negative number is a band, not a nudge**, and the two are one character apart. `-0.25..0.45`
     * has to survive being read by the rule that makes `-0.3` a fall.
     */
    test("a band starting below zero is still a band") {
        check(Setting.read("-0.25..0.45") == Setting.Fixed(Span(-0.25, 0.45))) { "a negative band became a nudge" }
        check(Setting.read("-1.0..-0.7") == Setting.Fixed(Span(-1.0, -0.7))) { "a wholly negative band was lost" }
    }

    /**
     * **Two demands that cannot both be met are a contradiction** — this is the walked example, and the one
     * instability exists to price: `temperate` is `-0.25..0.45` and `scorching` is `0.55..1.0`.
     */
    test("demands that do not overlap fail, and ones that do intersect") {
        check(settled("-0.25..0.45", "0.55..1.0") == null) { "temperate and scorching agreed" }
        val shared = settled("-0.25..0.45", "0.2..0.9")
        check(shared matches Span(0.2, 0.45)) { "two overlapping demands did not intersect: $shared" }
    }

    /** **A limit yields to anything already inside it**, which is what makes `warm scorching` an ordinary phrase. */
    test("a floor a demand already clears costs nothing") {
        val warmScorching = settled("0.55..1.0", ">0.3")
        check(warmScorching matches Span(0.55, 1.0)) { "a floor moved a band that already cleared it: $warmScorching" }
        val warmTemperate = settled("-0.25..0.45", ">0.3")
        check(warmTemperate matches Span(0.3, 0.45)) { "a floor did not clip the band it cut into: $warmTemperate" }
        check(settled("-1.0..-0.5", ">0.3") == null) { "a floor above the whole band should fail" }
    }

    /** **Nudges compose rather than compete**, and never fail however many there are. */
    test("shifts sum and cannot contradict") {
        val leaning = settled("0.0..0.4", "+0.3", "-0.1")
        check(leaning matches Span(0.2, 0.6)) { "two shifts did not sum: $leaning" }
        check(settled("+0.9", "-0.9") != null) { "shifts alone can never fail" }
    }

    /**
     * A band shoved past the end of the axis **slides** rather than collapsing: a hard nudge means "as far
     * that way as this world goes", not a band of one point pinned to the top.
     */
    test("a shift off the end keeps the band's width") {
        val shoved = settled("0.0..0.4", "+5.0")
        check(shoved matches Span(0.6, 1.0)) { "a band pushed off the top lost its width: $shoved" }
        val dropped = settled("0.0..0.4", "-5.0")
        check(dropped matches Span(-1.0, -0.6)) { "a band pushed off the bottom lost its width: $dropped" }
    }

    /**
     * **A nudge with nothing to nudge leans the axis instead**, which is the difference between `sultry`
     * meaning something and meaning nothing.
     *
     * Sliding keeps a band's width, and a band nothing narrowed is already the whole axis — so there was
     * nowhere for it to go and every lone shift was quietly dropped. `sultry` is two nudges and nothing
     * else, and it resolved to the full range on both of its axes.
     */
    test("a shift with no band to move leans the whole axis") {
        val warmer = settled("+0.25")
        check(warmer matches Span(-0.75, 1.0)) { "a lone nudge did not lean the axis: $warmer" }
        val cooler = settled("-0.3")
        check(cooler matches Span(-1.0, 0.7)) { "a lone fall did not lean the axis: $cooler" }
    }

    /** And a width somebody *did* ask for still slides, so the rule above only reaches what nobody claimed. */
    test("a shift beside a bound still slides") {
        val bounded = settled(">0.6", "+5.0")
        check(bounded matches Span(0.6, 1.0)) { "a bounded band did not keep its width: $bounded" }
    }

    /** Spread says how sure a word is, about the band's own middle. */
    test("a spread widens and narrows around the middle") {
        val wider = settled("0.2..0.4", "~0.1")
        check(wider matches Span(0.1, 0.5)) { "a spread did not widen: $wider" }
        val tighter = settled("0.0..0.6", "~-0.2")
        check(tighter matches Span(0.2, 0.4)) { "a spread did not narrow: $tighter" }
        check((settled("0.2..0.4", "~-9.0")?.width ?: 1.0) < A_HAIR) { "narrowing past nothing should pin, not invert" }
    }

    /**
     * **Two words agreeing about an axis leave what they share**, and folding that over a group can never
     * empty: these are intervals on a line, so pairwise overlap guarantees a common point, and a group is
     * built by requiring every member to agree with every other.
     */
    test("agreeing bands narrow, and a pairwise-agreeing set always leaves something") {
        check(Span(-0.25, 0.45).narrowedTo(Span(0.0, 0.6)) matches Span(0.0, 0.45)) { "bands did not narrow" }
        // Pairwise overlapping, no two of them the same, and the fold still lands somewhere.
        val agreeing = listOf(Span(-1.0, 0.2), Span(-0.5, 0.6), Span(0.0, 1.0), Span(-0.3, 0.9))
        for (one in agreeing) for (other in agreeing) {
            check(one.overlaps(other)) { "the fixture is not pairwise overlapping: $one against $other" }
        }
        val shared = agreeing.reduce { held, next -> held.narrowedTo(next) }
        check(shared.least <= shared.most) { "a pairwise-agreeing set folded to nothing: $shared" }
        check(shared matches Span(0.0, 0.2)) { "the common stretch is wrong: $shared" }
    }

    /** Nothing asked leaves the axis as it was, which is what an Age nobody spoke to about it gets. */
    test("asking nothing changes nothing") {
        check(Setting.settle(emptyList()) matches Span.NATURAL) { "an unasked axis moved" }
    }
})

/** Closer than any authored band cares about, and wider than the arithmetic drifts. */
private const val A_HAIR = 1e-9
