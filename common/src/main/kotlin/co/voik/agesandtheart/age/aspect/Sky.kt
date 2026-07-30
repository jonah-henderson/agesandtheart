package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.sky.SkySpec
import net.minecraft.resources.ResourceLocation

/**
 * What is overhead.
 *
 * Sky was the one thing already being *inferred* from the terrain — Spire worlds got the custom sky because they
 * were Spire worlds — and inference is exactly what aspects exist to replace. Naming it made "hills under a
 * stormy Spire sky" writable.
 *
 * **The presets are the atmosphere; the parameters are what hangs in it.** That split arrived with per-Age skies
 * (2026-07-29) and is why this stopped being the thinnest aspect. A preset says what the air and the clouds are
 * like, which is a small closed set. How many suns there are, how their orbits sit, how thickly the stars run —
 * those vary continuously and belong on parameters, resolved into a [SkySpec] that is *sent to the client* rather
 * than baked into a dimension type. `notes/per-age-skies-research.md` explains why that indirection is not
 * optional: a `DimensionType` composed per Age cannot be encoded in the join packet at all.
 *
 * The counts are enumerated rather than numeric because §3.2 forbids exposing numbers to the *player*. The
 * numbers behind them are ordinary internal arithmetic, and [SkySpec.drawn] takes it from there using the Age's
 * seed — so the writer names the character and the Age decides the specifics.
 */
enum class Sky(override val key: String, val ownDimensionType: ResourceLocation) : AspectPreset {
    /** An ordinary sky — vanilla's own air and clouds. */
    PLAIN("plain", AgeGeneration.AGE_PLAIN_DIMENSION_TYPE),

    /**
     * A troubled sky — **and as of 2026-07-29 it has no look of its own yet.**
     *
     * It used to mean steel-grey haze and two roiling cloud decks with stars only above them. That was the
     * *Spire's* atmosphere, borrowed: the two shared one effects marker, so every Age wearing this preset wore the
     * handcrafted Age's weather. Jonah's call was that the Spire's concepts must not be assumptions for anything
     * else, so they went back to the Spire and this was left holding nothing.
     *
     * **So `storm` currently renders the same as `plain`,** and that is a known gap rather than a silent drop: the
     * choice is still recorded in the recipe, still what `gloomy` and `dramatic` words resolve to, and gets its
     * look back when clouds and atmosphere become authorable (the plan's step 6). It is named here so that nobody
     * finds the sameness in game and concludes something is broken.
     */
    STORM("storm", AgeGeneration.AGE_DIMENSION_TYPE),

    /**
     * The Spire's own sky, and **the first of the "special exceptions" a pinned bespoke preset is allowed**
     * (Jonah, 2026-07-29, on making bespoke Ages into pinned compositions: *"this is how we will want to handle
     * most bespoke, with carefully pinned presets and occasional special exceptions, like the sky/star effects"*).
     *
     * It is a real preset rather than a hack because the exception has to survive the Spire becoming ordinary
     * data: once its world is a composition like any other, the only thing left that is *not* ordinary is what
     * hangs over it — two roiling cloud decks and stars that appear only above them, drawn by `SpireSkyRenderer`
     * under `agesandtheart:spire`.
     *
     * **Deliberately absent from the resolver's reach.** No word chooses this and `preset_tags/sky.json` does not
     * list it, so a writer cannot ask for the Spire's sky by accident or on purpose — it is reachable only by a
     * preset that pins it. That is what keeps an easter egg an easter egg.
     */
    SPIRE("spire", AgeGeneration.AGE_SPIRE_DIMENSION_TYPE),
    ;

    override val aspect = Aspect.SKY

    /** Only [SPIRE] is unaskable, and being so is the point of it — see [AspectPreset.askableInASentence]. */
    override val askableInASentence: Boolean get() = this != SPIRE

    /**
     * Every sky takes all four, **including [PLAIN]**.
     *
     * A writer who says "two suns" under a plain sky must get two suns; dropping it because the preset happens to
     * be the ordinary one is precisely §3.3's silent drop. What [PLAIN] means is *ordinary air*, not *nothing
     * unusual overhead*.
     */
    override val parameters: List<Parameter> get() = listOf(SUNS, MOONS, STARS, ORBITS)

    /**
     * Which dimension type this Age needs, given what its sky actually turned out to be.
     *
     * **An Age borrows our renderer only when it has something vanilla cannot draw.** An ordinary sky keeps
     * vanilla's own `effects`, the client attaches nothing, and a plain Age gets vanilla's sky *exactly* rather
     * than our imitation of it — no regression and no cost. Ask for a second sun or take the stars away and the
     * Age switches over. [SkySpec.isOrdinary] is the whole test.
     *
     * This is what lets [PLAIN] declare the parameters honestly without either regressing every ordinary Age or
     * dropping a request on the floor.
     */
    fun dimensionType(spec: SkySpec): ResourceLocation = when {
        // A preset that pins a marker of its own keeps it whatever its bodies turn out to be — the point of
        // [SPIRE] is the atmosphere, not the suns.
        this == SPIRE -> ownDimensionType
        this == PLAIN && spec.isOrdinary -> ownDimensionType
        else -> AgeGeneration.AGE_DIMENSION_TYPE
    }

    /** The sky this preset asks for, drawn from the Age's [seed]. */
    fun specFor(options: Options, seed: Long): SkySpec = SkySpec.drawn(
        suns = countOf(options.of(SUNS)),
        moons = countOf(options.of(MOONS)),
        starCount = starsOf(options.of(STARS)),
        spread = spreadOf(options.of(ORBITS)),
        seed = seed,
    )

    override fun getSerializedName(): String = key

    companion object {
        /**
         * The first option of each is its default, and **the four defaults together must draw exactly
         * [SkySpec.VANILLA]** — that is what lets an unremarkable Age keep vanilla's own sky rather than a
         * lookalike. `SkyCheck` holds it, because it would otherwise break silently the first time
         * anyone reordered an option list.
         */
        val SUNS = Parameter("suns", "one", "two", "three", "many")
        val MOONS = Parameter("moons", "one", "none", "two", "many")
        val STARS = Parameter("stars", "ordinary", "none", "sparse", "dense")

        /**
         * How far the extra bodies wander off the first one's path.
         *
         * `shared` is a striking case rather than the dull one: several suns tracking the same arc, strung along
         * it like beads. `wild` is the Mystcraft postcard — bodies crossing at unrelated angles. `tilted` is the
         * default because it is the one that reads as a *sky* rather than as a diagram.
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
