package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.sky.SkySpec
import co.voik.agesandtheart.sky.SpireSky
import net.minecraft.resources.Identifier

/**
 * What is overhead. The presets are the atmosphere; the parameters are what hangs in it, resolved into a
 * [SkySpec] that is *sent to the client* rather than baked into a dimension type — a `DimensionType`
 * composed per Age cannot be encoded in the join packet at all (`notes/per-age-skies-research.md`).
 *
 * Counts are enumerated rather than numeric, per design §3.2. [SkySpec.drawn] takes it from there.
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
     * Which dimension type this Age needs. Only the band of world still varies — the sky is drawn from the
     * Age's [SkySpec] whatever the dimension type says, so an ordinary Age needs no marker of its own.
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
                suns = countOf(options.of(SUNS)),
                moons = countOf(options.of(MOONS)),
                starCount = starsOf(options.of(STARS)),
                spread = spreadOf(options.of(ORBITS)),
                seed = seed,
            )
        }

    override fun getSerializedName(): String = key

    companion object {
        /**
         * The first option of each is its default, and **the four defaults together must draw exactly
         * [SkySpec.VANILLA]**, or an unremarkable Age stops keeping vanilla's own sky. `SkyCheck` holds it;
         * reordering an option list would otherwise break it silently.
         */
        val SUNS = Parameter("suns", "one", "two", "three", "many")
        val MOONS = Parameter("moons", "one", "none", "two", "many")
        val STARS = Parameter("stars", "ordinary", "none", "sparse", "dense")

        /**
         * How far the extra bodies wander off the first one's path. `shared` strings them along one arc
         * like beads, `wild` crosses them at unrelated angles, and `tilted` is the default because it
         * reads as a sky rather than as a diagram.
         */
        val ORBITS = Parameter("orbits", "tilted", "shared", "wild")

        /** Not as many as one could ask for — as many as still reads as a sky rather than as clutter. */
        private const val MANY_BODIES = 5

        private fun countOf(option: String): Int = when (option) {
            "none" -> 0
            "two" -> 2
            "three" -> 3
            "many" -> MANY_BODIES
            else -> 1
        }

        private fun starsOf(option: String): Int = when (option) {
            "none" -> 0
            "sparse" -> SkySpec.VANILLA_STAR_COUNT / SPARSE_DIVISOR
            "dense" -> SkySpec.VANILLA_STAR_COUNT * DENSE_MULTIPLE
            else -> SkySpec.VANILLA_STAR_COUNT
        }

        private const val SPARSE_DIVISOR = 5
        private const val DENSE_MULTIPLE = 3

        private fun spreadOf(option: String): Float = when (option) {
            "shared" -> 0.0f
            "wild" -> 1.0f
            else -> TILTED_SPREAD
        }

        private const val TILTED_SPREAD = 0.45f
    }
}
