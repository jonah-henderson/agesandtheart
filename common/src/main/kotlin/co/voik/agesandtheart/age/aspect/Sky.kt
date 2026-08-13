package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.ephemeris.sky.Look
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
    fun specFor(asked: (Aspect) -> Options, seed: Long, cast: (Aspect) -> Int = { 0 }): SkySpec {
        if (this == SPIRE) return SpireSky.SPEC
        // **Assembled from three aspects**, which is what the split made explicit: the suns, the moons and
        // the star field are each their own part of the world, and a spec is where they meet. One `Options`
        // bag reached all of them while they were one aspect, and a reader that quietly answered the wrong
        // one would have shown up as a sky missing its stars.
        val sun = asked(Aspect.SUN)
        val stars = asked(Aspect.STARS)
        // **The cast is what the book described, or the template's where it described none.** A sun is
        // brought into being by a clause about it, so the number of bodies is the number of clauses —
        // there is no count to write and no second spelling for "two suns".
        val suns = if (sun.of(SHINING) == NEVER) NONE else cast(Aspect.SUN).takeIf { it > NONE } ?: VANILLAS_ONE
        val moons = if (asked(Aspect.MOON).of(ORBITING) == NEVER) NONE
            else cast(Aspect.MOON).takeIf { it > NONE } ?: VANILLAS_ONE
        return SkySpec.drawn(
            suns = suns,
            moons = moons,
            starCount = starsAt(stars.steer(STARS, seed)),
            spread = ORDINARY_SPREAD.toFloat(),
            sunSize = sunSizeAt(sun.steer(SUNSIZE, seed)),
            sunColour = Colour.named(sun.of(SUNCOLOUR))?.saturated(SUN_IS_LOOKED_AT),
            seed = seed,
        )
    }

    override fun getSerializedName(): String = key

    companion object {
        /**
         * **Left as they are, the four must draw exactly [SkySpec.VANILLA]**, or an unremarkable Age stops
         * keeping vanilla's own sky. `SkyCheck` holds it; moving an ordinary value would otherwise break it
         * silently.
         */
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
         * Whether the sky reaches the ground at all — the dimension type's `has_skylight`, and **not**
         * a dimmer that no longer exists. This one stops skylight propagating: it is dark in the
         * open at noon, monsters spawn on the surface, and nothing that needs sky grows.
         */
        val SKYLIGHT = Parameter("skylight", Atmosphere.AS_EVER, "none")

        /**
         * Whether the world is treated as roofed — the dimension type's `has_ceiling`, and **it builds no
         * roof**. Checked against 26.1.2 rather than remembered: four things read it, and none of them
         * places a block. It never rains or snows (`Level.canHaveWeather`), maps go static
         * (`MapItem.update`), mobs stop spawning off the surface heightmap and so spawn at every depth
         * (`NaturalSpawner.getTopNonCollidingPos`), and respawn searches differently
         * (`PlayerSpawnFinder`). Bedrock over the nether is its *chunk generator's* surface rule and has
         * never been this flag.
         *
         * The things this used to do moved out in 26.1: `bed_rule` and `respawn_anchor_works` are
         * environment attributes now, and the Age types set them directly.
         *
         * Named for what a writer sees rather than for vanilla's key, because `ceiling` is already how high
         * the *clouds* sit (`Atmosphere.CEILING`) and one `sets` map carries both.
         */
        val ROOF = Parameter("roof", Atmosphere.AS_EVER, "always")

        /**
         * The pre-authored type an Age wearing these dials needs.
         *
         * **Four files, and that is the whole reason only two switches are here** (Jonah, 2026-08-04). A
         * composed `DimensionType` cannot be encoded in the join packet, so every combination has to be a
         * JSON we ship, and each further switch doubles them. The band of world is deliberately not among
         * them: all four declare [co.voik.agesandtheart.worldgen.VerticalWindow.DEFAULT], so no sky can
         * move an Age's floor.
         */
        fun dimensionType(options: Options): Identifier {
            val isLightless = options.of(SKYLIGHT) == "none"
            val isRoofed = options.of(ROOF) == "always"
            return when {
                isLightless && isRoofed -> AgeGeneration.AGE_LIGHTLESS_ROOFED_DIMENSION_TYPE
                isLightless -> AgeGeneration.AGE_LIGHTLESS_DIMENSION_TYPE
                isRoofed -> AgeGeneration.AGE_ROOFED_DIMENSION_TYPE
                else -> AgeGeneration.AGE_DIMENSION_TYPE
            }
        }

        const val NEVER = "never"

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
