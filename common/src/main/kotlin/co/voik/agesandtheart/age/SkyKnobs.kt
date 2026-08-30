package co.voik.agesandtheart.age

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Aurora
import co.voik.ephemeris.sky.AuroraGround
import co.voik.ephemeris.sky.CelestialBody
import co.voik.ephemeris.sky.CloudDeck
import co.voik.ephemeris.sky.Daylight
import co.voik.ephemeris.sky.HorizonGlow
import co.voik.ephemeris.sky.LevelLook
import co.voik.ephemeris.sky.Motion
import co.voik.ephemeris.sky.Motions
import co.voik.ephemeris.sky.Orbit
import co.voik.ephemeris.sky.Pacing
import co.voik.ephemeris.sky.SkyRules
import co.voik.ephemeris.sky.SkySpec
import net.minecraft.core.Direction

/**
 * The knobs `/age sky` offers over and above the Art's own words — **an instrument, not a vocabulary**.
 *
 * Ephemeris can do a great deal the Art has no word for yet: paths that are not circles, suns that never
 * set, orbits that swell, sprites that roll, and rules about which sun decides the day. None of it is
 * reachable from a sentence, and something unreachable is something unwalked — so these turn each of them on
 * for one preview.
 *
 * **Nothing here is the Art's design.** Every one of these is a lever on the library, named after what it
 * does rather than after anything a writer would say, and it is applied to whatever the words already
 * resolved to. When the Art grows words for any of this, the word decides and the knob stays a knob.
 */
object SkyKnobs {

    /** What one knob is called and what it accepts, so a mistyped one can say what it should have been. */
    private val OFFERED: Map<String, List<String>> = linkedMapOf(
        "lift" to listOf("<degrees>", "e.g. 90 for a sun on the horizon all day"),
        "swell" to listOf("<0..0.9>", "how far the orbit's radius varies"),
        "path" to Shape.entries.map { it.key },
        "glow" to HorizonGlow.entries.map { it.name.lowercase() },
        "daylight" to Daylight.entries.map { it.name.lowercase() },
        "deck" to Deck.entries.map { it.key },
        "air" to listOf("<0..2>", "how thick the air a body is seen through is; 0 is airless"),
        "aurora" to Curtain.entries.map { it.key },
        "bearing" to listOf("<degrees>", "which way the aurora's band crosses, clockwise from north"),
    )

    /** Whether [name] is one of ours rather than one of the Art's aspects. */
    fun offers(name: String): Boolean = name in OFFERED

    fun describeOffered(): String = OFFERED.entries.joinToString("; ") { (name, values) ->
        "$name=${values.joinToString("|")}"
    }

    /**
     * [spec] with the knobs in [tokens] turned, or a message saying which one could not be read.
     *
     * Applied after the words rather than instead of them, so a preview is still the Age's own sky with one
     * thing changed — which is what makes it possible to see what the knob did.
     */
    fun applyTo(spec: SkySpec, tokens: List<String>): Result<LevelLook> {
        var bodies = spec.bodies
        var decks = spec.decks
        var aurora = spec.aurora
        var rules = SkyRules.DEFAULT

        for (token in tokens) {
            val name = token.substringBefore('=')
            val value = token.substringAfter('=', missingDelimiterValue = "")
            val failure = "$name=$value — try ${OFFERED[name]?.joinToString("|")}"
            when (name) {
                "lift" -> {
                    val degrees = value.toFloatOrNull() ?: return Result.failure(IllegalArgumentException(failure))
                    bodies = bodies.map { it.onCircle { circle -> circle.copy(liftDegrees = degrees) } }
                }

                "swell" -> {
                    val amount = value.toFloatOrNull() ?: return Result.failure(IllegalArgumentException(failure))
                    bodies = bodies.map { it.onCircle { circle -> circle.copy(swell = amount) } }
                }

                "path" -> {
                    val shape = Shape.entries.firstOrNull { it.key == value }
                        ?: return Result.failure(IllegalArgumentException(failure))
                    bodies = bodies.mapIndexed { index, body -> body.copy(path = shape.pathFor(index, body)) }
                }

                "glow" -> {
                    val glow = HorizonGlow.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                        ?: return Result.failure(IllegalArgumentException(failure))
                    rules = rules.copy(glow = glow)
                }

                "daylight" -> {
                    val daylight = Daylight.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                        ?: return Result.failure(IllegalArgumentException(failure))
                    rules = rules.copy(daylight = daylight)
                }

                "air" -> {
                    val thickness = value.toFloatOrNull() ?: return Result.failure(IllegalArgumentException(failure))
                    rules = rules.copy(airThickness = thickness)
                }

                "deck" -> {
                    val deck = Deck.entries.firstOrNull { it.key == value }
                        ?: return Result.failure(IllegalArgumentException(failure))
                    decks = deck.decks()
                }

                "aurora" -> {
                    val curtain = Curtain.entries.firstOrNull { it.key == value }
                        ?: return Result.failure(IllegalArgumentException(failure))
                    aurora = curtain.aurora()
                }

                // **After `aurora=`, or it has nothing to turn.** Left as its own knob rather than folded
                // into the curtains, because which way a band crosses is the one thing about it you want to
                // move while standing under it.
                "bearing" -> {
                    val degrees = value.toFloatOrNull() ?: return Result.failure(IllegalArgumentException(failure))
                    aurora = (aurora ?: Curtain.ORDINARY.aurora())?.copy(bearingDegrees = degrees)
                }

                else -> return Result.failure(IllegalArgumentException("$name — no such knob"))
            }
        }
        return Result.success(LevelLook(spec.copy(bodies = bodies, decks = decks, aurora = aurora), rules = rules))
    }

