package co.voik.agesandtheart.age

import io.kotest.core.spec.style.FunSpec

/**
 * How an Age's instability is spent (`notes/instability-tiers-plan.md` §2).
 *
 * Each distinct flaw buys one step, in tiers set by how many flaws there are and capped by how long the book
 * was. Which steps an Age buys is drawn, so what a check can hold is the rules every draw obeys: the tier
 * bands, the cap, the even spread with the top tier always reached, nothing bought above it, the exclusions,
 * and that the same Age always draws the same way.
 */
class SpendingCheck : FunSpec({

    /** Enough seeds that a rule holding for all of them is not a property of one draw. */
    val seeds = (0L..<40L).map { it * 7_919L + 13L }

    val cold = SpendingSetting(mapOf("temperature" to -0.8, "humidity" to 0.0), emptySet())
    val hot = SpendingSetting(mapOf("temperature" to 0.8, "humidity" to -0.4), emptySet())
    val divided = SpendingSetting(emptyMap(), setOf(Showing.REGIONS))
    val everythingShows = Showing.entries.toSet()

    fun spent(flaws: Int, seed: Long, setting: SpendingSetting = SpendingSetting.UNKNOWN, pages: Int = LONG_BOOK) =
        Spending.of(Instability.forced(flaws).copy(pages = pages), seed, SHIPPED_LADDERS, setting)

    /** The tier of every step [spending] bought. */
    fun tiersBought(spending: Spending): List<Int> = Manifestation.entries.flatMap { manifestation ->
        manifestation.dials.flatMap { dial ->
            SHIPPED_LADDERS.getValue(manifestation).dials.getValue(dial).tiers.take(spending.bought(manifestation, dial))
        }
    }

    test("a coherent Age buys nothing") {
        val spending = spent(flaws = 0, seed = 1L)
        check(spending == Spending.NOTHING) { "a coherent Age bought something: $spending" }
    }

    /** Jonah's bands, and the bare desk's page limit over them. */
    test("the top tier is set by the flaws, and a short book stays strange") {
        val expected = mapOf(1 to 1, 3 to 1, 4 to 2, 6 to 2, 7 to 3, 20 to 3)
        for ((flaws, tier) in expected) {
            check(Spending.topTier(flaws, LONG_BOOK) == tier) { "$flaws flaws reached tier ${Spending.topTier(flaws, LONG_BOOK)}" }
            check(Spending.topTier(flaws, Spending.PAGES_KEPT_STRANGE) == Spending.STRANGE) {
                "$flaws flaws in a five-page book climbed out of the strange tier"
            }
        }
    }

    /** As evenly as they go, the remainder to the lowest tiers. */
    test("the flaws are dealt out evenly, the lowest tiers taking what is over") {
        check(Spending.slotsPerTier(7, top = 3) == mapOf(1 to 3, 2 to 2, 3 to 2)) { "seven gave ${Spending.slotsPerTier(7, 3)}" }
        check(Spending.slotsPerTier(5, top = 2) == mapOf(1 to 3, 2 to 2)) { "five gave ${Spending.slotsPerTier(5, 2)}" }
        check(Spending.slotsPerTier(3, top = 1) == mapOf(1 to 3)) { "three gave ${Spending.slotsPerTier(3, 1)}" }
        for (flaws in 1..30) {
            val top = Spending.topTier(flaws, LONG_BOOK)
            val slots = Spending.slotsPerTier(flaws, top)
            check(slots.values.sum() == flaws) { "$flaws flaws were dealt ${slots.values.sum()} slots" }
            check((slots[top] ?: 0) >= 1) { "$flaws flaws left the top tier empty: $slots" }
        }
    }

    /** One step a flaw, never more; and none above the tier the flaws reach. */
    test("each flaw buys at most one step, and none above its tier") {
        for (seed in seeds) {
            for (flaws in 1..20) {
                val bought = tiersBought(spent(flaws, seed))
                check(bought.size <= flaws) { "$flaws flaws bought ${bought.size} steps under seed $seed" }
                val top = Spending.topTier(flaws, LONG_BOOK)
                check(bought.all { it <= top }) { "$flaws flaws bought tiers $bought, above $top, under seed $seed" }
            }
        }
    }

    /** "Mistakes must purchase at least one manifestation of the top tier" (Jonah). */
    test("the top tier is always bought where it has anything left") {
        for (seed in seeds) {
            for (flaws in 4..20) {
                val top = Spending.topTier(flaws, LONG_BOOK)
                check(top in tiersBought(spent(flaws, seed))) { "$flaws flaws under seed $seed bought nothing at tier $top" }
            }
        }
    }

    test("a short book never buys a hazard, however wrong it is") {
        for (seed in seeds) {
            for (setting in listOf(SpendingSetting.UNKNOWN, divided)) {
                val bought = tiersBought(spent(flaws = 12, seed, setting, pages = Spending.PAGES_KEPT_STRANGE))
                check(bought.all { it == Spending.STRANGE }) { "a five-page book bought tiers $bought under seed $seed" }
            }
        }
    }

    /** One slip is strange: a divided Age's seams tear, or something comes over its sky. */
    test("one flaw is a strange step, and a divided Age's seams can be it") {
        fun isStrange(manifestation: Manifestation) =
            SHIPPED_LADDERS.getValue(manifestation).dials.values.all { steps -> steps.tiers.all { it == Spending.STRANGE } }
        for (seed in seeds) {
            val bought = spent(1, seed, divided)
            val strange = Manifestation.entries.filter(::isStrange).sumOf { bought.bought(it) }
            check(strange == 1) { "one flaw in a divided Age under seed $seed bought $bought" }
        }
        check(seeds.any { spent(1, it, divided).bought(Manifestation.TORN_SEAMS) == 1 }) {
            "no divided Age in forty tore its seams for one flaw"
        }
    }

    /**
     * **Deterministic**, which the recipe model rests on: an Age rebuilds from its recipe on every open, so a
     * spending that differed the second time would be a different world under one book.
     */
    test("the same Age spends the same way every time") {
        for (seed in seeds) {
            for (flaws in 0..20) {
                check(spent(flaws, seed, cold) == spent(flaws, seed, cold)) { "$flaws flaws under seed $seed are not stable" }
            }
        }
    }

    test("the same flaws show as different symptoms in different Ages") {
        val shown = seeds.map { spent(6, it) }.toSet()
        check(shown.size > seeds.size / 2) { "forty seeds at six flaws drew only ${shown.size} different Ages" }
    }

    /** The one exclusive pair (Jonah, 2026-10-12), in every climate. */
    test("a blizzard and an inferno never share an Age") {
        for (setting in listOf(SpendingSetting.UNKNOWN, cold, hot)) {
            for (seed in seeds) {
                for (flaws in listOf(6, 12, 40)) {
                    val spending = spent(flaws, seed, setting)
                    val both = spending.bought(Manifestation.BLIZZARD) > 0 && spending.bought(Manifestation.INFERNO) > 0
                    check(!both) { "$flaws flaws under seed $seed in $setting drew both: $spending" }
                }
            }
        }
    }

    /** The lean makes instability fit the place: cold Ages snow, hot ones burn. */
    test("a cold Age leans to the blizzard and a hot one to the inferno") {
        fun count(setting: SpendingSetting, manifestation: Manifestation) =
            (0L..<300L).count { spent(6, it * 104_729L + 3L, setting).bought(manifestation) > 0 }
        check(count(cold, Manifestation.BLIZZARD) > count(cold, Manifestation.INFERNO) * 2) {
            "a cold Age snowed ${count(cold, Manifestation.BLIZZARD)} times and burned ${count(cold, Manifestation.INFERNO)}"
        }
        check(count(hot, Manifestation.INFERNO) > count(hot, Manifestation.BLIZZARD) * 2) {
            "a hot Age burned ${count(hot, Manifestation.INFERNO)} times and snowed ${count(hot, Manifestation.BLIZZARD)}"
        }
    }

    /** An undivided Age has no seam to tear, so a step there would show nothing. */
    test("seams tear only where the Age has territories") {
        for (seed in seeds) {
            check(spent(4, seed).bought(Manifestation.TORN_SEAMS) == 0) { "an undivided Age tore its seams under seed $seed" }
        }
        check(seeds.any { spent(4, it, divided).bought(Manifestation.TORN_SEAMS) > 0 }) {
            "no divided Age in forty tore its seams"
        }
    }

    test("nothing is bought where the Age has nothing for it to show on") {
        val needing = SHIPPED_LADDERS.filterValues { it.needs.isNotEmpty() }
        for (seed in seeds) {
            for (flaws in listOf(1, 3, 12)) {
                val bought = spent(flaws, seed)
                val shownOnNothing = needing.keys.filter { bought.bought(it) > 0 }
                check(shownOnNothing.isEmpty()) { "an Age with nothing to show on bought $shownOnNothing under seed $seed" }
            }
        }
    }

    /**
     * And every dial is bought somewhere, or it is dead content whatever the draw does — at flaw counts a book
     * reaches, and in the climate it leans towards, since a blizzard keeps the inferno out of an Age that is
     * not hot. Not bought *in full*: with every flaw drawn, a heavily flawed Age buys the blizzard's
     * unsettling steps sooner or later, which shuts the inferno out however hot it is.
     */
    test("every dial is bought by some Age with many flaws") {
        val manySeeds = (0L..<100L).map { it * 104_729L + 3L }
        val climates = listOf(divided, hot, cold).map { it.copy(has = everythingShows) }
        for (manifestation in Manifestation.entries) {
            for (dial in manifestation.dials) {
                val bought = climates.any { setting ->
                    manySeeds.any { seed -> (10..30 step 5).any { spent(it, seed, setting).bought(manifestation, dial) > 0 } }
                }
                check(bought) { "${manifestation.key} $dial was never bought" }
            }
        }
    }

    /**
     * **That the map below names every manifestation and every dial there is.** Without this, adding one and
     * forgetting it here falls back to the default ladder quietly.
     */
    test("the shipped ladders cover every manifestation and every dial") {
        for (manifestation in Manifestation.entries) {
            val ladder = SHIPPED_LADDERS[manifestation]
            check(ladder != null) { "${manifestation.key} has no ladder, so it would take the default" }
            check(ladder.dials.keys == manifestation.dials.toSet()) {
                "${manifestation.key} ladders ${ladder.dials.keys} where it has ${manifestation.dials}"
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
        /** Long enough that the page cap never binds. */
        const val LONG_BOOK = 40
    }
}

private fun tiers(vararg each: Int) = DialSteps(each.toList())

/**
 * The ladders as shipped in `data/agesandtheart/art/manifestation/`, spelled out so a check reads what a
 * server runs. `ShippedLaddersCheck` fails if it drifts from the files.
 */
internal val SHIPPED_LADDERS = mapOf(
    Manifestation.TORN_SEAMS to Ladder.flat(Manifestation.TORN_SEAMS, tier = 1, most = 4).copy(needs = setOf(Showing.REGIONS)),
    Manifestation.WOUNDS to Ladder.flat(Manifestation.WOUNDS, tier = 2, most = 4),
    Manifestation.WORSENING_WOUNDS to Ladder.flat(Manifestation.WORSENING_WOUNDS, tier = 3, most = 3),
    Manifestation.SANDFALL to Ladder(
        mapOf(
            Manifestation.COLUMNS_OFTEN to DialSteps.flat(tier = 2, most = 4),
            Manifestation.COLUMNS_LONG to DialSteps.flat(tier = 2, most = 4),
            Manifestation.COLUMNS_SIZE to DialSteps.flat(tier = 2, most = 4),
            Manifestation.COLUMNS_DEPTH to DialSteps.flat(tier = 2, most = 4),
        ),
        leans = mapOf("humidity" to -1.0, "temperature" to 0.3),
    ),
    Manifestation.BLIZZARD to Ladder(
        mapOf(
            Manifestation.SNOWSTORMS_OFTEN to DialSteps.flat(tier = 2, most = 4),
            Manifestation.SNOWSTORMS_LONG to DialSteps.flat(tier = 2, most = 4),
            Manifestation.SNOWSTORMS_VISIBILITY to DialSteps.flat(tier = 2, most = 4),
            Manifestation.SNOWSTORMS_FROSTBITE to DialSteps.flat(tier = 2, most = 4),
        ),
        leans = mapOf("temperature" to -1.0),
        excludes = setOf(Manifestation.INFERNO),
    ),
    Manifestation.METEORS to Ladder(
        mapOf(
            Manifestation.STORMS_OFTEN to DialSteps.flat(tier = 2, most = 4),
            Manifestation.STORMS_LONG to DialSteps.flat(tier = 2, most = 4),
            Manifestation.STORMS_POWER to DialSteps.flat(tier = 2, most = 4),
        ),
    ),
    Manifestation.TECTONICS to Ladder.flat(Manifestation.TECTONICS, tier = 3, most = 4),
    Manifestation.TEMPEST to Ladder(
        mapOf(
            Manifestation.BOLTS_OFTEN to DialSteps.flat(tier = 2, most = 4),
            Manifestation.BOLTS_BLAST to DialSteps.flat(tier = 2, most = 4),
            Manifestation.BOLTS_FIRE to DialSteps.flat(tier = 2, most = 4),
        ),
        leans = mapOf("humidity" to 0.5),
    ),
    Manifestation.INFERNO to Ladder(
        mapOf(
            Manifestation.BURN_DAMAGE to DialSteps.flat(tier = 2, most = 4),
            Manifestation.LIGHT_INTENSITY to DialSteps.flat(tier = 2, most = 4),
        ),
        leans = mapOf("temperature" to 1.0, "humidity" to -0.5),
    ),
    Manifestation.DELUGE to Ladder(
        mapOf(
            Manifestation.RISE_RATE to DialSteps.flat(tier = 2, most = 10),
            Manifestation.DOWNPOUR to DialSteps.flat(tier = 2, most = 10),
            Manifestation.RISE_HEIGHT to tiers(2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3),
        ),
        leans = mapOf("humidity" to 1.0),
    ),
    Manifestation.COLLAPSE to Ladder.flat(Manifestation.COLLAPSE, tier = 3, most = 3),
    Manifestation.FOG to Ladder.flat(Manifestation.FOG, tier = 1, most = 4).copy(leans = mapOf("humidity" to 0.3)),
    Manifestation.SKY_DRIFT to Ladder(
        mapOf(
            Manifestation.DRIFT_STRENGTH to DialSteps.flat(tier = 1, most = 3),
            Manifestation.DRIFT_PACE to DialSteps.flat(tier = 1, most = 2),
        ),
    ),
    Manifestation.DAY_STARS to Ladder.flat(Manifestation.DAY_STARS, tier = 1, most = 2)
        .copy(leans = mapOf("humidity" to -0.3)),
    Manifestation.LOW_CLOUDS to Ladder.flat(Manifestation.LOW_CLOUDS, tier = 1, most = 2)
        .copy(leans = mapOf("humidity" to 0.3)),
    Manifestation.STILL_CREATURES to Ladder.flat(Manifestation.STILL_CREATURES, tier = 1, most = 1)
        .copy(needs = setOf(Showing.CREATURES)),
    Manifestation.STAMPED_SHAPES to Ladder.flat(Manifestation.STAMPED_SHAPES, tier = 1, most = 1)
        .copy(needs = setOf(Showing.TREES)),
    Manifestation.LEAFLESS_TREES to Ladder.flat(Manifestation.LEAFLESS_TREES, tier = 1, most = 1)
        .copy(needs = setOf(Showing.TREES)),
    Manifestation.ABANDONED_VILLAGES to Ladder.flat(Manifestation.ABANDONED_VILLAGES, tier = 1, most = 1)
        .copy(needs = setOf(Showing.VILLAGES)),
    Manifestation.UNCANNY_MOTES to Ladder.flat(Manifestation.UNCANNY_MOTES, tier = 1, most = 2),
)
