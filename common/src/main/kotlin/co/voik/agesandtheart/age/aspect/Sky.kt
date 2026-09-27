package co.voik.agesandtheart.age.aspect

import co.voik.ephemeris.sky.Look
import co.voik.ephemeris.sky.SkySpec
import co.voik.agesandtheart.location
import net.minecraft.resources.Identifier
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * What is overhead. The presets are the atmosphere; the parameters are what hangs in it, resolved into a
 * [SkySpec] that is *sent to the client* rather than baked into a dimension type — a `DimensionType`
 * composed per Age cannot be encoded in the join packet at all (`notes/per-age-skies-research.md`).
 *
 * A sky's bodies are **counted** and its stars are a density, per §3.2's rule about which numbers may reach
 * a writer: two moons is a thing to say about a sky, and fifteen hundred stars is not. [SkySpec.drawn] takes
 * it from there.
 */
enum class Sky(override val key: String) : AuthoredPreset {
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

    /** Only [SPIRE] is kept from broad words, and being so is the point of it — see [Taggable.availableToBroadWords]. */
    override val availableToBroadWords: Boolean get() = this != SPIRE

    /**
     * **None.** The bodies overhead are the sun's, the moon's and the stars' — their own aspects — and the
     * vault's own parameters belong to the aspect rather than to any one of its presets. A preset declaring them
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
        if (isRoofed(parts)) return SkySpec.drawn(NONE, NONE, NONE, ORDINARY_SPREAD.toFloat(), seed)
        // **Assembled from three aspects**, which is what the split made explicit: the suns, the moons and
        // the star field are each their own part of the world, and a spec is where they meet.
        val sun = parts.optionsFor(Aspect.SUN)
        // **The cast is what the book described, or the template's where it described none.** A sun is
        // brought into being by a clause about it, so the number of bodies is the number of clauses —
        // there is no count to write and no second spelling for "two suns".
        val suns = if (sun.isTrue(SkyBodies.ABSENT)) NONE
            else parts.membersIn(Aspect.SUN).takeIf { it > NONE } ?: VANILLAS_ONE
        val moons = if (parts.optionsFor(Aspect.MOON).isTrue(SkyBodies.ABSENT)) NONE
            else parts.membersIn(Aspect.MOON).takeIf { it > NONE } ?: VANILLAS_ONE
        val drawn = SkySpec.drawn(
            suns = suns,
            moons = moons,
            starCount = SkyBodies.starsAt(parts.optionsFor(Aspect.STARS).steer(SkyBodies.STARS, seed)),
            starGlow = SkyBodies.starGlowAt(parts.optionsFor(Aspect.STARS).steer(SkyBodies.STARGLOW, seed)),
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
            SkyBodies.described(body, parts.optionsFor(aspect, among(at, isASun)), seed)
        }
        return drawn.copy(
            bodies = told,
            aurora = AuroraAspect.auroraIn(parts, seed),
            rainbow = RainbowAspect.rainbowIn(parts, seed),
        )
    }

    override fun getSerializedName(): String = key

    companion object {

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
        val SEALED = Parameter.flag("sealed", help = "Whether the world is roofed over, like the nether.")

        /**
         * Whether the Age is shut overhead — **because its book said so, or because its rock does it**.
         *
         * The two are one fact and are read as one everywhere. A landform solid to the ceiling is sealed
         * whether or not anybody wrote the word, which is what makes the physical claim above true rather
         * than merely intended (see [AgeParts.roofedByItsRock]).
         */
        fun isRoofed(parts: AgeParts): Boolean = parts.optionsFor(Aspect.SKY).isTrue(SEALED) || parts.roofedByItsRock

        /**
         * Whether nothing lights the Age from above — **one fact with three readers**: the dimension type
         * it wears, the skylight the game gives it, and how its vault is painted.
         *
         * A world is dark because it is shut overhead *or* because nothing shines on it, and the two must
         * be asked together. A reader that knows only the seal paints a blue sky with clouds in it over a
         * world the game is holding pitch dark, which is the walked bug of 2026-08-05 arriving a second
         * time by a second route.
         */
        fun isLightless(parts: AgeParts): Boolean =
            isRoofed(parts) || parts.optionsFor(Aspect.SUN).isTrue(SkyBodies.ABSENT)

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
        fun dimensionType(parts: AgeParts): Identifier =
            // **Three, not four.** A world sealed overhead cannot also let the sky reach the ground, so
            // roofed-and-lit is a combination the facts cannot produce and the file for it is gone.
            when {
                isRoofed(parts) -> AGE_LIGHTLESS_ROOFED_DIMENSION_TYPE
                isLightless(parts) -> AGE_LIGHTLESS_DIMENSION_TYPE
                else -> AGE_DIMENSION_TYPE
            }

        /**
         * The three types an Age with rock of its own may wear — [isLightless] and [isRoofed], spelled out.
         *
         * A composed `DimensionType` cannot be encoded in the join packet, so every combination is a file
         * we ship, and each further switch would double them. All three declare
         * [co.voik.agesandtheart.worldgen.VerticalWindow.DEFAULT], which is the band a field tree builds
         * into; an Age wearing a template's rock wears that world's own type instead, and its band with it.
         *
         * **They live with the aspect that decides them** rather than with the generation that reads them:
         * which type an Age wears is a fact about its sky, and the model asking the composition for it was
         * the one edge that kept generation from being lifted out of `age`.
         */
        val AGE_DIMENSION_TYPE: Identifier = "age".location()
        val AGE_LIGHTLESS_DIMENSION_TYPE: Identifier = "age_lightless".location()
        val AGE_LIGHTLESS_ROOFED_DIMENSION_TYPE: Identifier = "age_lightless_roofed".location()

        private const val NONE = 0

        /** What a sky nobody wrote a body into keeps — the overworld's own, which is the template. */
        private const val VANILLAS_ONE = 1

        /**
         * How far the extra bodies wander off the first one's path, which reads as a sky rather than as a
         * diagram.
         *
         * **No longer a parameter**, and it will not become one again: a spread is a fact about the *arrangement*
         * of a count of bodies, and once each body is written on its own page and says where it rises there
         * is nothing left for it to mean.
         */
        private const val ORDINARY_SPREAD = 0.45

        internal fun colour(name: String, help: String) =
            Parameter(name, listOf(Parameter.DEFAULT) + Colour.ALL, help = help)

        /**
         * A point on an axis a writer said, laid between [least] and [most].
         *
         * One function rather than one per dial, which is also what lets [AuroraAspect.AURORASIZE]'s two
         * bands move together off the single axis a writer states.
         */
        internal fun bandAt(largeness: Double, least: Float, most: Float): Float =
            (least + Span.NATURAL.fractionOf(largeness) * (most - least)).toFloat()

        /**
         * A point on [Span.NATURAL] drawn from this Age's seed, for an axis no word bounded.
         *
         * Salted per axis, so an Age's curtain is not as bright as it is wide as it is frequent — one draw
         * shared between three would make every undescribed aurora sit on a diagonal.
         */
        internal fun drawn(seed: Long, salt: Long, band: Span): Double =
            band.least + XoroshiroRandomSource(seed xor salt).nextDouble() * band.width

        /**
         * The band an unsaid glow is drawn from, in [Span.NATURAL]'s own terms — here rather than with
         * either display because a curtain and a bow draw their brightness from the same one.
         *
         * Narrower than what a word can ask for, and deliberately: the far ends of each axis are what a
         * writer *buys*, so an Age that said nothing should never land somewhere `brilliant` could have
         * taken it.
         */
        internal val DRAWN_GLOW = Span(-0.4, 0.35)
    }
}
