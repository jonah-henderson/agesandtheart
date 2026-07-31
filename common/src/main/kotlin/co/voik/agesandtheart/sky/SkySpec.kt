package co.voik.agesandtheart.sky

import co.voik.agesandtheart.math.Rgba
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.RandomSource
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * Everything an Age's sky is, as data: its celestial bodies and its stars.
 *
 * **What crosses to the client**, and nothing here is a registry object — see [SkyPayload] for why that
 * matters, and `notes/per-age-skies-research.md` §1.
 *
 * **Derived, never stored**: [drawn] is a pure function, so an Age rebuilds the same sky on every open
 * from its recipe. The codec exists to *send* a spec, not to persist one.
 */
data class SkySpec(
    val bodies: List<CelestialBody>,
    val stars: StarField,
    /** Overcast layers, outermost last. Empty for every sky that has none, which is all but the Spire's. */
    val decks: List<CloudDeck> = emptyList(),
) {

    /**
     * Whether this is an ordinary sky — vanilla's sun, moon and stars, nothing added. The painter declines
     * to draw one, so vanilla's own `renderSunMoonAndStars` runs and an unremarkable Age gets vanilla's
     * sky exactly rather than our imitation of it.
     *
     * The star *seed* is deliberately not compared: a different arrangement of the same number of stars is
     * not something vanilla cannot draw. A star **reveal** is, so it counts — [decks] do not, because they
     * are the other Mixin's business and an Age may have them over an otherwise unremarkable sky.
     */
    val isOrdinary: Boolean
        get() {
            val bodiesAreVanillas = bodies == VANILLA.bodies
            val starsAreVanillas = stars.count == VANILLA.stars.count && stars.reveal == null
            return bodiesAreVanillas && starsAreVanillas
        }

    /**
     * This sky in a line per body, for `/age sky` — numbers and all, which §3.2 permits: it forbids
     * showing numbers to the **player**, and this is a developer instrument behind an operator permission.
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
    } + decks.map { deck ->
        "deck height %.0f drift %.3f thickness %.0f".format(deck.height, deck.driftSpeed, deck.halfThickness * 2)
    } + listOfNotNull(
        "stars ${stars.count}",
        stars.reveal?.let { "— hidden below %.0f, fully shown above %.0f".format(it.hiddenBelow, it.fullyShownAbove) },
        "— an ordinary sky, so vanilla draws this one and we stay out of it".takeIf { isOrdinary },
    )

    companion object {
        /** Vanilla's own sun, spelled once so [drawn] and [VANILLA] cannot disagree about it. */
        private val VANILLA_SUN_BODY = CelestialBody(Orbit.VANILLA_SUN, sun(Rgba.WHITE, VANILLA_SUN_SIZE))

        /**
         * Vanilla's own moon: the same orbit as the sun, half a turn behind it, cycling its eight phases over
         * eight days — which is `moonPhase(dayTime) = dayTime / 24000 % 8`, said as a period.
         */
        private val VANILLA_MOON = CelestialBody(
            Orbit.VANILLA_MOON,
            moon(Rgba.WHITE, VANILLA_MOON_SIZE),
            PhaseCycle(
                periodTicks = Orbit.TICKS_PER_VANILLA_DAY * PhaseCycle.VANILLA_PHASES,
                offsetTicks = 0,
                steps = Appearance.MOON_SHAPES.size,
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
                CloudDeck.CODEC.listOf().optionalFieldOf("decks", emptyList()).forGetter(SkySpec::decks),
            ).apply(instance, ::SkySpec)
        }

        /**
         * The sky an Age gets, drawn from its seed — **the writer names the character and the seed decides
         * the specifics**, so two Ages with the same words at different seeds differ.
         *
         * Counts arrive as plain integers rather than [co.voik.agesandtheart.age.aspect.Parameter] values,
         * which is what lets this be checked offline without a vocabulary.
         *
         * **The first sun is exactly vanilla's**, and that is load-bearing: the lightmap still runs on
         * `DimensionType.timeOfDay`, so a primary sun off that schedule would leave noon bright with the
         * sun to one side. `SkyCheck` holds it.
         *
         * [spread] is how far the extra bodies wander from that first orbit — 0 puts them all in vanilla's
         * plane at different phases, 1 scatters their inclinations across the sky.
         */
        fun drawn(suns: Int, moons: Int, starCount: Int, spread: Float, seed: Long): SkySpec {
            val random = XoroshiroRandomSource(seed xor SKY_SALT)
            val bodies = mutableListOf<CelestialBody>()

            repeat(suns.coerceAtLeast(0)) { index ->
                bodies += if (index == 0) {
                    VANILLA_SUN_BODY
                } else {
                    CelestialBody(
                        wanderingOrbit(random, spread, SUN_PERIOD_SPREAD, SUN_BAND),
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
                    wanderingOrbit(random, spread, MOON_PERIOD_SPREAD, MOON_BAND),
                    moon(moonTint(random), sized(random, VANILLA_MOON_SIZE, MOON_SIZE_VARIANCE)),
                    PhaseCycle(
                        periodTicks = Orbit.TICKS_PER_VANILLA_DAY * random.nextIntBetweenInclusive(2, LONGEST_PHASE_DAYS),
                        offsetTicks = random.nextInt(Orbit.TICKS_PER_VANILLA_DAY),
                        // Vanilla has eight moon shapes, so a cycle through them has eight steps. Held by
                        // `SkyCheck`, because a mismatch would index past the last sprite.
                        steps = Appearance.MOON_SHAPES.size,
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
         * **Inclination, ascending node and period all scale with it, not inclination alone.** A random
         * ascending node rotates an untilted plane about the vertical, so gating only the inclination gave
         * "shared" several visibly distinct great circles; unequal periods drift apart over a longer
         * timescale. At `spread 0` all three collapse onto vanilla's orbit and only the *phase* stays
         * random, which strings the bodies along one arc like beads.
         *
         * [band] is the range of radii this kind of body may take. Distance varies regardless of spread,
         * because two bodies at one radius flicker against each other on draw order — the sky pass has no
         * z-buffer at all (research §3.1).
         */
        private fun wanderingOrbit(
            random: RandomSource,
            spread: Float,
            periodSpread: Float,
            band: ClosedFloatingPointRange<Float>,
        ): Orbit {
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
                distance = band.start + random.nextFloat() * (band.endInclusive - band.start),
            )
        }

        /**
         * Vanilla's sun sprite, tinted. One shape, so no phases — a sun does not wane.
         */
        private fun sun(tint: Rgba, size: Float) = Appearance.Sprite(tint, size, Appearance.SUN_SHAPES)

        /**
         * Vanilla's eight moon shapes, tinted, so a phase can pick one.
         */
        private fun moon(tint: Rgba, size: Float) = Appearance.Sprite(tint, size, Appearance.MOON_SHAPES)

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
        /**
         * Moons orbit inside every sun and suns outside every moon, so a moon always passes in front.
         * Bodies are drawn farthest first, which is what makes that ordering mean anything — and it is
         * where an eclipse would hook in, ordering being the only tool the depthless sky pass has.
         */
        private val SUN_BAND = 100.0f..108.0f
        private val MOON_BAND = 88.0f..96.0f
        private const val SUN_SIZE_VARIANCE = 0.35f
        private const val MOON_SIZE_VARIANCE = 0.45f

        private val EMBER = Rgba(1.0f, 0.55f, 0.25f)
        private val FROST = Rgba(0.72f, 0.82f, 1.0f)
    }
}

/**
 * The stars, as a count and an arrangement seed. Zero is "no stars", which a writer can ask for; the seed
 * is per Age, so two Ages with the same number still get different constellations.
 *
 * Brightness is absent because our renderer owns it outright — `ClientLevel.getStarBrightness` is read
 * only by `LevelRenderer.renderSky` (research §4.3), which we replace entirely.
 */
data class StarField(
    val count: Int,
    val seed: Long,
    /**
     * The height band the stars fade in across, or null for a field that is simply always there.
     *
     * Present only where a sky hides its stars behind something — the Spire's, above its upper deck.
     */
    val reveal: StarReveal? = null,
) {
    companion object {
        val CODEC: Codec<StarField> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("count").forGetter(StarField::count),
                Codec.LONG.fieldOf("seed").forGetter(StarField::seed),
                StarReveal.CODEC.optionalFieldOf("reveal")
                    .forGetter { field -> java.util.Optional.ofNullable(field.reveal) },
            ).apply(instance) { count, seed, reveal -> StarField(count, seed, reveal.orElse(null)) }
        }
    }
}
