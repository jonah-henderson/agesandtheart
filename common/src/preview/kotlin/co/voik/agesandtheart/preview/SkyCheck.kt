package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.sky.Appearance
import co.voik.agesandtheart.sky.Orbit
import co.voik.agesandtheart.sky.SkySpec

/**
 * Does a sky reproduce, and does a one-sun Age still look like an ordinary world?
 *
 * Run with `./gradlew :common:skycheck`. Offline, because [SkySpec.drawn] is a pure function — the whole reason
 * it takes plain integers rather than resolved options is so this needs no vocabulary and no booted game.
 *
 * The properties are the ones a per-Age sky could plausibly break silently. A sky that fails to reproduce would
 * show up as an Age looking different after a restart, which is exactly the class of bug the recipe model exists
 * to prevent and exactly the kind nobody notices for a month.
 */
fun main() {
    val failures = mutableListOf<String>()

    fun require(condition: Boolean, describe: () -> String) {
        if (!condition) failures += describe()
    }

    // 1. Reproducibility. An Age is rebuilt from its recipe on every open, so the same arguments must give the
    //    same sky forever — and a *different* seed must give a different one, or the seed is not being read.
    val seeds = listOf(0L, 1L, 7L, 4242L, -99L, Long.MAX_VALUE)
    for (seed in seeds) {
        val first = SkySpec.drawn(suns = 3, moons = 2, starCount = 900, spread = 0.6f, seed = seed)
        val again = SkySpec.drawn(suns = 3, moons = 2, starCount = 900, spread = 0.6f, seed = seed)
        require(first == again) { "A sky at seed $seed did not reproduce" }
    }
    val distinct = seeds.map { SkySpec.drawn(3, 2, 900, 0.6f, it) }.distinct()
    require(distinct.size == seeds.size) { "Different seeds gave the same sky: only ${distinct.size} of ${seeds.size} differ" }

    // 2. The primary sun is exactly vanilla's. Tier 1 leaves the lightmap on vanilla's clock, so a one-sun Age
    //    has to sit on vanilla's orbit or noon is bright with the sun off to one side.
    for (seed in seeds) {
        for (suns in 1..4) {
            val spec = SkySpec.drawn(suns, moons = 1, starCount = 0, spread = 1.0f, seed = seed)
            val primary = spec.bodies.first()
            require(primary.orbit == Orbit.VANILLA_SUN) {
                "The first sun at seed $seed with $suns suns is not vanilla's: ${primary.orbit}"
            }
            require(primary.phase == null) { "A sun should not have phases (seed $seed)" }
        }
    }

    // 3. Counts are honoured exactly, including the empty cases a writer can ask for.
    for (suns in 0..4) {
        for (moons in 0..4) {
            val spec = SkySpec.drawn(suns, moons, starCount = 10, spread = 0.5f, seed = 7L)
            require(spec.bodies.size == suns + moons) {
                "Asked for $suns suns and $moons moons, got ${spec.bodies.size} bodies"
            }
            require(spec.bodies.count { it.phase != null } == moons) {
                "Only moons should carry phases; $suns/$moons gave ${spec.bodies.count { it.phase != null }}"
            }
        }
    }
    require(SkySpec.drawn(1, 0, starCount = 0, spread = 0.0f, seed = 7L).stars.count == 0) {
        "\"No stars\" must mean no stars"
    }

    // 4. Nothing drawn is degenerate. A zero or negative period divides by zero in `progressAt`; a zero size or
    //    distance collapses a body to a point. These are the values a careless spread would produce.
    for (seed in seeds) {
        val spec = SkySpec.drawn(suns = 4, moons = 4, starCount = 500, spread = 1.0f, seed = seed)
        for (body in spec.bodies) {
            require(body.orbit.periodTicks > 0) { "A body at seed $seed has period ${body.orbit.periodTicks}" }
            require(body.orbit.distance > 0.0f) { "A body at seed $seed has distance ${body.orbit.distance}" }
            require(body.appearance.angularSize > 0.0f) { "A body at seed $seed has size ${body.appearance.angularSize}" }
            require(body.phase?.let { it.periodTicks > 0 && it.steps >= 1 } != false) {
                "A moon at seed $seed has a degenerate phase cycle: ${body.phase}"
            }
        }
    }

    // 5. Spread does what it says: zero puts every body on ONE SHARED PATH, so an Age can ask for three suns
    //    strung along a single arc like beads.
    //
    //    **This check used to assert only the inclination, and that blind spot shipped a bug.** At spread 0 every
    //    orbit was untilted but each still drew a random ascending node, which rotates the plane about the
    //    vertical — so "shared" gave several distinct great circles that merely happened to contain the zenith.
    //    Jonah spotted it in game; the check had passed. An orbit is a *plane*, and a plane needs both angles.
    //    The period is here for the same reason over a longer timescale: unequal periods drift apart, so bodies
    //    could not hold the formation the word promises.
    for (seed in seeds) {
        val shared = SkySpec.drawn(suns = 4, moons = 2, starCount = 0, spread = 0.0f, seed = seed)
        val vanillaOrbit = shared.bodies.first().orbit
        for (body in shared.bodies) {
            require(body.orbit.inclinationDegrees == vanillaOrbit.inclinationDegrees) {
                "At spread 0 every orbit should share one tilt, found ${body.orbit.inclinationDegrees}"
            }
            require(body.orbit.ascendingNodeDegrees == vanillaOrbit.ascendingNodeDegrees) {
                "At spread 0 every orbit should share one plane, but the ascending node differs: " +
                    "${body.orbit.ascendingNodeDegrees} against ${vanillaOrbit.ascendingNodeDegrees}"
            }
            require(body.orbit.periodTicks == vanillaOrbit.periodTicks) {
                "At spread 0 every body should keep formation, but the period differs: " +
                    "${body.orbit.periodTicks} against ${vanillaOrbit.periodTicks}"
            }
        }
        // ...and the phases must still differ, or "beads on one arc" is one bead.
        require(shared.bodies.map { it.orbit.phaseDegrees }.distinct().size == shared.bodies.size) {
            "At spread 0 the bodies share a phase too, so they are all in the same place at seed $seed"
        }
    }

    // 6. The orbit maths is sane over a whole revolution: progress stays in 0..1 and actually moves.
    val body = SkySpec.drawn(2, 1, 0, 0.7f, 7L).bodies.last()
    val samples = (0..Orbit.TICKS_PER_VANILLA_DAY step 500).map { body.orbit.progressAt(it.toLong()) }
    require(samples.all { it in 0.0f..1.0f }) { "Orbit progress left 0..1: ${samples.filter { it !in 0.0f..1.0f }}" }
    require(samples.distinct().size > samples.size / 2) { "Orbit progress barely moves over a day" }

    // 7. The codec round-trips, including the sealed appearance dispatch and the optional phase. `codeccheck`
    //    proves the codec *builds*; this proves it carries the values.
    val spec = SkySpec.drawn(suns = 3, moons = 2, starCount = 700, spread = 0.8f, seed = 4242L)
    val encoded = SkySpec.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, spec)
        .getOrThrow { error -> IllegalStateException("A sky would not encode: $error") }
    val decoded = SkySpec.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded)
        .getOrThrow { error -> IllegalStateException("A sky would not decode: $error") }
    require(decoded == spec) { "A sky did not survive its codec:\n  before $spec\n  after  $decoded" }
    require(spec.bodies.all { it.appearance is Appearance.Sprite }) { "Tier 1 draws sprites only" }

    // 8. A phase must never index past its atlas. This is the one way borrowing vanilla's moon texture can go
    //     wrong silently: eight steps against a 4x2 grid is fine, nine would read off the end and the ninth
    //     shape would be whatever happens to be adjacent in the PNG.
    for (seed in seeds) {
        val moons = SkySpec.drawn(1, 4, 0, 1.0f, seed).bodies.filter { it.phase != null }
        for (body in moons) {
            val sprite = body.appearance as Appearance.Sprite
            val phase = body.phase ?: continue
            require(phase.steps == sprite.cells) {
                "A moon at seed $seed cycles ${phase.steps} steps over a ${sprite.columns}x${sprite.rows} atlas"
            }
            val visited = (0..<phase.periodTicks step (phase.periodTicks / (phase.steps * 2)).coerceAtLeast(1))
                .map { phase.stepAt(it.toLong()) }
            require(visited.all { it in 0..<sprite.cells }) {
                "A moon at seed $seed indexed outside its atlas: ${visited.filter { it !in 0..<sprite.cells }}"
            }
            require(visited.distinct().size == phase.steps) {
                "A moon at seed $seed shows only ${visited.distinct().size} of its ${phase.steps} shapes"
            }
        }
    }

    // 9. Vanilla's own spelling agrees with the drawn one-sun-one-moon case, so `SkySpec.VANILLA` cannot drift
    //    away from what an ordinary Age actually gets. The moon matters as much as the sun here: vanilla's is
    //    half a turn behind on the same orbit, not wandering.
    require(SkySpec.VANILLA.bodies.first().orbit == Orbit.VANILLA_SUN) {
        "SkySpec.VANILLA's sun is not Orbit.VANILLA_SUN"
    }
    for (seed in seeds) {
        for (spread in listOf(0.0f, 0.45f, 1.0f)) {
            val ordinary = SkySpec.drawn(1, 1, SkySpec.VANILLA_STAR_COUNT, spread, seed)
            require(ordinary.bodies == SkySpec.VANILLA.bodies) {
                "One sun and one moon at seed $seed spread $spread is not vanilla's sky:\n" +
                    "  drawn ${ordinary.bodies}\n  vanilla ${SkySpec.VANILLA.bodies}"
            }
            require(ordinary.isOrdinary) { "One sun and one moon at seed $seed spread $spread is not ordinary" }
        }
    }

    // 10. THE ONE THAT DECIDES WHETHER PLAIN AGES REGRESS. The Sky aspect's four defaults must draw exactly
    //     vanilla's sky, because that is what makes `Sky.dimensionType` hand an unremarkable Age back to
    //     vanilla's own renderer. Reordering any option list would break it silently and every plain Age would
    //     quietly start wearing our sky instead.
    for (sky in Sky.entries) {
        for (seed in seeds) {
            val spec = sky.specFor(Options(), seed)
            require(spec.isOrdinary) {
                "$sky's defaults do not draw an ordinary sky at seed $seed — the first option of SUNS, MOONS " +
                    "and STARS must be the vanilla one. Drawn: $spec"
            }
        }
        require(sky.parameters.containsAll(listOf(Sky.SUNS, Sky.MOONS, Sky.STARS, Sky.ORBITS))) {
            "$sky does not declare all four sky parameters, so a request would be silently dropped"
        }
    }

    // 11. And the counterpart: asking for anything unusual must NOT read as ordinary, or an Age would never be
    //     handed our renderer and the whole feature would be invisible.
    val unusual = listOf(
        "two suns" to Sky.PLAIN.specFor(Options(mapOf(Sky.SUNS.name to listOf("two"))), 7L),
        "no moons" to Sky.PLAIN.specFor(Options(mapOf(Sky.MOONS.name to listOf("none"))), 7L),
        "no stars" to Sky.PLAIN.specFor(Options(mapOf(Sky.STARS.name to listOf("none"))), 7L),
        "dense stars" to Sky.PLAIN.specFor(Options(mapOf(Sky.STARS.name to listOf("dense"))), 7L),
    )
    for ((described, spec) in unusual) {
        require(!spec.isOrdinary) { "\"$described\" reads as an ordinary sky, so no Age would ever draw it" }
    }

    if (failures.isEmpty()) {
        val widest = SkySpec.drawn(4, 4, 1500, 1.0f, 4242L)
        val inclinations = widest.bodies.map { it.orbit.inclinationDegrees.toInt() }.sorted()
        println("Sky: all eleven properties hold. A widest-spread sky of 8 bodies tilts at $inclinations degrees.")
    } else {
        failures.forEach { System.err.println("  $it") }
        error("${failures.size} sky properties failed")
    }
}
