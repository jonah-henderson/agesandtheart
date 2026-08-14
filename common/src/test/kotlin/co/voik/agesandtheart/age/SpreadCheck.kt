package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.age.aspect.Terrain
import io.kotest.core.spec.style.FunSpec

/**
 * How a **spatial population** decides what its boundary looks like (`the-world-model.md` §2) — the draw,
 * and what a book may say over the top of it.
 *
 * Registry-free selection rules rather than sampled ground, so these run in the ordinary suite;
 * `FaultCheck` owns what a form actually does to the rock, and is a landform check for that reason.
 */
class SpreadCheck : FunSpec({

    /**
     * **A boundary is only drawn where there is one to draw.** A population of one member has no seam, and
     * an Age that came out whole must not carry a form that would silently appear the day something
     * divided it.
     */
    test("nothing is drawn between one member and itself") {
        val whole = AgeComposition(terrains = listOf(Terrain.HILLS)).seamed(A_SEED)
        for (aspect in Aspect.entries.filter { it.spatial }) {
            check(whole.spreads.of(aspect).drawn == null) {
                "'${aspect.key}' was drawn a ${whole.spreads.of(aspect).drawn?.key} with nothing to draw it between"
            }
        }
    }

    /**
     * **A form asked for is kept, and asking for a shear is asking for something.** `landmass.seam=sheared`
     * on a divided Age is a writer asking for no fault at all, so a draw over the top of it would be the
     * silent drop §3.3 forbids — and it is the one pin a nullable field is what keeps.
     */
    test("a form asked for survives the draw") {
        val divided = AgeComposition(terrains = listOf(Terrain.HILLS, Terrain.PILLARS))
        for (asked in Seam.entries) {
            val pinned = divided.withOptions(Aspect.TERRAIN, Spread.SEAM, listOf(asked.key))
            // Every seed, since one that agreed with the draw would pass while proving nothing.
            for (seed in 1L..SEEDS) {
                val written = pinned.seamed(seed).spreadOf(Aspect.TERRAIN).seam
                check(written == asked) { "'${asked.key}' was asked for at seed $seed and drew '${written.key}'" }
            }
        }
    }

    /**
     * **Only the terrain is thrown.** A displacement needs rock of its own to displace, so a sea, a
     * carving and a climate divide over ground the terrain shaped — and a scarp drawn for one of them
     * would be a form nothing could ever show.
     */
    test("no boundary but the terrain's displaces") {
        for (aspect in Aspect.entries.filter { it.spatial && it != Aspect.TERRAIN }) {
            for (seed in 1L..SEEDS) {
                val drawn = Seam.drawnFor(aspect, Seam.sourceFor(aspect, seed))
                check(!drawn.displaces) { "'${aspect.key}' drew a '${drawn.key}', which has no rock to move" }
            }
        }
    }

    /**
     * **A dissolve stays rare wherever it is drawn.** The displacing forms are *reduced* to a shear off the
     * terrain rather than drawn out of what is left, which is the whole difference between a fuzz at its
     * declared twentieth and a fuzz at a quarter of the two outcomes remaining.
     */
    test("a fuzz is as rare off the terrain as on it") {
        fun fuzzedOutOf(aspect: Aspect) =
            (1L..SEEDS).count { Seam.drawnFor(aspect, Seam.sourceFor(aspect, it)) == Seam.FUZZED }

        val everywhere = Aspect.entries.filter { it.spatial }.map { it to fuzzedOutOf(it) }
        for ((aspect, fuzzed) in everywhere) {
            val share = fuzzed.toDouble() / SEEDS
            val declared = Seam.FUZZED.frequency.toDouble() / Seam.entries.sumOf { it.frequency }
            check(share in declared - TOLERANCE..declared + TOLERANCE) {
                "'${aspect.key}' fuzzed $fuzzed of $SEEDS boundaries, a share of $share against $declared"
            }
        }
    }

    /**
     * **Two boundaries in one Age are drawn apart.** They read one source per aspect, so an Age divided in
     * its rock *and* its caves is not two copies of one geology — which is the point of the form belonging
     * to the population rather than to the Age.
     */
    test("two populations do not draw the same form") {
        val divided = AgeComposition(terrains = listOf(Terrain.HILLS, Terrain.PILLARS))
            .withPresets(Aspect.CARVERS, listOf(Carvers.CAVES.key, Carvers.SOLID.key))
        val disagreed = (1L..SEEDS).count { seed ->
            val seamed = divided.seamed(seed)
            seamed.spreadOf(Aspect.TERRAIN).seam != seamed.spreadOf(Aspect.CARVERS).seam
        }
        // The carving can only shear or fuzz, so the two agree whenever the terrain shears too — which is
        // most of the time. Any disagreement at all is what proves the sources are not one.
        check(disagreed > SEEDS / 2) { "the rock and the caves drew alike at all but $disagreed of $SEEDS seeds" }
    }
})

/** Enough seeds that a twentieth is measurable, and few enough that the spec still runs in a blink. */
private const val SEEDS = 4000

/** How far an observed share may sit from its declared one over [SEEDS] draws. */
private const val TOLERANCE = 0.02

private const val A_SEED = 0x5EA_11L
