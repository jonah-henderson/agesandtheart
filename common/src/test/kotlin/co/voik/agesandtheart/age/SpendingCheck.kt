package co.voik.agesandtheart.age

import io.kotest.core.spec.style.FunSpec

/**
 * How an instability budget is spent (design §5.0).
 *
 * The budget model exists to make one guarantee arithmetic rather than hand-guarded: **a small mistake
 * cannot buy a dire manifestation.** §5.2.1 wants the worsening reserved for the severe end and §5.3 wants
 * collapse for the unsalvageable, and under a threshold model each of those is a separate number to tune
 * and a separate place to drift. Here it is subtraction.
 */
class SpendingCheck : FunSpec({

    /**
     * The price list as shipped in `data/agesandtheart/art/manifestation/`, spelled out so a check reads
     * what a server runs. Changing a file there should change this and fail loudly if it does not.
     */
    val SHIPPED = mapOf(
        Manifestation.TORN_SEAMS to Price(costs = 2, most = 4),
        Manifestation.WOUNDS to Price(costs = 5, most = 4),
        Manifestation.SANDFALL to Price(costs = 7, most = 4),
        Manifestation.BLIZZARD to Price(costs = 7, most = 4),
        Manifestation.METEORS to Price(costs = 7, most = 4),
        Manifestation.WORSENING_WOUNDS to Price(costs = 9, most = 3),
        Manifestation.COLLAPSE to Price(costs = 14, most = 3),
    )

    /** What it costs to buy every step of everything — the top of the ladder, whatever is on it. */
    fun everythingCosts(): Int =
        Manifestation.entries.sumOf { SHIPPED.getValue(it).costs * SHIPPED.getValue(it).most }

    /** A stride over the budgets, so the sweep stays quick as the ladder grows. */
    val A_FEW = 3

    val cheap = Manifestation.TORN_SEAMS

    /** Far past any budget a check here hands out, so nothing but the one under test can be afforded. */
    val unaffordable = 1_000_000

    /**
     * A price list where only [cheap] is buyable and everything else is out of reach.
     *
     * **Every manifestation is priced, not just the one under test.** `Spending.of` falls back to the
     * default price for anything a list omits, so a fixture naming one thing quietly let all the others
     * spend the budget — which is exactly what happened the moment a second manifestation existed.
     */
    fun priced(costs: Int, most: Int) = Manifestation.entries.associateWith { manifestation ->
        if (manifestation == cheap) Price(costs = costs, most = most) else Price(costs = unaffordable, most = 0)
    }

    test("a coherent Age buys nothing") {
        val spending = Spending.of(budget = 0, prices = priced(2, 4), seed = 1L)
        check(spending.bought(cheap) == 0) { "a coherent Age bought something: $spending" }
        check(spending.reach(cheap, priced(2, 4)) == 0.0) { "a coherent Age reached somewhere: $spending" }
    }

    test("a budget buys what it can afford and no more") {
        val prices = priced(costs = 2, most = 4)
        check(Spending.of(1, prices, 1L).bought(cheap) == 0) { "1 bought a 2-point step" }
        check(Spending.of(2, prices, 1L).bought(cheap) == 1) { "2 bought no step at all" }
        check(Spending.of(5, prices, 1L).bought(cheap) == 2) { "5 should buy two steps of two" }
    }

    /**
     * **The cap is what makes consequence accumulate rather than escalate.** Without it a runaway index
     * buys an unbounded amount of the cheapest thing and never reaches anything else, so an Age at
     * instability 40 would have enormous seams and nothing wrong with it otherwise.
     */
    test("no manifestation can be bought past its cap") {
        val prices = priced(costs = 2, most = 4)
        val rich = Spending.of(1000, prices, 1L)
        check(rich.bought(cheap) == 4) { "the cap did not hold: $rich" }
        check(rich.reach(cheap, prices) == 1.0) { "a maxed manifestation should read as fully reached" }
        check(rich.unspent > 0) { "a budget past every cap should have a remainder, and it had none" }
    }

    /** Unspent is left unspent, which §5.0 names as the safe default until something wants it. */
    test("what cannot be afforded is left over") {
        val spending = Spending.of(budget = 5, prices = priced(2, 4), seed = 1L)
        check(spending.unspent == 1) { "5 spent on 2-point steps should leave 1, and left ${spending.unspent}" }
    }

    /**
     * **Deterministic**, which the recipe model rests on: an Age rebuilds from its recipe on every open,
     * so an allocation that differed the second time would be a different world under one book.
     */
    test("the same Age spends the same way every time") {
        val prices = priced(3, 3)
        for (budget in 0..20) {
            check(Spending.of(budget, prices, 7L) == Spending.of(budget, prices, 7L)) {
                "the allocation at $budget is not stable"
            }
        }
    }

    /** A price of zero would buy infinitely many steps for nothing, so it is refused rather than looped on. */
    test("a free manifestation is skipped, not bought forever") {
        val free = priced(2, 4) + mapOf(cheap to Price(costs = 0, most = 4))
        val spending = Spending.of(10, free, 1L)
        check(spending.bought(cheap) == 0) { "a zero price was bought anyway: $spending" }
        check(spending.unspent == 10) { "a zero price consumed budget: $spending" }
    }

    /**
     * **What tearing does to the rock**, which is the half a walk will judge and the half arithmetic can
     * settle in advance.
     *
     * Every magnitude moves in the direction its form means: a scarp throws further, a rift cuts deeper, a
     * wall stands higher, a dissolve widens. And the rift has a floor it may never pass — a chasm to
     * bedrock along every seam severs the territories rather than dividing them, which is the whole reason
     * `RIFT_FLOOR` was chosen where it was.
     */
    test("a torn Age's seams are more than a coherent one's") {
        check(Seam.scarpThrow(1.0) > Seam.scarpThrow(Seam.UNTORN)) { "a torn scarp throws no further" }
        check(Seam.riftFloor(1.0) < Seam.riftFloor(Seam.UNTORN)) { "a torn rift cuts no deeper" }
        check(Seam.wallCrest(1.0) > Seam.wallCrest(Seam.UNTORN)) { "a torn wall stands no higher" }
        check(Seam.widestFuzz(1.0) > Seam.widestFuzz(Seam.UNTORN)) { "a torn dissolve is no wider" }

        // The one hard limit, and it holds however torn.
        check(Seam.riftFloor(1.0) >= Seam.RIFT_DEEPEST) { "a rift cut past its floor: ${Seam.riftFloor(1.0)}" }
        check(Seam.riftFloor(99.0) >= Seam.RIFT_DEEPEST) { "a runaway budget cut to bedrock" }

        // And a coherent Age is exactly what it always was, or this changed every Age ever written.
        check(Seam.scarpThrow(Seam.UNTORN) == Seam.SCARP_THROW) { "an untorn scarp moved" }
        check(Seam.riftFloor(Seam.UNTORN) == Seam.RIFT_FLOOR) { "an untorn rift moved" }
        check(Seam.wallCrest(Seam.UNTORN) == Seam.WALL_CREST) { "an untorn wall moved" }
        check(Seam.widestFuzz(Seam.UNTORN) == Seam.WIDEST_FUZZ_BLOCKS) { "an untorn dissolve moved" }
    }

    /** `SHEARED` has no magnitude, and that is the ruling rather than an oversight (Jonah, 2026-08-07). */
    test("a shear is instability already visible, and costs nothing to show") {
        check(Seam.SHEARED.share == 0.0) { "a shear gained a width to widen" }
        check(Seam.SHEARED.blendBlocks(400, torn = 1.0) == 0) {
            "tearing gave a shear a blend band, which is the one form that must not have one"
        }
    }

    /**
     * **What the shipped price list buys across the range a writer will actually see.**
     *
     * The one thing a check can settle about wounds: that a small mistake cannot open one. Seams are
     * cheap and capped, wounds are dearer, so a budget has to fill the first before it reaches the second
     * — which is §5.0's fence working as arithmetic rather than as a guard somebody remembered to write.
     */
    test("a small mistake tears seams and opens no wounds") {
        fun wounds(budget: Int) = Spending.of(budget, SHIPPED, 1L).bought(Manifestation.WOUNDS)
        fun seams(budget: Int) = Spending.of(budget, SHIPPED, 1L).bought(Manifestation.TORN_SEAMS)

        // Everything a beginner can plausibly reach buys tearing and nothing worse.
        for (budget in 0..9) {
            check(wounds(budget) == 0) { "instability $budget opened ${wounds(budget)} wounds" }
        }
        // And the seams are maxed before the first wound is affordable, so consequence accumulates.
        check(seams(9) == 4) { "seams were not filled before wounds were reached: ${seams(9)}" }
        check(wounds(14) >= 1) { "a badly flawed Age opened no wound at all" }
        check(wounds(1000) == 4) { "the wound cap did not hold: ${wounds(1000)}" }
    }

    /**
     * **The goodwill fence, as arithmetic** (design §5.0, §5.2.1).
     *
     * The dire registers are not gated by a guard somebody remembered to write — they are simply
     * unaffordable until everything cheaper has been bought to its cap. This is the check that says so,
     * and it is the one to look at if the price list is ever retuned.
     */
    test("a dearer register is unaffordable until everything cheaper is at its cap") {
        val cheapestFirst = Manifestation.entries
            .sortedWith(compareBy({ SHIPPED.getValue(it).costs }, Manifestation::ordinal))
        // Every budget worth asking about, rather than three that were true when they were written.
        for (budget in 0..everythingCosts() step A_FEW) {
            val spent = Spending.of(budget, SHIPPED, 1L)
            for ((rung, manifestation) in cheapestFirst.withIndex()) {
                if (spent.bought(manifestation) == 0) continue
                for (cheaper in cheapestFirst.take(rung)) {
                    check(spent.bought(cheaper) == SHIPPED.getValue(cheaper).most) {
                        "instability $budget bought ${manifestation.key} with ${cheaper.key} not yet full"
                    }
                }
            }
        }
    }

    /** And that the dearest is reachable at all, or it is dead content whatever the ladder costs. */
    test("everything is reachable by an Age written to come apart") {
        val spent = Spending.of(everythingCosts(), SHIPPED, 1L)
        for (manifestation in Manifestation.entries) {
            check(spent.bought(manifestation) == SHIPPED.getValue(manifestation).most) {
                "${manifestation.key} was unreachable even at the top of the ladder"
            }
        }
    }

    /**
     * **That the map above names every manifestation there is.**
     *
     * Without this, adding one and forgetting to price it here is not caught where the mistake is: the new
     * manifestation silently falls back to `Price.ORDINARY`, which at two a step is *cheaper than anything
     * shipped*, so it eats the budget from the bottom and the failure surfaces as some unrelated register
     * going unbought. That is exactly what adding the sandfall did (2026-08-31).
     */
    test("the shipped list prices every manifestation") {
        val unpriced = Manifestation.entries.filterNot { it in SHIPPED }
        check(unpriced.isEmpty()) {
            "$unpriced would fall back to Price.ORDINARY, which is cheaper than everything shipped"
        }
    }

    /**
     * **What the shipped price list actually does**, so the tuning is visible rather than only tunable.
     * These are the numbers a walk will be judging, and a change to them should be a change here too.
     */
    test("the shipped tearing reads sensibly across the range") {
        val prices = priced(Price.ORDINARY.costs, Price.ORDINARY.most)
        val reaches = listOf(0, 2, 4, 6, 8, 12).map { it to Spending.of(it, prices, 1L).reach(cheap, prices) }
        check(reaches.first().second == 0.0) { "a coherent Age tore: $reaches" }
        check(reaches.last().second == 1.0) { "a badly flawed Age did not tear fully: $reaches" }
        // Monotone, or a writer making an Age *worse* could make it look better.
        check(reaches.map { it.second } == reaches.map { it.second }.sorted()) {
            "tearing is not monotone in the budget: $reaches"
        }
    }
})
