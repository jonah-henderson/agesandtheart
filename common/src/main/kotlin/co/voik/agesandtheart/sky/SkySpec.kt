package co.voik.agesandtheart.sky

import co.voik.agesandtheart.math.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.RandomSource
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * Everything an Age's sky is, as data: its celestial bodies and its stars.
 *
 * **This is what crosses to the client**, and it is the whole reason per-Age skies are possible at all. A
 * `DimensionType` cannot be composed per Age — its network codec writes registry ids only, so an unregistered one
 * cannot even be encoded in the join packet (the plan's step 8). But nothing here is a registry object. It is
 * plain data on a custom payload, so the frozen-registry problem never touches it. See
 * `notes/per-age-skies-research.md` §1.
 *
 * **Derived, never stored.** [drawn] is a pure function of its arguments, so an Age rebuilds the same sky on
 * every open from its recipe alone — the same contract `AgeGeneration.chunkGenerator` holds. The codec here
 * exists to *send* a spec, not to persist one; what persists is the recipe that produces it.
 */
data class SkySpec(val bodies: List<CelestialBody>, val stars: StarField) {

    /**
     * Whether this is an ordinary sky — vanilla's sun, vanilla's moon, vanilla's stars, nothing added.
     *
     * **What this is for:** an Age only needs *our* renderer if it has something vanilla cannot draw. When this
     * is true, `AgeGeneration` gives the Age vanilla's own `effects` and the client attaches nothing, so a plain
     * Age keeps vanilla's sky exactly rather than an imitation of it. The moment a writer asks for a second sun
     * or takes the stars away, the Age switches to our renderer. That is what keeps "plain" honest without
     * either regressing every ordinary Age or silently dropping a request (§3.3).
     *
     * The star *seed* is deliberately not compared: a different arrangement of the same number of stars is not
     * a thing vanilla cannot draw, and vanilla's own field is a fixed seed anyway.
     */
    val isOrdinary: Boolean
        get() = bodies == VANILLA.bodies && stars.count == VANILLA.stars.count

    /**
     * This sky in a line per body, for `/age sky`.
     *
     * Numbers and all, which §3.2 permits without argument: that section forbids showing numbers to the
     * **player**, and this is a developer instrument behind an operator permission. Spelling the orbit out is the
     * entire point — "three suns" tells you nothing about why two of them are bunched together.
     */
    fun described(): List<String> = bodies.map { body ->
        val orbit = body.orbit
        val sprite = body.appearance as? Appearance.Sprite
        val kind = if (body.phase == null) "sun " else "moon"
        val direction = if (orbit.retrograde) " retrograde" else ""
        val days = orbit.periodTicks.toFloat() / Orbit.TICKS_PER_VANILLA_DAY
        // The node is drawn as a swing either side of vanilla's -90, so it can land outside a turn — `-208°`
        // rather than the `152°` that means the same thing. Rotations are modular so the *value* is correct; only
        // the read-out is confusing, so it is normalised here and left alone in the spec.
        val node = ((orbit.ascendingNodeDegrees % FULL_TURN) + FULL_TURN) % FULL_TURN
        "$kind tilt %+4.0f° node %3.0f° phase %3.0f° period %.2f days distance %.0f size %.0f%s".format(
            orbit.inclinationDegrees,
            node,
            orbit.phaseDegrees,
            days,
            orbit.distance,
            sprite?.angularSize ?: 0.0f,
            direction,
        )
    } + listOfNotNull(
        "stars ${stars.count}",
        "— an ordinary sky, so vanilla's own renderer draws this one".takeIf { isOrdinary },
    )

    companion object {
        /** Vanilla's own sun, spelled once so [drawn] and [VANILLA] cannot disagree about it. */
        private val VANILLA_SUN_BODY = CelestialBody(Orbit.VANILLA_SUN, sun(Rgba.WHITE, VANILLA_SUN_SIZE))

        /**
         * Vanilla's own moon: the same orbit as the sun, half a turn behind it, cycling its eight phases over
         * eight days — which is `moonPhase(dayTime) = dayTime / 24000 % 8`, said as a period.
         */
        private val VANILLA_MOON = CelestialBody(
            Orbit.VANILLA_SUN.copy(phaseDegrees = 180.0f),
            moon(Rgba.WHITE, VANILLA_MOON_SIZE),
            PhaseCycle(
                periodTicks = Orbit.TICKS_PER_VANILLA_DAY * PhaseCycle.VANILLA_PHASES,
                offsetTicks = 0,
                steps = Appearance.MOON_COLUMNS * Appearance.MOON_ROWS,
            ),
        )

        /** An ordinary sky: one sun on vanilla's own orbit, one moon opposite it, vanilla's star count. */
        val VANILLA = SkySpec(
            bodies = listOf(VANILLA_SUN_BODY, VANILLA_MOON),
            stars = StarField(VANILLA_STAR_COUNT, seed = 0L),
        )

        val CODEC: Codec<SkySpec> = RecordCodecBuilder.create { instance ->
            instance.group(
                CelestialBody.CODEC.listOf().fieldOf("bodies").forGetter(SkySpec::bodies),
                StarField.CODEC.fieldOf("stars").forGetter(SkySpec::stars),
            ).apply(instance, ::SkySpec)
        }

        /**
         * The sky an Age gets, drawn from its seed.
         *
         * **The writer names the character and the seed decides the specifics**, which is the division terrain
         * arrangements already use: "three suns" is the sentence, and *where* those three suns hang is the Age's
         * own. Two Ages with the same words at different seeds get different skies, which is the point.
         *
         * The counts arrive as plain integers rather than as [co.voik.agesandtheart.age.aspect.Parameter] values
         * on purpose — mapping enumerated words onto them is the Sky aspect's business, and keeping it out of
         * here is what lets this be checked offline without a vocabulary.
         *
         * **The first sun is exactly vanilla's**, and that invariant is load-bearing rather than tidy. Tier 1
         * leaves the lightmap on `DimensionType.timeOfDay`, so the world still brightens and dims on vanilla's
         * schedule; if the primary sun drifted off that schedule, noon would be bright with the sun somewhere
         * off to the side. A one-sun Age therefore looks exactly like an ordinary world, and every additional
         * body is a departure from a correct baseline. `SkyCheck` holds this.
         *
         * [spread] is how far the extra bodies wander from that first orbit, in `0.0..1.0` — 0 puts them all in
         * vanilla's plane at different phases, 1 scatters their inclinations across the sky.
         */
        fun drawn(suns: Int, moons: Int, starCount: Int, spread: Float, seed: Long): SkySpec {
            val random = XoroshiroRandomSource(seed xor SKY_SALT)
            val bodies = mutableListOf<CelestialBody>()

            repeat(suns.coerceAtLeast(0)) { index ->
                bodies += if (index == 0) {
                    VANILLA_SUN_BODY
                } else {
                    CelestialBody(
                        wanderingOrbit(random, spread, SUN_PERIOD_SPREAD),
                        sun(sunTint(random), sized(random, VANILLA_SUN_SIZE, SUN_SIZE_VARIANCE)),
                    )
                }
            }

            repeat(moons.coerceAtLeast(0)) { index ->
                if (index == 0) {
                    bodies += VANILLA_MOON
                    return@repeat
                }
                bodies += CelestialBody(
                    wanderingOrbit(random, spread, MOON_PERIOD_SPREAD),
                    moon(moonTint(random), sized(random, VANILLA_MOON_SIZE, MOON_SIZE_VARIANCE)),
                    PhaseCycle(
                        periodTicks = Orbit.TICKS_PER_VANILLA_DAY * random.nextIntBetweenInclusive(2, LONGEST_PHASE_DAYS),
                        offsetTicks = random.nextInt(Orbit.TICKS_PER_VANILLA_DAY),
                        // Vanilla's atlas has eight cells, so a cycle through it has eight steps. Held by
                        // `SkyCheck`, because a mismatch would index past the texture.
                        steps = Appearance.MOON_COLUMNS * Appearance.MOON_ROWS,
                    ),
                )
            }

            // Drawn from the Age even when it is empty, so that "no stars" and "stars we happened to draw none
            // of" cannot be confused — the count is the statement, the seed is only how it is arranged.
            val stars = StarField(starCount.coerceAtLeast(0), random.nextLong())
            return SkySpec(bodies, stars)
        }

        /**
         * An orbit that departs from vanilla's by [spread].
         *
         * **Three things scale with [spread], not one, and getting that wrong was a real bug.** Until Jonah
         * walked it, only the *inclination* was gated: at `spread 0` every body sat in a plane containing the
         * zenith, but each still drew a random **ascending node**, which rotates that plane about the vertical.
         * So "shared" produced several distinct great circles that merely happened to be untilted — visibly
         * different orbits, exactly as reported. The **period** matters for the same reason over a longer
         * timescale: bodies with unequal periods drift apart, so they could not hold the formation the word
         * promises. At `spread 0` all three now collapse onto vanilla's own orbit, and only the *phase* stays
         * random — which is what strings the bodies out along one arc like beads.
         *
         * Distance varies regardless of spread, deliberately: two bodies on exactly one radius would flicker
         * against each other on draw order, since the sky pass has no z-buffer at all (research §3.1). At
         * `spread 0` that reads as concentric circles on one path, which is the intent rather than a compromise.
         */
        private fun wanderingOrbit(random: RandomSource, spread: Float, periodSpread: Float): Orbit {
            val period = Orbit.TICKS_PER_VANILLA_DAY * (1.0f + symmetric(random) * periodSpread * spread)
            return Orbit(
                inclinationDegrees = symmetric(random) * spread * WIDEST_INCLINATION,
                ascendingNodeDegrees = Orbit.VANILLA_SUN.ascendingNodeDegrees +
                    symmetric(random) * spread * WIDEST_NODE_SWING,
                phaseDegrees = random.nextFloat() * FULL_TURN,
                periodTicks = period.toInt().coerceAtLeast(SHORTEST_PERIOD),
                // Rare, and never on the primary sun, which never reaches this function. Not gated by spread:
                // a body going the other way around a *shared* path is one of the better things this can do.
                retrograde = random.nextFloat() < RETROGRADE_CHANCE,
                distance = Orbit.VANILLA_SUN.distance * (1.0f + symmetric(random) * DISTANCE_VARIANCE),
            )
        }

        /**
         * Vanilla's sun texture, tinted. One cell, so no phases — a sun does not wane.
         */
        private fun sun(tint: Rgba, size: Float) = Appearance.Sprite(tint, size, Appearance.SUN_TEXTURE)

        /**
         * Vanilla's moon atlas, tinted, with its 4×2 grid declared so a phase can pick a cell.
         */
        private fun moon(tint: Rgba, size: Float) = Appearance.Sprite(
            tint,
            size,
            Appearance.MOON_TEXTURE,
            columns = Appearance.MOON_COLUMNS,
            rows = Appearance.MOON_ROWS,
        )

        private fun sized(random: RandomSource, base: Float, variance: Float): Float =
            base * (1.0f + symmetric(random) * variance)

        /** Warm: white through to a deep amber. */
        private fun sunTint(random: RandomSource): Rgba =
            Rgba.WHITE.lerp(EMBER, random.nextFloat())

        /** Cool: white through to a pale blue. */
        private fun moonTint(random: RandomSource): Rgba =
            Rgba.WHITE.lerp(FROST, random.nextFloat())

        /** A value in `-1.0..1.0`, so a variance reads as "either side of" rather than "up to". */
        private fun symmetric(random: RandomSource): Float = random.nextFloat() * 2.0f - 1.0f

        private const val SKY_SALT = 0x5C1E_7A5EL

        /** Vanilla's own half-extents, so the ordinary case is spelled rather than approximated. */
        const val VANILLA_SUN_SIZE = 30.0f
        const val VANILLA_MOON_SIZE = 20.0f

        /** Vanilla attempts 1500 stars and keeps most of them; ours are placed rather than rejected. */
        const val VANILLA_STAR_COUNT = 1500

        private const val FULL_TURN = 360.0f
        private const val WIDEST_INCLINATION = 85.0f

        /** Half a turn either side, so the widest spread reaches every compass heading. */
        private const val WIDEST_NODE_SWING = 180.0f
        private const val SUN_PERIOD_SPREAD = 0.4f
        private const val MOON_PERIOD_SPREAD = 0.8f
        private const val SHORTEST_PERIOD = 2000
        private const val LONGEST_PHASE_DAYS = 12
        private const val RETROGRADE_CHANCE = 0.2f
        private const val DISTANCE_VARIANCE = 0.08f
        private const val SUN_SIZE_VARIANCE = 0.35f
        private const val MOON_SIZE_VARIANCE = 0.45f

        private val EMBER = Rgba(1.0f, 0.55f, 0.25f)
        private val FROST = Rgba(0.72f, 0.82f, 1.0f)
    }
}

/**
 * The stars, as a count and an arrangement seed.
 *
 * A count of zero is "no stars", which is a thing a writer can ask for. The seed is per Age, so two Ages with the
 * same number of stars still have different constellations — the cheapest possible way to make every sky its own.
 *
 * Brightness is not here because our renderer owns it outright: `ClientLevel.getStarBrightness` is read **only**
 * by `LevelRenderer.renderSky` (research §4.3), and Tier 1 replaces that method entirely.
 */
data class StarField(val count: Int, val seed: Long) {
    companion object {
        val CODEC: Codec<StarField> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("count").forGetter(StarField::count),
                Codec.LONG.fieldOf("seed").forGetter(StarField::seed),
            ).apply(instance, ::StarField)
        }
    }
}