    /**
     * [change] applied to this body's path where it is a circle, and the body untouched where it is not.
     *
     * Silent rather than refusing, because the knobs compose: `path=epicycle lift=40` is a reasonable thing
     * to type and the lift simply has nothing to act on once the path is a stack.
     */
    private fun CelestialBody.onCircle(change: (Orbit) -> Orbit): CelestialBody =
        (path as? Orbit)?.let { copy(path = change(it)) } ?: this

    /** The demonstration paths, which exist to be looked at rather than to be written into an Age. */
    private enum class Shape(val key: String) {
        /** Whatever the words already resolved to. */
        CIRCLE("circle") {
            override fun pathFor(index: Int, body: CelestialBody) = body.path
        },

        /**
         * A body circling at a constant height, never setting — the midnight sun.
         *
         * **Each keeps its own place around the circle.** Dropping the phase put every body on one
         * trajectory, so a moon trailed its sun a few degrees overhead like a sidecar, which reads as a
         * rendering fault rather than as the demonstration it is.
         */
        POLAR("polar") {
            override fun pathFor(index: Int, body: CelestialBody) =
                Orbit.VANILLA_SUN.copy(
                    inclinationDegrees = 90.0f,
                    liftDegrees = 25.0f + index * 15.0f,
                    distance = body.path.distance,
                    phaseDegrees = (body.path as? Orbit)?.phaseDegrees ?: (index * 90.0f),
                )
        },

        /** A second sweep across the first, so the body wanders the whole compass rather than one arc. */
        EPICYCLE("epicycle") {
            override fun pathFor(index: Int, body: CelestialBody) = Motions(
                listOf(
                    Motion.Turn(Direction.Axis.Y, Orbit.VANILLAS_NODE),
                    Motion.Sweep(Direction.Axis.X, Orbit.TICKS_PER_VANILLA_DAY, pacing = Pacing.EVEN),
                    Motion.Sweep(
                        Direction.Axis.Z,
                        Orbit.TICKS_PER_VANILLA_DAY / (2 + index),
                        pacing = Pacing.EVEN,
                    ),
                ),
                body.path.distance,
            )
        },

        /** A sweep with a wobble across it at twice the rate — the analemma. */
        FIGURE_EIGHT("figure8") {
            override fun pathFor(index: Int, body: CelestialBody) = Motions(
                listOf(
                    Motion.Turn(Direction.Axis.Y, Orbit.VANILLAS_NODE),
                    Motion.Sweep(Direction.Axis.X, Orbit.TICKS_PER_VANILLA_DAY, pacing = Pacing.EVEN),
                    Motion.Oscillate(
                        Direction.Axis.Z,
                        amplitudeDegrees = 25.0f,
                        period = Orbit.TICKS_PER_VANILLA_DAY / 2,
                        phaseDegrees = index * 40.0f,
                    ),
                ),
                body.path.distance,
            )
        },
        ;

        abstract fun pathFor(index: Int, body: CelestialBody): co.voik.ephemeris.sky.CelestialPath
    }

    /**
     * The curtains a preview can hang overhead — **every night, so there is something to look at**.
     *
     * The Art's own aurorae come on a share of nights and only where the ground is cold, which is right in
     * play and useless for tuning one: an instrument you have to wait three nights and walk to a glacier for
     * is an instrument nobody uses. So every one of these is `frequency = 1`, and the ground rule is what a
     * walk of the *Art's* words is for.
     */
    private enum class Curtain(val key: String) {
        NONE("none") {
            override fun aurora(): Aurora? = null
        },

