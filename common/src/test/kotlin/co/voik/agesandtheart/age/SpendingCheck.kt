package co.voik.agesandtheart.age

import io.kotest.core.spec.style.FunSpec

/**
 * How an instability budget is spent (design §5.0).
 *
 * The index is spent a step at a time on steps drawn off the Age's seed, so what a check can hold is not
 * *which* steps an Age buys but the rules every draw obeys: nothing is bought past what the budget affords,
 * past a dial's steps, or before a step's floor opens; the same Age always draws the same way; and the one
 * threshold, collapse, is exact.
 */
class SpendingCheck : FunSpec({

    val cheap = Manifestation.TORN_SEAMS
    val onlyDial = cheap.dials.single()

    /** Far past any budget a check here hands out, so nothing but the one under test can be afforded. */
    val unaffordable = 1_000_000

    /** Enough seeds that a rule holding for all of them is not a property of one draw. */
    val seeds = (0L..<40L).map { it * 7_919L + 13L }

    /**
     * A price list where only [cheap] is buyable and everything else is out of reach.
     *
     * **Every manifestation is priced, not just the one under test.** `Spending.of` falls back to the
     * default price for anything a list omits, so a fixture naming one thing quietly let all the others
     * spend the budget.
     */
    fun priced(costs: Int, most: Int) = Manifestation.entries.associateWith { manifestation ->
        if (manifestation == cheap) Price.flat(manifestation, costs, most)
        else Price.flat(manifestation, unaffordable, 1)
    }

    /** What a whole draw paid, step by step, at [prices]. */
    fun paid(spending: Spending, prices: Map<Manifestation, Price>): Int =
        Manifestation.entries.filter { prices.getValue(it).certainFrom == null }.sumOf { manifestation ->
            manifestation.dials.sumOf { dial ->
                prices.getValue(manifestation).dials.getValue(dial).costs.take(spending.bought(manifestation, dial)).sum()
            }
        }

    test("a coherent Age buys nothing") {
        val spending = Spending.of(budget = 0, seed = 1L, prices = priced(2, 4))
        check(spending.bought(cheap) == 0) { "a coherent Age bought something: $spending" }
        check(spending.reach(cheap) == 0.0) { "a coherent Age reached somewhere: $spending" }
    }

    test("a budget buys what it can afford and no more") {
        val prices = priced(costs = 2, most = 4)
        check(Spending.of(1, 1L, prices).bought(cheap) == 0) { "1 bought a 2-point step" }
        check(Spending.of(2, 1L, prices).bought(cheap) == 1) { "2 bought no step at all" }
        check(Spending.of(5, 1L, prices).bought(cheap) == 2) { "5 should buy two steps of two" }
    }

    /**
     * **The cap is what makes consequence accumulate rather than escalate.** Without it a runaway index
     * buys an unbounded amount of whatever its draw lands on and nothing else.
     */
    test("no dial can be bought past its steps") {
        val rich = Spending.of(1000, 1L, priced(costs = 2, most = 4))
        check(rich.bought(cheap) == 4) { "the cap did not hold: $rich" }
        check(rich.reach(cheap) == 1.0) { "a maxed dial should read as fully reached" }
    }

    /**
     * **Deterministic**, which the recipe model rests on: an Age rebuilds from its recipe on every open,
     * so an allocation that differed the second time would be a different world under one book.
     */
    test("the same Age spends the same way every time") {
        for (seed in seeds) {
            for (budget in 0..120 step 7) {
                check(Spending.of(budget, seed, SHIPPED_PRICES) == Spending.of(budget, seed, SHIPPED_PRICES)) {
                    "the allocation at $budget under seed $seed is not stable"
                }
            }
        }
    }

    /** The point of drawing rather than ordering (Jonah, 2026-09-17). */
    test("the same index shows as different symptoms in different Ages") {
        val shown = seeds.map { Spending.of(60, it, SHIPPED_PRICES) }.toSet()
        check(shown.size > seeds.size / 2) { "forty seeds at index 60 drew only ${shown.size} different Ages" }
    }

    /** A price of zero would buy infinitely many steps for nothing, so it is refused rather than looped on. */
    test("a free step is skipped, not bought forever") {
        val free = priced(2, 4) + mapOf(cheap to Price.flat(cheap, costs = 0, most = 4))
        val spending = Spending.of(10, 1L, free)
        check(spending.bought(cheap) == 0) { "a zero price was bought anyway: $spending" }
    }

    test("a draw never spends more than the index") {
        for (seed in seeds) {
            for (budget in 0..199 step 3) {
                val spent = paid(Spending.of(budget, seed, SHIPPED_PRICES), SHIPPED_PRICES)
                check(spent <= budget) { "index $budget under seed $seed paid $spent" }
            }
        }
    }

    /**
     * **The goodwill fence, as a floor** (design §5.0, §5.2.1). Under a draw a price no longer keeps a
     * verdict back — a nine-point step can be the first one drawn — so each step's `opens_at` does, and no
     * draw may buy a step its index has not reached.
     */
    test("no step is bought before its floor opens") {
        for (seed in seeds) {
            for (budget in 0..199 step 3) {
                val spent = Spending.of(budget, seed, SHIPPED_PRICES)
                for (manifestation in Manifestation.entries) {
                    val price = SHIPPED_PRICES.getValue(manifestation)
                    if (price.certainFrom != null) continue
                    for (dial in manifestation.dials) {
                        val floors = price.dials.getValue(dial).opensAt.take(spent.bought(manifestation, dial))
                        check(floors.all { it <= budget }) {
                            "index $budget under seed $seed bought ${manifestation.key} $dial past a floor: $floors"
                        }
                    }
                }
            }
        }
    }

    test("a small mistake tears seams and opens no wounds") {
        for (seed in seeds) {
            for (budget in 0..9) {
                val wounds = Spending.of(budget, seed, SHIPPED_PRICES).bought(Manifestation.WOUNDS)
                check(wounds == 0) { "instability $budget under seed $seed opened $wounds wounds" }
            }
            check(Spending.of(9, seed, SHIPPED_PRICES).bought(Manifestation.TORN_SEAMS) > 0) {
                "an Age with nothing else open to it tore no seams under seed $seed"
            }
        }
    }

    /** Collapse is not drawn (Jonah, 2026-09-17): the threshold is exact, whatever the seed. */
    test("collapse is a threshold, and what is past it buys its spread") {
        val threshold = requireNotNull(SHIPPED_PRICES.getValue(Manifestation.COLLAPSE).certainFrom)
        val spread = SHIPPED_PRICES.getValue(Manifestation.COLLAPSE).dials.values.single()
        for (seed in seeds) {
            fun collapse(budget: Int) = Spending.of(budget, seed, SHIPPED_PRICES).bought(Manifestation.COLLAPSE)
            check(collapse(threshold - 1) == 0) { "an Age short of the threshold collapsed under seed $seed" }
            check(collapse(threshold) == 1) { "an Age at the threshold did not collapse under seed $seed" }
            check(collapse(threshold + spread.costs[1]) == 2) { "the index past the threshold bought no spread" }
            check(collapse(threshold * 10) == spread.most) { "collapse's spread went past its steps" }
        }
    }

    /** And that everything is reachable at all, or it is dead content whatever the draw does. */
    test("everything is reachable by an Age written to come apart") {
        val spent = Spending.of(unaffordable, seeds.first(), SHIPPED_PRICES)
        for (manifestation in Manifestation.entries) {
            for (dial in manifestation.dials) {
                check(spent.reach(manifestation, dial) == 1.0) {
                    "${manifestation.key} $dial was unreachable even at the top of the index"
                }
            }
        }
    }

    /**
     * **What a deluge-leaning Age gets**, which is the design's reason for the cheap dials: its downpour and
     * rate bought well up while the rest of the index is still modest.
     */
    test("some Age at a moderate index is drowning in earnest") {
        fun drowning(seed: Long): Boolean {
            val spent = Spending.of(60, seed, SHIPPED_PRICES)
            val downpour = spent.reach(Manifestation.DELUGE, Manifestation.DOWNPOUR)
            val rate = spent.reach(Manifestation.DELUGE, Manifestation.RISE_RATE)
            return downpour >= HALF_OR_MORE && rate >= HALF_OR_MORE
        }
        val manySeeds = (0L..<400L).map { it * 104_729L + 3L }
        check(manySeeds.any(::drowning)) { "no Age in four hundred at index 60 had bought half of both cheap dials" }
    }

    /**
     * **That the map below names every manifestation and every dial there is.** Without this, adding one and
     * forgetting to price it here falls back to the default price, which eats the budget quietly.
     */
    test("the shipped list prices every manifestation and every dial") {
        for (manifestation in Manifestation.entries) {
            val price = SHIPPED_PRICES[manifestation]
            check(price != null) { "${manifestation.key} is unpriced, so it would cost the default" }
            check(price.dials.keys == manifestation.dials.toSet()) {
                "${manifestation.key} prices ${price.dials.keys} where it has ${manifestation.dials}"
            }
        }
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
}) {
    private companion object {
        const val HALF_OR_MORE = 0.5
    }
}

/**
 * The price list as shipped in `data/agesandtheart/art/manifestation/`, spelled out so a check reads
 * what a server runs. `ShippedPricesCheck` fails if it drifts from the files.
 */
internal val SHIPPED_PRICES = mapOf(
    Manifestation.TORN_SEAMS to Price.flat(Manifestation.TORN_SEAMS, costs = 2, most = 4),
    Manifestation.WOUNDS to Price.flat(Manifestation.WOUNDS, costs = 5, most = 4, opensAt = 10),
    Manifestation.WORSENING_WOUNDS to Price.flat(Manifestation.WORSENING_WOUNDS, costs = 9, most = 3, opensAt = 120),
    Manifestation.SANDFALL to Price.flat(Manifestation.SANDFALL, costs = 7, most = 4, opensAt = 16),
    Manifestation.BLIZZARD to Price.flat(Manifestation.BLIZZARD, costs = 7, most = 4, opensAt = 16),
    Manifestation.METEORS to Price.flat(Manifestation.METEORS, costs = 7, most = 4, opensAt = 16),
    Manifestation.TECTONICS to Price.flat(Manifestation.TECTONICS, costs = 7, most = 4, opensAt = 16),
    // One step rather than four: an inferno has no designed ramp — see `Manifestation.INFERNO`.
    Manifestation.INFERNO to Price.flat(Manifestation.INFERNO, costs = 7, most = 1, opensAt = 16),
    Manifestation.DELUGE to Price(
        mapOf(
            Manifestation.RISE_RATE to DialPrice.flat(costs = 3, most = 10, opensAt = 16),
            Manifestation.DOWNPOUR to DialPrice.flat(costs = 3, most = 10, opensAt = 16),
            Manifestation.RISE_HEIGHT to DialPrice(
                costs = listOf(3, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 8),
                opensAt = listOf(16, 16, 30, 45, 60, 80, 100, 120, 140, 160, 180, 195),
            ),
        ),
    ),
    Manifestation.COLLAPSE to Price.flat(Manifestation.COLLAPSE, costs = 14, most = 3).copy(certainFrom = 200),
)
