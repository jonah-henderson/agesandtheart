package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.ephemeris.sky.Appearance
import co.voik.ephemeris.sky.CelestialBody
import co.voik.ephemeris.sky.Look
import co.voik.ephemeris.sky.Orbit
import co.voik.ephemeris.sky.SkySpec
import co.voik.agesandtheart.sky.SpireSky
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

/**
 * What is overhead. The presets are the atmosphere; the parameters are what hangs in it, resolved into a
 * [SkySpec] that is *sent to the client* rather than baked into a dimension type — a `DimensionType`
 * composed per Age cannot be encoded in the join packet at all (`notes/per-age-skies-research.md`).
 *
 * A sky's bodies are **counted** and its stars are a density, per §3.2's rule about which numbers may reach
 * a writer: two moons is a thing to say about a sky, and fifteen hundred stars is not. [SkySpec.drawn] takes
 * it from there.
 */
enum class Sky(override val key: String) : AspectPreset {
    /** An ordinary sky — vanilla's own air and clouds. */
    PLAIN("plain"),

    /**
     * A troubled sky — **which has no look of its own yet, so it currently renders as [PLAIN]**. A known
     * gap, not a silent drop: the choice is still recorded in the recipe and is still what `gloomy` and
     * `dramatic` resolve to. It gets its look back when clouds and atmosphere become authorable.
     */
    STORM("storm"),

    /**
     * The Spire's own sky: two roiling cloud decks and stars that appear only above them. Written down in
     * [SpireSky] rather than resolved.
     *
     * Deliberately out of the resolver's reach — no word chooses it and `preset_tags/sky.json` omits it,
     * so it is reachable only by a preset that pins it.
     */
    SPIRE("spire"),
    ;

    override val aspect = Aspect.SKY

    /** Only [SPIRE] is unaskable, and being so is the point of it — see [AspectPreset.askableInASentence]. */
    override val askableInASentence: Boolean get() = this != SPIRE

    /**
     * **None.** The bodies overhead are the sun's, the moon's and the stars' — their own aspects — and the
     * vault's own dials belong to the aspect rather than to any one of its presets. A preset declaring them
     * too made every one of those names owned twice, which `DerivedAspectsCheck` refuses outright.
     */
    override val parameters: List<Parameter> get() = emptyList()

    /**
     * The look this preset paints under whatever the sentence asked for, or [Look.NOTHING] where it has
     * none of its own.
     *
     * **This is what a dimension type used to carry.** The Spire wore one of its own for five colours, and
     * `Atmosphere` says all five better — so the palette moved here, where it can be laid *under* a
     * writer's own instead of competing with it for a pre-authored file.
     */
    fun look(): Look = if (this == SPIRE) SpireSky.LOOK else Look.NOTHING

    /**
     * The sky this preset asks for, drawn from the Age's [seed].
     *
     * [SPIRE] ignores both, and that is the whole of what makes it bespoke: its sky is written down rather
     * than resolved, so no seed and no option moves it.
     */
    fun specFor(parts: AgeParts, seed: Long): SkySpec {
        if (this == SPIRE) return SpireSky.SPEC
        // **A world shut overhead has nothing overhead**, and that is a fact about the world rather than
        // something a writer has to remember to say. Silencing the sun is not enough: a moon and a full
        // field of stars were still drawn through the ceiling of an infernal Age, because the cast falls
        // back to vanilla's one moon wherever no clause described a body and `orbiting` was never set.
        if (isRoofed(parts.optionsFor(Aspect.SKY))) return SkySpec.drawn(NONE, NONE, NONE, ORDINARY_SPREAD.toFloat(), seed)
        // **Assembled from three aspects**, which is what the split made explicit: the suns, the moons and
        // the star field are each their own part of the world, and a spec is where they meet.
        val sun = parts.optionsFor(Aspect.SUN)
        // **The cast is what the book described, or the template's where it described none.** A sun is
        // brought into being by a clause about it, so the number of bodies is the number of clauses —
        // there is no count to write and no second spelling for "two suns".
        val suns = if (sun.of(SHINING) == NEVER) NONE
            else parts.membersIn(Aspect.SUN).takeIf { it > NONE } ?: VANILLAS_ONE
        val moons = if (parts.optionsFor(Aspect.MOON).of(ORBITING) == NEVER) NONE
            else parts.membersIn(Aspect.MOON).takeIf { it > NONE } ?: VANILLAS_ONE
        val drawn = SkySpec.drawn(
            suns = suns,
            moons = moons,
            starCount = starsAt(parts.optionsFor(Aspect.STARS).steer(STARS, seed)),
            spread = ORDINARY_SPREAD.toFloat(),
            seed = seed,
        )
        // **Which sun this is, counted rather than read off where it sits.** `SkySpec.drawn` happens to
        // put the suns first, and arithmetic against that would hand a moon a sun's description the day it
        // stopped — a change in Ephemeris that nothing here could have failed on.
        fun among(at: Int, isASun: Boolean) = drawn.bodies.take(at).count { (it.phase == null) == isASun }
        val told = drawn.bodies.mapIndexed { at, body ->
            val isASun = body.phase == null
            val aspect = if (isASun) Aspect.SUN else Aspect.MOON
            described(body, parts.optionsFor(aspect, among(at, isASun)), seed)
        }
        return drawn.copy(bodies = told)
    }