        /** What an aurora nobody described looks like: a red crown, a green body, a violet hem. */
        ORDINARY("ordinary") {
            override fun aurora() = Aurora(frequency = EVERY_NIGHT)
        },

        /** One colour, which is the other common case and the one that shows the ramp is not load-bearing. */
        PLAIN("plain") {
            override fun aurora() = Aurora(colours = listOf(GREEN), frequency = EVERY_NIGHT, curtains = 1)
        },

        /** Six, in an order nature would never take — which is what a writer is allowed to ask for. */
        BANDED("banded") {
            override fun aurora() = Aurora(
                colours = listOf(VIOLET, BLUE, GREEN, YELLOW, ORANGE, RED),
                frequency = EVERY_NIGHT,
            )
        },

        /**
         * The far ends of every dial at once, for finding out what breaks before a writer does.
         *
         * **Its colours are a fixture, not a sample.** Red, white and blue are three stops nothing would
         * ever resolve to, chosen so the ramp's two ends are unmistakable at a glance — which means this is
         * the one curtain here that can never tell you what an Age's own looks like. Reach for `ordinary`
         * or `/age aurora now` for that (Jonah asked why it was always the same colours, 2026-08-30).
         */
        EXTREME("extreme") {
            override fun aurora() = Aurora(
                colours = listOf(RED, WHITE, BLUE),
                glow = 2.0f,
                breadth = 1.0f,
                height = 1.0f,
                frequency = EVERY_NIGHT,
                curtains = Aurora.MOST_CURTAINS,
            )
        },

        /** As little as an aurora can be and still be one, which is where a hem reads or does not. */
        FAINT("faint") {
            override fun aurora() =
                Aurora(glow = 0.3f, breadth = 0.25f, height = 0.2f, frequency = EVERY_NIGHT, curtains = 1)
        },

        /**
         * The only one that asks about the ground — an ordinary curtain held to the snow line.
         *
         * Every other curtain here is deliberately seen from anywhere, so that tuning what one *looks* like
         * does not also mean standing somewhere cold. This is the one for walking the rule itself: hang it,
         * then walk out of the ice and back in.
         */
        POLAR("polar") {
            override fun aurora() = Aurora(frequency = EVERY_NIGHT, ground = AuroraGround.WHERE_IT_SNOWS)
        },
        ;

        abstract fun aurora(): Aurora?

        companion object {
            private const val EVERY_NIGHT = 1.0f

            private val RED = Rgba(0.90f, 0.20f, 0.25f)
            private val ORANGE = Rgba(0.95f, 0.55f, 0.15f)
            private val YELLOW = Rgba(0.95f, 0.90f, 0.30f)
            private val GREEN = Rgba(0.25f, 0.95f, 0.55f)
            private val BLUE = Rgba(0.20f, 0.55f, 0.95f)
            private val VIOLET = Rgba(0.55f, 0.30f, 0.90f)
            private val WHITE = Rgba(0.95f, 0.95f, 1.0f)
        }
    }

    /** The cloud decks a preview can put overhead, for looking at what a texture does. */
    private enum class Deck(val key: String) {
        NONE("none") {
            override fun decks(): List<CloudDeck> = emptyList()
        },

        /** Cut from vanilla's own picture — broken cloud with sky between, at a height of our choosing. */
        VANILLA("vanilla") {
            override fun decks(): List<CloudDeck> = listOf(
                CloudDeck(DEMONSTRATION_HEIGHT, PALE, BRIGHT, driftSpeed = 0.03f),
            )
        },

        /** An unbroken ceiling, which is what the Spire wears. */
        SOLID("solid") {
            override fun decks(): List<CloudDeck> = listOf(
                CloudDeck.solid(DEMONSTRATION_HEIGHT, PALE, BRIGHT, driftSpeed = 0.03f),
            )
        },

        /** Both, so the two are side by side and the difference is unarguable. */
        BOTH("both") {
            override fun decks(): List<CloudDeck> = listOf(
                CloudDeck.solid(DEMONSTRATION_HEIGHT + 40.0, DIM, PALE, driftSpeed = 0.015f),
                CloudDeck(DEMONSTRATION_HEIGHT, PALE, BRIGHT, driftSpeed = 0.03f),
            )
        },
        ;

        abstract fun decks(): List<CloudDeck>

        companion object {
            /** Well above the ground and well below the build limit, so both sides of it are reachable. */
            private const val DEMONSTRATION_HEIGHT = 150.0

            private val DIM = Rgba(0.18f, 0.19f, 0.24f)
            private val PALE = Rgba(0.55f, 0.57f, 0.62f)
            private val BRIGHT = Rgba(0.88f, 0.90f, 0.94f)
        }
    }
}
