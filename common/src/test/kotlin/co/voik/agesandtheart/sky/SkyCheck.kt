package co.voik.agesandtheart.sky

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.ephemeris.sky.Appearance
import co.voik.ephemeris.sky.CelestialBody
import co.voik.ephemeris.sky.Orbit
import co.voik.ephemeris.sky.SkySpec

import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Sky
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import co.voik.agesandtheart.age.aspect.Span

/**
 * Does a sky reproduce, and does a one-sun Age still look like an ordinary world? Offline, because
 * [SkySpec.drawn] is a pure function — which is why it takes plain integers rather than resolved options.
 *
 * The properties are the ones a per-Age sky could break silently: a sky that fails to reproduce shows up
 * as an Age looking different after a restart, which nobody notices for a month.
 */
/**
 * Everything the resolver draws is a circle, so these read it as one. A cast rather than a `when`, because a
 * spec that started producing some other path would be a change worth failing over here.
 */
private val CelestialBody.orbit: Orbit get() = path as Orbit

class SkyCheck : FunSpec({

    // 1. An Age is rebuilt from its recipe on every open, so the same arguments must give the same sky forever —
    //    and a *different* seed must give a different one, or the seed is not being read.
    test("a sky reproduces from its seed") {
        for (seed in SEEDS) {
            val first = SkySpec.drawn(suns = 3, moons = 2, starCount = 900, spread = 0.6f, seed = seed)
            val again = SkySpec.drawn(suns = 3, moons = 2, starCount = 900, spread = 0.6f, seed = seed)
            check(first == again) { "A sky at seed $seed did not reproduce" }
        }
        val distinct = SEEDS.map { SkySpec.drawn(3, 2, 900, 0.6f, it) }.distinct()
        check(distinct.size == SEEDS.size) {
            "Different seeds gave the same sky: only ${distinct.size} of ${SEEDS.size} differ"
        }
    }

    // 2. Tier 1 leaves the lightmap on vanilla's clock, so a one-sun Age has to sit on vanilla's orbit or noon
    //    is bright with the sun off to one side.
    test("the primary sun is exactly vanilla's") {
        for (seed in SEEDS) {
            for (suns in 1..4) {
                val spec = SkySpec.drawn(suns, moons = 1, starCount = 0, spread = 1.0f, seed = seed)
                val primary = spec.bodies.first()
                check(primary.orbit == Orbit.VANILLA_SUN) {
                    "The first sun at seed $seed with $suns suns is not vanilla's: ${primary.orbit}"
                }
                check(primary.phase == null) { "A sun should not have phases (seed $seed)" }
            }
        }
    }

    test("counts are honoured exactly, including the empty cases") {
        for (suns in 0..4) {
            for (moons in 0..4) {
                val spec = SkySpec.drawn(suns, moons, starCount = 10, spread = 0.5f, seed = 7L)
                check(spec.bodies.size == suns + moons) {
                    "Asked for $suns suns and $moons moons, got ${spec.bodies.size} bodies"
                }
                check(spec.bodies.count { it.phase != null } == moons) {
                    "Only moons should carry phases; $suns/$moons gave ${spec.bodies.count { it.phase != null }}"
                }
            }
        }
        check(SkySpec.drawn(1, 0, starCount = 0, spread = 0.0f, seed = 7L).stars.count == 0) {
            "\"No stars\" must mean no stars"
        }
    }

    // 4. A zero or negative period divides by zero in `progressAt`; a zero size or distance collapses a body to
    //    a point. These are the values a careless spread would produce.
    test("nothing drawn is degenerate") {
        for (seed in SEEDS) {
            val spec = SkySpec.drawn(suns = 4, moons = 4, starCount = 500, spread = 1.0f, seed = seed)
            for (body in spec.bodies) {
                check(body.orbit.periodTicks > 0) { "A body at seed $seed has period ${body.orbit.periodTicks}" }
                check(body.orbit.distance > 0.0f) { "A body at seed $seed has distance ${body.orbit.distance}" }
                check(body.appearance.angularSize > 0.0f) {
                    "A body at seed $seed has size ${body.appearance.angularSize}"
                }
                check(body.phase?.let { it.periodTicks > 0 && it.steps >= 1 } != false) {
                    "A moon at seed $seed has a degenerate phase cycle: ${body.phase}"
                }
            }
        }
    }

    /**
     * Spread does what it says: zero puts every body on **one shared path**, so an Age can ask for three
     * suns strung along a single arc like beads.
     *
     * **All three of inclination, ascending node and period**, because asserting the inclination alone
     * shipped a bug: a random ascending node rotates an untilted plane about the vertical, so "shared" gave
     * several distinct great circles that merely contained the zenith. An orbit is a *plane*, and a plane
     * needs both angles; unequal periods drift apart over a longer timescale.
     */
    test("at spread zero every body shares one path") {
        for (seed in SEEDS) {
            val shared = SkySpec.drawn(suns = 4, moons = 2, starCount = 0, spread = 0.0f, seed = seed)
            val vanillaOrbit = shared.bodies.first().orbit
            for (body in shared.bodies) {
                check(body.orbit.inclinationDegrees == vanillaOrbit.inclinationDegrees) {
                    "At spread 0 every orbit should share one tilt, found ${body.orbit.inclinationDegrees}"
                }
                check(body.orbit.ascendingNodeDegrees == vanillaOrbit.ascendingNodeDegrees) {
                    "At spread 0 every orbit should share one plane, but the ascending node differs: " +
                        "${body.orbit.ascendingNodeDegrees} against ${vanillaOrbit.ascendingNodeDegrees}"
                }
                check(body.orbit.periodTicks == vanillaOrbit.periodTicks) {
                    "At spread 0 every body should keep formation, but the period differs: " +
                        "${body.orbit.periodTicks} against ${vanillaOrbit.periodTicks}"
                }
            }
            // ...and the phases must still differ, or "beads on one arc" is one bead.
            check(shared.bodies.map { it.orbit.phaseDegrees }.distinct().size == shared.bodies.size) {
                "At spread 0 the bodies share a phase too, so they are all in the same place at seed $seed"
            }
        }
    }

    test("orbit progress stays in zero to one and actually moves") {
        val body = SkySpec.drawn(2, 1, 0, 0.7f, 7L).bodies.last()
        val samples = (0..Orbit.TICKS_PER_VANILLA_DAY step 500).map { body.orbit.progressAt(it.toLong()) }
        check(samples.all { it in 0.0f..1.0f }) {
            "Orbit progress left 0..1: ${samples.filter { it !in 0.0f..1.0f }}"
        }
        check(samples.distinct().size > samples.size / 2) { "Orbit progress barely moves over a day" }
    }

    /** `CodecCheck` proves the codec *builds*; this proves it carries the values. */
    test("a sky survives its codec, sealed appearance and optional phase included") {
        val spec = SkySpec.drawn(suns = 3, moons = 2, starCount = 700, spread = 0.8f, seed = 4242L)
        val encoded = SkySpec.CODEC.encodeStart(JsonOps.INSTANCE, spec)
            .getOrThrow { error -> IllegalStateException("A sky would not encode: $error") }
        val decoded = SkySpec.CODEC.parse(JsonOps.INSTANCE, encoded)
            .getOrThrow { error -> IllegalStateException("A sky would not decode: $error") }
        check(decoded == spec) { "A sky did not survive its codec:\n  before $spec\n  after  $decoded" }
        check(spec.bodies.all { it.appearance is Appearance.Sprite }) { "Tier 1 draws sprites only" }
    }

    /**
     * A phase must never index past the shapes it has — the one way borrowing vanilla's moon sprites can go
     * wrong silently. Eight steps against vanilla's eight shapes is fine; nine would read off the end.
     */
    test("a moon never indexes past its shapes") {
        for (seed in SEEDS) {
            val moons = SkySpec.drawn(1, 4, 0, 1.0f, seed).bodies.filter { it.phase != null }
            for (body in moons) {
                val sprite = body.appearance as Appearance.Sprite
                val phase = body.phase ?: continue
                check(phase.steps == sprite.cells) {
                    "A moon at seed $seed cycles ${phase.steps} steps over ${sprite.cells} shapes"
                }
                val visited = (0..<phase.periodTicks step (phase.periodTicks / (phase.steps * 2)).coerceAtLeast(1))
                    .map { phase.stepAt(it.toLong()) }
                check(visited.all { it in 0..<sprite.cells }) {
                    "A moon at seed $seed indexed outside its shapes: ${visited.filter { it !in 0..<sprite.cells }}"
                }
                check(visited.distinct().size == phase.steps) {
                    "A moon at seed $seed shows only ${visited.distinct().size} of its ${phase.steps} shapes"
                }
            }
        }
    }

    /**
     * Vanilla's own spelling agrees with the drawn one-sun-one-moon case, so [SkySpec.VANILLA] cannot drift
     * away from what an ordinary Age actually gets. The moon matters as much as the sun here: vanilla's is
     * half a turn behind on the same orbit, not wandering.
     */
    test("vanilla's spelling agrees with the drawn one-sun-one-moon case") {
        check(SkySpec.VANILLA.bodies.first().orbit == Orbit.VANILLA_SUN) {
            "SkySpec.VANILLA's sun is not Orbit.VANILLA_SUN"
        }
        for (seed in SEEDS) {
            for (spread in listOf(0.0f, 0.45f, 1.0f)) {
                val ordinary = SkySpec.drawn(1, 1, SkySpec.VANILLA_STAR_COUNT, spread, seed)
                check(ordinary.bodies == SkySpec.VANILLA.bodies) {
                    "One sun and one moon at seed $seed spread $spread is not vanilla's sky:\n" +
                        "  drawn ${ordinary.bodies}\n  vanilla ${SkySpec.VANILLA.bodies}"
                }
                check(ordinary.isOrdinary) { "One sun and one moon at seed $seed spread $spread is not ordinary" }
            }
        }
    }

    /**
     * **The one that decides whether plain Ages regress.** The Sky aspect's four defaults must draw exactly
     * vanilla's sky, which is what makes `Sky.dimensionType` hand an unremarkable Age back to vanilla's own
     * renderer. Reordering any option list breaks it silently.
     */
    test("the sky aspect's defaults draw an ordinary sky") {
        // SPIRE is exempt because it resolves nothing: its sky is written down in `SpireSky`, so its
        // options never reach `SkySpec.drawn` and it cannot say anything about their defaults. The check
        // below holds it to being bespoke, so the exemption is not a hole to hide a regression in.
        for (sky in Sky.entries.filter { it != Sky.SPIRE }) {
            for (seed in SEEDS) {
                val spec = sky.specFor({ _, _ -> Options() }, seed)
                check(spec.isOrdinary) {
                    "$sky's defaults do not draw an ordinary sky at seed $seed — a book that minted no " +
                        "body and bent no axis must keep the overworld's own sky. Drawn: $spec"
                }
            }
        }
        // The bodies are the sun's, the moon's and the stars' rather than any sky preset's, so it is the
        // aspects that must hold their knobs — one nothing declares is a request silently dropped.
        for (parameter in listOf(Sky.SHINING, Sky.SUNSIZE, Sky.SUNCOLOUR)) {
            check(parameter in Aspect.SUN.dials) { "the sun does not hold ${parameter.name}" }
        }
        check(Sky.STARS in Aspect.STARS.dials) { "the stars do not hold their own density" }
    }

    /**
     * The Spire's sky is the one that is *deliberately* not ordinary, and it must stay that way whatever
     * is asked of it — no seed and no option may move it, because it is written rather than resolved.
     */
    test("the Spire's sky is bespoke and unmoved by what is asked of it") {
        val spec = Sky.SPIRE.specFor({ _, _ -> Options() }, seed = 0L)
        check(!spec.isOrdinary) { "The Spire's sky reads as ordinary, so vanilla would draw it instead" }
        check(spec.decks.size == 2) { "The Spire has ${spec.decks.size} cloud decks, and its sky is two" }
        check(spec.bodies.isEmpty()) { "The Spire has never had a sun or a moon, but drew ${spec.bodies.size}" }
        check(spec.stars.reveal != null) { "The Spire's stars must be hidden until you climb above its deck" }

        for (seed in SEEDS) {
            check(Sky.SPIRE.specFor({ _, _ -> Options() }, seed) == spec) {
                "The Spire's sky moved at seed $seed, so something about it is being resolved after all"
            }
        }
    }

    /**
     * A reveal band must rise, or `visibilityAt` divides by zero and the stars either never appear or are
     * always out. Cheap to get backwards when retuning a deck.
     */
    test("a star reveal fades upward across a real band") {
        val reveal = Sky.SPIRE.specFor({ _, _ -> Options() }, seed = 0L).stars.reveal ?: error("The Spire has no reveal")
        check(reveal.fullyShownAbove > reveal.hiddenBelow) {
            "The Spire's reveal band does not rise: ${reveal.hiddenBelow}..${reveal.fullyShownAbove}"
        }
        check(reveal.visibilityAt(reveal.hiddenBelow - 100.0) == 0.0f) { "Stars show below the band" }
        check(reveal.visibilityAt(reveal.fullyShownAbove + 100.0) == 1.0f) { "Stars are dimmed above the band" }
        val midway = reveal.visibilityAt((reveal.hiddenBelow + reveal.fullyShownAbove) / 2.0)
        check(midway > 0.0f && midway < 1.0f) { "The band does not fade, it snaps: midway reveal is $midway" }
    }

    /** The counterpart: anything unusual must NOT read as ordinary, or the whole feature would be invisible. */
    test("anything unusual does not read as ordinary") {
        val unusual = listOf(
            // Two suns is two clauses that minted one, which is the cast rather than any option.
            "two suns" to Sky.PLAIN.specFor({ _, _ -> Options() }, A_SEED) { aspect ->
                if (aspect == Aspect.SUN) 2 else 0
            },
            "no suns" to Sky.PLAIN.specFor(
                { aspect, _ -> if (aspect == Aspect.SUN) Options(mapOf(Sky.SHINING.name to listOf(Sky.NEVER))) else Options() },
                A_SEED,
            ),
            "no stars" to Sky.PLAIN.specFor({ _, _ -> Options(mapOf(Sky.STARS.name to listOf(EMPTIEST))) }, A_SEED),
            "dense stars" to Sky.PLAIN.specFor({ _, _ -> Options(mapOf(Sky.STARS.name to listOf(FULLEST))) }, A_SEED),
        )
        for ((described, spec) in unusual) {
            check(!spec.isOrdinary) { "\"$described\" reads as an ordinary sky, so no Age would ever draw it" }
        }
    }
})

private val SEEDS = listOf(0L, 1L, 7L, 4242L, -99L, Long.MAX_VALUE)

/** One seed, where the property under check does not vary with it. */
private const val A_SEED = 7L

/** The two ends of a ranged axis, as a recipe pins them. */
private val EMPTIEST = Span.at(Span.NATURAL_LEAST).spelled()
private val FULLEST = Span.at(Span.NATURAL_MOST).spelled()