    /**
     * One body as **its own clause** described it, which is the whole point of minting (world model §2).
     *
     * `SkySpec.drawn` builds a sky out of what is true of all of them, which is everything a count could
     * ever say; this is the pass that lets the second sun be blue where the first is red. A body nobody
     * said anything about keeps exactly what was drawn, so an unremarkable sky stays vanilla's.
     */
    private fun described(body: CelestialBody, own: Options, seed: Long): CelestialBody {
        val sized = own.steer(SUNSIZE, seed)?.let(::sunSizeAt)
        val tinted = Colour.named(own.of(SUNCOLOUR))?.saturated(SUN_IS_LOOKED_AT)
        val rising = bearingOf(own.of(RISING))
        val sprite = body.appearance as? Appearance.Sprite
        val appearance = when {
            sprite == null || (sized == null && tinted == null) -> body.appearance
            else -> sprite.copy(tint = tinted ?: sprite.tint, angularSize = sized ?: sprite.angularSize)
        }
        // Aimed from the path it already has, so a spare body keeps the wander the draw gave it and only
        // the horizon it comes up over moves.
        val path = rising?.let { Orbit.risingAt(it, body.path as? Orbit ?: Orbit.VANILLA_SUN) } ?: body.path
        return body.copy(appearance = appearance, path = path)
    }

    override fun getSerializedName(): String = key

