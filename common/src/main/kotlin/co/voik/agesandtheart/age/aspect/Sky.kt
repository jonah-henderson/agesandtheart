package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.sky.SkySpec
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
enum class Sky(override val key: String, val ownDimensionType: Identifier) : AspectPreset {
    /** An ordinary sky — vanilla's own air and clouds. */
    PLAIN("plain", AgeGeneration.AGE_DIMENSION_TYPE),

    /**
     * A troubled sky — **which has no look of its own yet, so it currently renders as [PLAIN]**. A known
     * gap, not a silent drop: the choice is still recorded in the recipe and is still what `gloomy` and
     * `dramatic` resolve to. It gets its look back when clouds and atmosphere become authorable.
     */
    STORM("storm", AgeGeneration.AGE_DIMENSION_TYPE),

    /**
     * The Spire's own sky: two roiling cloud decks and stars that appear only above them. Written down in
     * [SpireSky] rather than resolved.
     *
     * Deliberately out of the resolver's reach — no word chooses it and `preset_tags/sky.json` omits it,
     * so it is reachable only by a preset that pins it.
     */
    SPIRE("spire", AgeGeneration.AGE_SPIRE_DIMENSION_TYPE),
    ;

    override val aspect = Aspect.SKY

    /** Only [SPIRE] is unaskable, and being so is the point of it — see [AspectPreset.askableInASentence]. */
    override val askableInASentence: Boolean get() = this != SPIRE

    /**
     * Every sky takes all four, including [PLAIN]: a writer who says "two suns" under a plain sky must get
     * two suns. [PLAIN] means ordinary *air*, not nothing unusual overhead.
     */
    override val parameters: List<Parameter> get() = listOf(SUNS, MOONS, STARS, ORBITS)

    /**
     * Which dimension type this Age needs. Only the **colour of the air** still varies — what is drawn
     * overhead comes from the Age's [SkySpec], but a spec carries no sky, fog or cloud colour, so a sky
     * wanting its own palette still needs a type to put it in. The band of world is not chosen here and
     * must not be: see [AgeGeneration.dimensionType].
     */
    fun dimensionType(): Identifier = ownDimensionType

    /**
     * The sky this preset asks for, drawn from the Age's [seed].
     *
     * [SPIRE] ignores both, and that is the whole of what makes it bespoke: its sky is written down rather
     * than resolved, so no seed and no option moves it.
     */
    fun specFor(options: Options, seed: Long): SkySpec =
        if (this == SPIRE) {
            SpireSky.SPEC
        } else {
            SkySpec.drawn(
                suns = options.countOf(SUNS),
                moons = options.countOf(MOONS),
                starCount = starsAt(options.steer(STARS, seed)),
                spread = spreadAt(options.steer(ORBITS, seed)),
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
        val SUNS = Parameter.counted("suns", ordinary = 1, most = MANY_BODIES)
        val MOONS = Parameter.counted("moons", ordinary = 1, most = MANY_BODIES)

        /** How thick the stars lie: none at the bottom of the axis, [DENSEST_STARS] times vanilla's at the top. */
        val STARS = Parameter.ranged("stars")

        /**
         * How far the extra bodies wander off the first one's path. At the bottom of the axis they are
         * strung along one arc like beads and at the top they cross at unrelated angles; unsaid is
         * [ORDINARY_SPREAD], which reads as a sky rather than as a diagram.
         */
        val ORBITS = Parameter.ranged("orbits")

        /** As many as a numeral page will be able to ask for, which is where §3.2 puts the ceiling. */
        private const val MANY_BODIES = 10

        private const val DENSEST_STARS = 3

        /** Vanilla's own star count, which is a third of the way up the axis. */
        private const val ORDINARY_STARS = 1.0 / DENSEST_STARS

        private const val ORDINARY_SPREAD = 0.45

        private fun starsAt(density: Double?): Int {
            val fraction = density?.let(Span.NATURAL::fractionOf) ?: ORDINARY_STARS
            return (fraction * DENSEST_STARS * SkySpec.VANILLA_STAR_COUNT).roundToInt()
        }

        /** The spread is already the fraction it is asked for, so the axis needs only reading. */
        private fun spreadAt(wander: Double?): Float =
            (wander?.let(Span.NATURAL::fractionOf) ?: ORDINARY_SPREAD).toFloat()
    }
}
