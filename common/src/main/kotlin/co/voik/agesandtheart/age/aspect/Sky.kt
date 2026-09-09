package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Appearance
import co.voik.ephemeris.sky.Aurora
import co.voik.ephemeris.sky.Rainbow
import co.voik.ephemeris.sky.CelestialBody
import co.voik.ephemeris.sky.Look
import co.voik.ephemeris.sky.Orbit
import co.voik.ephemeris.sky.SkySpec
import co.voik.agesandtheart.sky.SpireSky
import net.minecraft.resources.Identifier
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
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

    /** Only [SPIRE] is unaskable, and being so is the point of it — see [Taggable.askableInASentence]. */
    override val askableInASentence: Boolean get() = this != SPIRE

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
        val suns = if (sun.isTrue(ABSENT)) NONE
            else parts.membersIn(Aspect.SUN).takeIf { it > NONE } ?: VANILLAS_ONE
        val moons = if (parts.optionsFor(Aspect.MOON).isTrue(ABSENT)) NONE
            else parts.membersIn(Aspect.MOON).takeIf { it > NONE } ?: VANILLAS_ONE
        val drawn = SkySpec.drawn(
            suns = suns,
            moons = moons,
            starCount = starsAt(parts.optionsFor(Aspect.STARS).steer(STARS, seed)),
            starGlow = starGlowAt(parts.optionsFor(Aspect.STARS).steer(STARGLOW, seed)),
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
        return drawn.copy(bodies = told, aurora = auroraIn(parts, seed), rainbow = rainbowIn(parts, seed))
    }

    /**
     * The curtain this Age wears, or null where nothing asked for one.
     *
     * **Two ways in, and they are the same statement made twice** — which is the shape §7.7 says this aspect
     * has. A writer may *name* the phenomenon (`auroral`, or an evocative word the tag layer carried there)
     * or *describe* it (`red and green aurora`), and either is having said the Age has one. Nothing mints a
     * member here the way a clause mints a sun, the aspect holding nothing, so a dial with anything on it is
     * the description.
     *
     * A bare `aurora` page with nothing leading it says neither, and is inert rather than wrong.
     */
    private fun auroraIn(parts: AgeParts, seed: Long): Aurora? {
        val own = parts.optionsFor(Aspect.AURORA)
        val claim = Phenomena.claimFor(parts.optionsFor(Aspect.PHENOMENA, 0), Phenomenon.AURORA)
        if (claim == null && own.chosen.isEmpty()) return null
        // **The rung is how hard it comes**, which is the populative machinery doing the job it already
        // does: `teeming auroral` is more nights and a brighter curtain, and no dial had to be invented for
        // it. Ordinary is one, so a word that named no quantity changes nothing.
        val insistence = (claim?.density ?: Rung.ORDINARY) / Rung.ORDINARY
        // **Drawn where nothing said, rather than defaulted.** `auroral` names the phenomenon and nothing
        // else, so without this every undescribed curtain in every Age would be the same curtain. The seed
        // is where the bearing already came from, and an aurora nobody described should still differ
        // between Ages without a writer having to buy the difference.
        val size = own.steer(AURORASIZE, seed) ?: drawn(seed, SIZE_SALT, DRAWN_BAND)
        return Aurora(
            colours = rampIn(own),
            glow = (glowAt(own.steer(AURORAGLOW, seed) ?: drawn(seed, GLOW_SALT, DRAWN_GLOW)) * insistence)
                .toFloat().coerceIn(FAINTEST_CURTAIN, BRIGHTEST_CURTAIN),
            breadth = bandAt(size, NARROWEST_BAND, WIDEST_BAND),
            height = bandAt(size, SHORTEST_BAND, TALLEST_BAND),
            frequency = (nightsAt(own.steer(AURORAFREQUENCY, seed) ?: drawn(seed, NIGHTS_SALT, DRAWN_NIGHTS))
                * insistence).toFloat().coerceIn(RAREST_NIGHTS, EVERY_NIGHT),
            // Drawn rather than written: which way a band lies is a fact about this Age's sky and not
            // something §3.2 would put in front of a writer.
            bearingDegrees = XoroshiroRandomSource(seed xor AURORA_SALT).nextInt(WHOLE_COMPASS).toFloat(),
            // **Where the snow lies unless a writer says otherwise**, and vanilla's own line between snow
            // and rain is a boundary every player has already learned by walking over it. It is a ceiling
            // rather than a rule now, which is what lets a word ask for curtains over temperate ground —
            // see [AURORAWARMTH] for why that coupling was worth loosening.
            warmestGround = warmthAt(own.steer(AURORAWARMTH, seed) ?: WHERE_THE_SNOW_LIES),
            seed = seed,
        )
    }

    /**
     * [AURORAWARMTH] read as a temperature ceiling — nought is vanilla's snow line, and either end runs to
     * where no biome is left to exclude or include.
     */
    private fun warmthAt(steered: Double): Float = when {
        steered <= 0.0 -> (Aurora.SNOW_LINE + (Aurora.SNOW_LINE - COLDEST_GROUND) * steered).toFloat()
        else -> (Aurora.SNOW_LINE + (WARMEST_GROUND - Aurora.SNOW_LINE) * steered).toFloat()
    }

    /**
     * The bow this Age wears, or null where nothing asked for one.
     *
     * **The aurora's two ways in, for the aurora's reason.** A writer may *name* the phenomenon
     * (`rainbows`) or *describe* it (`red and yellow rainbow`), and either is having said the Age has them.
     * A bare `rainbow` page with nothing leading it says neither, and is inert rather than wrong.
     *
     * **Where it stands is not decided here and could not be.** A bow is a circle about the point opposite
     * whatever lights it, so it is placed by the Age's own suns at the instant it is drawn — which is why
     * there is no bearing to draw from the seed as an aurora needs.
     */
    private fun rainbowIn(parts: AgeParts, seed: Long): Rainbow? {
        val own = parts.optionsFor(Aspect.RAINBOW)
        val claim = Phenomena.claimFor(parts.optionsFor(Aspect.PHENOMENA, 0), Phenomenon.RAINBOW)
        if (claim == null && own.chosen.isEmpty()) return null
        // The rung is how hard it comes, as it is for a curtain: `teeming rainbows` is more days and a
        // brighter bow, off the populative machinery rather than a dial invented for it.
        val insistence = (claim?.density ?: Rung.ORDINARY) / Rung.ORDINARY
        return Rainbow(
            colours = bandIn(own),
            glow = (bandAt(own.steer(RAINBOWGLOW, seed) ?: drawn(seed, BOW_GLOW_SALT, DRAWN_GLOW),
                FAINTEST_BOW, BRIGHTEST_BOW) * insistence)
                .toFloat().coerceIn(FAINTEST_BOW, BRIGHTEST_BOW),
            // **Not drawn from the seed, where a curtain's size is** — and the difference is the point. How
            // large a curtain hangs is a fact about the display, so an Age nobody described should still
            // differ. How wide a bow stands is a fact about what its light is bending *through*, and an
            // Age's rain is water until a writer says otherwise.
            radiusDegrees = own.steer(RAINBOWSIZE, seed)
                ?.let { bandAt(it, NARROWEST_BOW, WIDEST_BOW) } ?: Rainbow.WATERS_OWN,
            needsRain = own.steer(RAINBOWRAIN, seed)
                ?.let { bandAt(it, NO_RAIN_AT_ALL, ALL_THE_RAIN) } ?: Rainbow.ORDINARY_RAIN_NEEDED,
            frequency = (bandAt(own.steer(RAINBOWFREQUENCY, seed) ?: drawn(seed, BOW_DAYS_SALT, DRAWN_BOW_DAYS),
                RAREST_DAYS, EVERY_DAY) * insistence)
                .toFloat().coerceIn(RAREST_DAYS, EVERY_DAY),
            seed = seed,
        )
    }

    /**
     * The colours the bow burns, **outermost first and in the order they were written**, or the ordinary
     * spectrum where nobody named one.
     *
     * [Options.allOf] rather than [Options.of] for the ramp's reason, and [Parameter.keepsWrittenOrder] on
     * [RAINBOWCOLOUR] is what keeps red on the outside. The second bow reverses whatever this answers, so a
     * writer names one band and gets both.
     */
    private fun bandIn(own: Options): List<Rgba> {
        val named = own.allOf(RAINBOWCOLOUR)
            .filter { it != Parameter.DEFAULT }
            .mapNotNull { Colour.named(it)?.saturated(A_BOW_IS_ADDED) }
        return named.ifEmpty { Rainbow.ORDINARY_SPECTRUM }
    }

    /**
     * The colours the curtain burns, **crown first and in the order they were written**, or the ordinary
     * ramp where nobody named one.
     *
     * [Options.allOf] rather than [Options.of] is the whole of the difference from a sun's colour: a sun is
     * one tint and this is a ramp, and the mingling that puts several values on one dial is the same
     * mechanism that puts two rocks in one wall. What keeps them in the writer's order is
     * [Parameter.keepsWrittenOrder] on [AURORACOLOUR].
     *
     * **Nothing sorts them into what nature does.** Violet crowning red is a sky nobody has seen and a
     * writer is welcome to it; the realism lives in the default, which is where a default belongs.
     */
    private fun rampIn(own: Options): List<Rgba> {
        val named = own.allOf(AURORACOLOUR)
            .filter { it != Parameter.DEFAULT }
            .mapNotNull { Colour.named(it)?.saturated(A_CURTAIN_IS_LOOKED_AT) }
        return named.ifEmpty { Aurora.ORDINARY_RAMP }
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
        /**
         * Whether the body a clause is about is **not there** — one parameter on the sun and on the moon.
         *
         * It was two, `shining` and `orbiting`, kept apart by a rule that no longer exists and named for
         * what a body *does* rather than for what is being said about it. `sun.absent` and `moon.absent`
         * read as what a writer means (Jonah, 2026-08-31), and a word says which body it means by naming
         * the aspect in the parameter — without that the two would be one word, since `Word.reaching` only ever
         * widens.
         *
         * **Only the first body can be absent, which is a quirk of how a sky is written rather than of
         * this.** Every other sun is minted by a clause describing one, so there is no clause to say a
         * body that was never minted is missing; this is the default body's own switch.
         */
        val ABSENT = Parameter.flag("absent", help = "Whether this body is missing from the sky altogether.")

        /** How thick the stars lie: none at the bottom of the axis, [DENSEST_STARS] times vanilla's at the top. */
        val STARS = Parameter.ranged(
            "density",
            help = "How thickly the stars lie.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "none at all"),
                Parameter.Landmark(-0.3333, "vanilla's", isVanilla = true),
                Parameter.Landmark(0.3333, "twice vanilla's"),
                Parameter.Landmark(1.0, "three times"),
            ),
        )

        /**
         * How brightly the stars burn, against vanilla's own — [BRIGHTEST_STARS] times it at the top.
         *
         * A second axis on one field because brilliance and number are different facts about it: `starlit`
         * says there are more of them and this says the ones there are blaze, and a sky may be either
         * without being the other.
         *
         * **Never fainter than vanilla's**, for the reason [SUNSIZE] is never smaller: a sky with less
         * light in it overhead is a *sparser* one, and that is [STARS] to say.
         */
        val STARGLOW = Parameter.ranged(
            "glow",
            help = "How brightly the stars burn.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "vanilla's", isVanilla = true),
                Parameter.Landmark(0.0, "half again"),
                Parameter.Landmark(1.0, "twice as bright"),
            ),
        )

        /**
         * The colours the curtain burns, crown first — **the one dial that holds several values in order**.
         *
         * A ramp rather than a colour, because that is what an aurora is: it changes from its crown to its
         * hem, and a writer naming two means both. `and` is what joins them, exactly as it joins two rocks
         * in a wall; [Parameter.keepsWrittenOrder] is what keeps the crown at the crown.
         */
        val AURORACOLOUR = colour("colour", "The colours of the aurora, from its crown to its hem.").copy(keepsWrittenOrder = true)

        /** How brightly the curtain burns, against an ordinary one. */
        val AURORAGLOW = Parameter.ranged("glow", help = "How brightly the aurora burns.")

        /**
         * How much of the sky the curtain takes up.
         *
         * **One axis for both directions**, because a curtain is not two independent measurements: a small
         * one is small, and a writer who wanted a wide low band and a narrow tall one is asking a question
         * §3.2 keeps away from them.
         */
        val AURORASIZE = Parameter.ranged(
            "size",
            help = "How much of the sky the aurora takes up.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "a narrow band"),
                Parameter.Landmark(0.0, "a broad ribbon"),
                Parameter.Landmark(1.0, "most of the sky"),
            ),
        )

        /** What share of nights it comes at all. */
        val AURORAFREQUENCY = Parameter.ranged("frequency", help = "What share of nights the aurora comes at all.")

        /**
         * How warm the ground under it may be and still show one.
         *
         * **The cold was only ever a proxy for the poles**, and a loose one: nothing in an Age carries
         * anything electromagnetic for a curtain to key on, so `coldEnoughToSnow` stood in for a latitude
         * we do not have. That is a fair default and a bad requirement — an Age charged enough to hang
         * curtains has no reason to be a cold one (Jonah, 2026-09-08). So the coupling is loosened *here*,
         * where a writer can ask, rather than removed: an aurora nobody described still stands where the
         * snow does.
         *
         * **What it does not loosen is anything that reads the cold for its own sake.** Rime demands a
         * frozen climate outright and takes the curtain as incidental, so nothing about it moves.
         */
        val AURORAWARMTH = Parameter.ranged(
            "warmth",
            help = "How warm the ground under the aurora may be and still show one.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "only over deep ice"),
                Parameter.Landmark(0.0, "where the snow lies"),
                Parameter.Landmark(1.0, "over any ground at all"),
            ),
        )

        /**
         * The colours the bow burns, **outermost first** — red at the outside, as a real one is.
         *
         * The same mechanism as [AURORACOLOUR] and for the same reason: a bow is a band rather than a tint,
         * and which end is the outside is the one thing about it a writer states outright. The second bow
         * reverses this, so naming one band names both.
         */
        val RAINBOWCOLOUR = colour("colour", "The colours of the arc.").copy(keepsWrittenOrder = true)

        /** How brightly the bow burns, against an ordinary one. */
        val RAINBOWGLOW = Parameter.ranged("glow", help = "How brightly the rainbow burns.")

        /**
         * How wide the arc stands from the point opposite its light.
         *
         * **A fact about what the light is bending through, not about the sky** — ours is forty-two degrees
         * because that is what water does, and an Age whose rain is not water has every right to another.
         *
         * It is also how much of the day a bow survives, with nothing else said: the crown stands at this
         * less the light's own height, so a wide arc is still up under a sun that has already sunk a narrow
         * one below the ground.
         */
        val RAINBOWSIZE = Parameter.ranged(
            "size",
            help = "How wide the arc stands.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "a tight arc"),
                Parameter.Landmark(0.0, "an ordinary bow"),
                Parameter.Landmark(1.0, "a wide sweep"),
            ),
        )

        /** What share of days it comes at all. */
        val RAINBOWFREQUENCY = Parameter.ranged("frequency", help = "What share of days a rainbow comes at all.")

        /**
         * How much falling water it needs — the top of the axis will not come without rain, the bottom
         * never wanted any.
         *
         * The dial that lets an Age hang arcs in a dry clear sky, and a thing to say on purpose rather than
         * a default to fall into: left alone, a bow is sunlight bent through rain and waits for some.
         */
        val RAINBOWRAIN = Parameter.ranged("rain", help = "How much rain a rainbow needs before it will appear.")

        /**
         * How large the suns are, against vanilla's — [LARGEST_SUN] times it at the top of the axis.
         *
         * **The renderer could always draw this and nothing could say it.** `Appearance.Sprite` has carried
         * an `angularSize` since the sky was built, and [SkySpec.drawn] already varied it for the *extra*
         * suns; what was missing was a writer's way to ask, and a way for the ask to reach the first one.
         */
        val SUNSIZE = Parameter.ranged(
            "size",
            help = "How large the suns are.",
            // The bottom is vanilla's own and the top is LARGEST_SUN times it, evenly between.
            landmarks = listOf(
                    Parameter.Landmark(-1.0, "vanilla's", isVanilla = true),
                    Parameter.Landmark(-1.0 / 3.0, "twice"),
                    Parameter.Landmark(1.0 / 3.0, "three times"),
                    Parameter.Landmark(1.0, "four times"),
                ),
        )

        /**
         * What colour the suns burn, or [Parameter.DEFAULT] for vanilla's white.
         *
         * Reaches **every** sun including the first, which is what separates it from the tint
         * [SkySpec.drawn] already draws for the others: that one spreads a sky's extra bodies apart, and
         * this one is a statement about the star this world goes round.
         */
        val SUNCOLOUR = colour("colour", "The colour the suns burn.")

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
         * **The sun's and the moon's alike**, which is one parameter owned by two aspects: the word means the
         * same thing about either body and the clause it was laid in picks which one it is about.
         */
        val RISING = Parameter(
            "rising",
            listOf(Parameter.DEFAULT) + BEARINGS.keys,
            help = "Which horizon this body rises over.",
        )

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
            isRoofed(parts) || parts.optionsFor(Aspect.SUN).isTrue(ABSENT)

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
                isRoofed(parts) -> AgeGeneration.AGE_LIGHTLESS_ROOFED_DIMENSION_TYPE
                isLightless(parts) -> AgeGeneration.AGE_LIGHTLESS_DIMENSION_TYPE
                else -> AgeGeneration.AGE_DIMENSION_TYPE
            }

        private const val NONE = 0

        /** What a sky nobody wrote a body into keeps — the overworld's own, which is the template. */
        private const val VANILLAS_ONE = 1

        private const val DENSEST_STARS = 3

        /** Vanilla's own star count, which is a third of the way up the axis. */
        private const val ORDINARY_STARS = 1.0 / DENSEST_STARS

        /**
         * How many times vanilla's own the top of [STARGLOW] burns at.
         *
         * Twice is *four* times the light — the sky pass adds what it draws scaled by its own alpha — so
         * this reaches a good deal further than the number suggests.
         */
        private const val BRIGHTEST_STARS = 2.0

        /**
         * How far the extra bodies wander off the first one's path, which reads as a sky rather than as a
         * diagram.
         *
         * **No longer a parameter**, and it will not become one again: a spread is a fact about the *arrangement*
         * of a count of bodies, and once each body is written on its own page and says where it rises there
         * is nothing left for it to mean.
         */
        private const val ORDINARY_SPREAD = 0.45

        private fun starsAt(density: Double?): Int {
            val fraction = density?.let(Span.NATURAL::fractionOf) ?: ORDINARY_STARS
            return (fraction * DENSEST_STARS * SkySpec.VANILLA_STAR_COUNT).roundToInt()
        }

        /**
         * Vanilla's own brilliance where the axis is unsaid, so an ordinary sky's stars are vanilla's
         * exactly — which is the whole reason this became a word rather than staying the default.
         */
        private fun starGlowAt(brilliance: Double?): Float {
            val fraction = brilliance?.let(Span.NATURAL::fractionOf) ?: return SkySpec.ORDINARY_STAR_GLOW
            return SkySpec.ORDINARY_STAR_GLOW * (1.0 + fraction * (BRIGHTEST_STARS - 1.0)).toFloat()
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

        private fun colour(name: String, help: String) =
            Parameter(name, listOf(Parameter.DEFAULT) + Colour.ALL, help = help)

        /**
         * How far a named colour is pushed from its own grey before a curtain burns it.
         *
         * The same argument as [SUN_IS_LOOKED_AT] and a little harder: an aurora is drawn additively against
         * a night sky, where a washed-out tint reads as fog rather than as light.
         */
        private const val A_CURTAIN_IS_LOOKED_AT = 1.8f

        /** The band [AURORAGLOW] runs over, against an ordinary curtain. */
        private const val FAINTEST_CURTAIN = 0.35f
        private const val BRIGHTEST_CURTAIN = 2.0f

        /** The band [AURORAFREQUENCY] runs over. Never every night by default, and never truly never. */
        private const val RAREST_NIGHTS = 0.08f
        private const val EVERY_NIGHT = 1.0f

        /**
         * Where [AURORAWARMTH] sits when nothing said — vanilla's snow line, so an undescribed curtain
         * stands exactly where it always did.
         */
        private const val WHERE_THE_SNOW_LIES = 0.0

        /** The band [AURORAWARMTH] runs over: deep ice at one end, and past any biome vanilla has at the other. */
        private const val COLDEST_GROUND = -0.5f
        private const val WARMEST_GROUND = 2.5f

        /** How much of the sky [AURORASIZE] reaches, either way. */
        private const val NARROWEST_BAND = 0.3f
        private const val WIDEST_BAND = 1.0f
        // The renderer will not draw a stubby curtain — a short one reads as a ribbon rather than as a
        // modest aurora — so the axis runs above that floor rather than half into it.
        private const val SHORTEST_BAND = 0.5f
        private const val TALLEST_BAND = 1.0f

        private const val AURORA_SALT = 0x0A17_0BA5L

        /** Every whole degree of it, a band being wide enough that a finer bearing says nothing. */
        private const val WHOLE_COMPASS = 360

        private fun glowAt(brilliance: Double): Double =
            FAINTEST_CURTAIN + Span.NATURAL.fractionOf(brilliance) * (BRIGHTEST_CURTAIN - FAINTEST_CURTAIN)

        private fun nightsAt(often: Double): Double =
            RAREST_NIGHTS + Span.NATURAL.fractionOf(often) * (EVERY_NIGHT - RAREST_NIGHTS)

        /**
         * A point on an axis a writer said, laid between [least] and [most].
         *
         * One function rather than one per dial, which is also what lets [AURORASIZE]'s two bands move
         * together off the single axis a writer states.
         */
        private fun bandAt(largeness: Double, least: Float, most: Float): Float =
            (least + Span.NATURAL.fractionOf(largeness) * (most - least)).toFloat()

        /**
         * A point on [Span.NATURAL] drawn from this Age's seed, for an axis no word bounded.
         *
         * Salted per axis, so an Age's curtain is not as bright as it is wide as it is frequent — one draw
         * shared between three would make every undescribed aurora sit on a diagonal.
         */
        private fun drawn(seed: Long, salt: Long, band: Span): Double =
            band.least + XoroshiroRandomSource(seed xor salt).nextDouble() * band.width

        /**
         * The bands an unsaid axis is drawn from, in [Span.NATURAL]'s own terms.
         *
         * Narrower than what a word can ask for, and deliberately: the far ends of each axis are what a
         * writer *buys*, so an Age that said nothing should never land somewhere `brilliant` could have
         * taken it.
         */
        private val DRAWN_GLOW = Span(-0.4, 0.35)
        private val DRAWN_BAND = Span(-0.3, 0.5)
        private val DRAWN_NIGHTS = Span(-0.6, 0.1)

        /**
         * How far a named colour is pushed off grey for a bow — **much less than a curtain's**.
         *
         * A bow is added to the sky rather than laid over it, so a saturated band against a bright day
         * drives its strongest channel to one and comes out white. Pale is both what the blending can
         * render and what a real bow looks like.
         */
        private const val A_BOW_IS_ADDED = 0.4f

        private const val FAINTEST_BOW = 0.4f
        private const val BRIGHTEST_BOW = 1.8f

        /** Rare for a bow is still often: the geometry and the weather are already most of the rarity. */
        private const val RAREST_DAYS = 0.2f
        private const val EVERY_DAY = 1.0f

        /** How far either way a writer may bend an Age's rain, about [Rainbow.WATERS_OWN] in the middle. */
        private const val NARROWEST_BOW = 24.0f
        private const val WIDEST_BOW = 66.0f

        private const val NO_RAIN_AT_ALL = 0.0f
        private const val ALL_THE_RAIN = 1.0f

        /** Leans frequent, the geometry having already ruled out the middle of every day. */
        private val DRAWN_BOW_DAYS = Span(-0.1, 0.5)

        private const val BOW_GLOW_SALT = 0xB0_0060L
        private const val BOW_DAYS_SALT = 0xB0_0DA75L

        private const val GLOW_SALT = 0x0A17_60L
        private const val SIZE_SALT = 0x0A17_512EL
        private const val NIGHTS_SALT = 0x0A17_1416L

    }
}