    companion object {
        /**
         * Whether this world goes round anything at all — **the one thing minting cannot say.**
         *
         * Every other fact about the suns is written by describing one, and the number of them is the
         * number of clauses. Nought is the exception: there is no clause that mints no body, so an empty
         * sky needs a word of its own, and `sunless` is it.
         */
        val SHINING = Parameter("shining", Atmosphere.AS_EVER, NEVER)

        /** The moon's own, and separate because one name may be owned by one aspect (`DerivedAspectsCheck`). */
        val ORBITING = Parameter("orbiting", Atmosphere.AS_EVER, NEVER)

        /** How thick the stars lie: none at the bottom of the axis, [DENSEST_STARS] times vanilla's at the top. */
        val STARS = Parameter.ranged("stars")

        /**
         * How large the suns are, against vanilla's — [LARGEST_SUN] times it at the top of the axis.
         *
         * **The renderer could always draw this and nothing could say it.** `Appearance.Sprite` has carried
         * an `angularSize` since the sky was built, and [SkySpec.drawn] already varied it for the *extra*
         * suns; what was missing was a writer's way to ask, and a way for the ask to reach the first one.
         */
        val SUNSIZE = Parameter.ranged("sunsize")

        /**
         * What colour the suns burn, or [Atmosphere.AS_EVER] for vanilla's white.
         *
         * Reaches **every** sun including the first, which is what separates it from the tint
         * [SkySpec.drawn] already draws for the others: that one spreads a sky's extra bodies apart, and
         * this one is a statement about the star this world goes round.
         */
        val SUNCOLOUR = colour("suncolour")

        /**
         * The compass, in the bearings `Orbit.risingAt` reads — due east is 90, which is where vanilla's
         * own sun comes up.
         *
         * Eight points and no more: the four cardinals and the four between them are what a person points
         * at, and a writer who wants a sun at 22.5° is asking a question §3.2 keeps away from them.
         */
        private val BEARINGS: Map<String, Float> = linkedMapOf(
            "north" to 0.0f,
            "northeast" to 45.0f,
            "east" to 90.0f,
            "southeast" to 135.0f,
            "south" to 180.0f,
            "southwest" to 225.0f,
            "west" to 270.0f,
            "northwest" to 315.0f,
        )

        /** The bearing [named] rises at, or null where nobody said — which leaves the path as it was. */
        fun bearingOf(named: String): Float? = BEARINGS[named]

        /**
         * Which horizon a body comes up over — the eight points of the compass, and the one thing about a
         * sun a writer is likeliest to want to say.
         *
         * Named rather than measured, which is §3.2 at its least arguable: "north-rising" is a thing a
         * person says about a sun where ninety degrees of ascending node is a fact about our arithmetic.
         * `Orbit.risingAt` does the conversion and owns the sign trap in it.
         *
         * **The sun's and the moon's alike**, which is one knob owned by two aspects: the word means the
         * same thing about either body and the clause it was laid in picks which one it is about.
         */
        val RISING = Parameter("rising", listOf(Atmosphere.AS_EVER) + BEARINGS.keys)

        /**
         * Whether the world is **sealed overhead** — a physical fact about the Age, and the one a writer
         * may argue with.
         *
         * The game rules that follow it are not writable: `has_ceiling` and `has_skylight` are derived from
         * this and from whether anything shines (see [dimensionType]). So a writer with enough words can
         * open a nether-shaped world to the sky and the rules go with them, rather than being a second
         * thing to remember to say.
         *
         * **It places no blocks yet**, and the name is chosen against that day rather than for today: the
         * ceiling a sealed world has comes from whatever generates it, and until something does this says
         * what the Age *is* without yet building it. `has_ceiling` never built one either — read against
         * 26.1.2, four things call it and none places a block.
         */
        val SEALED = Parameter("sealed", Atmosphere.AS_EVER, ALWAYS)

        /** Whether the Age is shut overhead. */
        fun isRoofed(sky: Options): Boolean = sky.of(SEALED) == ALWAYS

        /**
         * Whether nothing lights the Age from above — **one fact with three readers**: the dimension type
         * it wears, the skylight the game gives it, and how its vault is painted.
         *
         * A world is dark because it is shut overhead *or* because nothing shines on it, and the two must
         * be asked together. A reader that knows only the seal paints a blue sky with clouds in it over a
         * world the game is holding pitch dark, which is the walked bug of 2026-08-05 arriving a second
         * time by a second route.
         */
        fun isLightless(sky: Options, sun: Options): Boolean = isRoofed(sky) || sun.of(SHINING) == NEVER

        /**
         * The pre-authored type an Age wearing these facts needs — **derived, never written** (Jonah,
         * 2026-08-12).
         *
         * `has_ceiling` and `has_skylight` are *game rules*, and a writer never sets one: they follow from
         * the physical facts of the Age. What is sealed overhead has a ceiling; what is sealed overhead or
         * goes round nothing has no skylight. So a writer with enough words can open a nether-shaped world
         * to the sky, and the rules follow them there rather than having to be argued with separately.
         *
         * **Four files, and that is the whole reason only two switches are derived.** A composed
         * `DimensionType` cannot be encoded in the join packet, so every combination has to be a JSON we
         * ship, and each further switch doubles them. The band of world is deliberately not among them: all
         * four declare [co.voik.agesandtheart.worldgen.VerticalWindow.DEFAULT], so no sky moves an Age's
         * floor.
         */
        fun dimensionType(sky: Options, sun: Options): Identifier =
            // **Three, not four.** A world sealed overhead cannot also let the sky reach the ground, so
            // roofed-and-lit is a combination the facts cannot produce and the file for it is gone.
            when {
                isRoofed(sky) -> AgeGeneration.AGE_LIGHTLESS_ROOFED_DIMENSION_TYPE
                isLightless(sky, sun) -> AgeGeneration.AGE_LIGHTLESS_DIMENSION_TYPE
                else -> AgeGeneration.AGE_DIMENSION_TYPE
            }

        const val NEVER = "never"

        /** What [SEALED] says when the world is shut overhead. */
        const val ALWAYS = "always"

        private const val NONE = 0

        /** What a sky nobody wrote a body into keeps — the overworld's own, which is the template. */
        private const val VANILLAS_ONE = 1

        private const val DENSEST_STARS = 3

        /** Vanilla's own star count, which is a third of the way up the axis. */
        private const val ORDINARY_STARS = 1.0 / DENSEST_STARS

        /**
         * How far the extra bodies wander off the first one's path, which reads as a sky rather than as a
         * diagram.
         *
         * **No longer a knob**, and it will not become one again: a spread is a fact about the *arrangement*
         * of a count of bodies, and once each body is written on its own page and says where it rises there
         * is nothing left for it to mean.
         */
        private const val ORDINARY_SPREAD = 0.45

        private fun starsAt(density: Double?): Int {
            val fraction = density?.let(Span.NATURAL::fractionOf) ?: ORDINARY_STARS
            return (fraction * DENSEST_STARS * SkySpec.VANILLA_STAR_COUNT).roundToInt()
        }

        /**
         * Vanilla's sun where the axis is unsaid, so an ordinary sky is untouched — which `SkyCheck` holds.
         *
         * Never smaller than vanilla's: the bottom of the axis is an ordinary sun rather than a pinprick,
         * because "small sun" is a distant one, and distance is the path's business rather than the size's.
         */
        private fun sunSizeAt(largeness: Double?): Float {
            val fraction = largeness?.let(Span.NATURAL::fractionOf) ?: return SkySpec.VANILLA_SUN_SIZE
            return SkySpec.VANILLA_SUN_SIZE * (1.0 + fraction * (LARGEST_SUN - 1.0)).toFloat()
        }

        /** How many times vanilla's own sun the top of [SUNSIZE] reaches — filling a good part of the sky. */
        private const val LARGEST_SUN = 4.0

        /**
         * How far a named colour is pushed from its own grey before a sun wears it (Jonah, 2026-08-08).
         *
         * `Colour`'s palette was chosen for things the eye looks *past* — a sky, a fog, a tint on light —
         * so its red is a soft one. On a sun it read as a tint laid over white rather than as the colour
         * the star burns, which is the one thing a sun's colour has to say.
         */
        private const val SUN_IS_LOOKED_AT = 1.5f

        private fun colour(name: String) = Parameter(name, listOf(Atmosphere.AS_EVER) + Colour.ALL)

    }
}
