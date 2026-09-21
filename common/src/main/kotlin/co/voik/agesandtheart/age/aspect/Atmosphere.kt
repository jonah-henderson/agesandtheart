package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeTemplate
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.attribute.EnvironmentAttributeMap
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Look
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier

/**
 * What the air does to you (design §3.1, vanilla's `EnvironmentAttributeMap`) — **whatever the dimension
 * and its biomes would do**, until a sentence says otherwise.
 *
 * An aspect of its own rather than parameters on [Biomes], because it is not a population: it says nothing
 * about which biomes an Age holds, only what standing in one is like. Folding it in would make a
 * population carry a large payload that is not a population, which is the shape §3.1 has been moving away
 * from.
 *
 * **The system is a stack of layers**, each modifying the value the one below produced — a constant layer
 * for the dimension, a positional one for the biomes, then the timeline and the weather. An Age's own
 * answer is one more constant layer **on top**, which is why saying nothing costs nothing: an empty layer
 * changes no value at all.
 */
object Atmosphere {

    /** Whether standing water boils away, as it does in the nether. */
    val EVAPORATION = Parameter.flag("evaporation", help = "Whether standing water boils away, as it does in the nether.").perBiome()

    /**
     * The three the eye sees, each taking one of [Colour]'s nine.
     *
     * **A colour and not a hex triple** — §3.2's rule at its least arguable: "green" is a thing a person
     * says about a sky where `#6DB563` is a fact about our arithmetic.
     */
    val SKY = colour("colour", "The colour of the sky itself.")
    val FOG = colour("colour", "The colour of the air and its fog.")
    val CLOUD = colour("colour", "The colour of the clouds.")

    /**
     * How close the fog closes in — **one parameter for what vanilla holds as two distances**, since a writer
     * says "thick" rather than "starting at 32 and ending at 96". The two are derived, and the granular
     * pair stays available the day a word wants it.
     */
    val HAZE = Parameter.ranged(
        "haze",
        help = "How close the fog closes in.",
        landmarks = listOf(
                Parameter.Landmark(-1.0, "clear to the horizon"),
                Parameter.Landmark(0.0, "ordinary"),
                Parameter.Landmark(1.0, "fog at arm's length"),
            ),
        ).perBiome()

    /**
     * How much of the time it rains, and how much of *that* is thunder.
     *
     * **Not attributes**, unlike everything else here — they steer the Age's own `WeatherData`
     * ([co.voik.agesandtheart.age.phenomena.AgeWeather]), which only became a thing an Age has at all once
     * 26.1's shared schedule was diverted. They live on `Atmosphere` because that is where a writer would
     * look for them and because `evaporation` is already the same kind of statement, and they are applied
     * from the weather module rather than by [settle] — the same split `Sky.SKYLIGHT` already has.
     *
     * **Deliberately independent of any phenomenon** (Jonah, 2026-08-05). A tempest wants rain and thunder
     * and so insists on them, but so might a drowned Age with no lightning in it at all, and neither should
     * have to be the other.
     */
    val RAINFALL = Parameter.ranged(
        "rainfall",
        help = "How much of the time it rains.",
        landmarks = listOf(
                Parameter.Landmark(-1.0, "never"),
                Parameter.Landmark(0.0, "ordinary"),
                Parameter.Landmark(1.0, "almost always"),
            ),
        )
    val THUNDER = Parameter.ranged(
        "thunder",
        help = "How much of the rain is thunder.",
        landmarks = listOf(
                Parameter.Landmark(-1.0, "never"),
                Parameter.Landmark(0.0, "ordinary"),
                Parameter.Landmark(1.0, "every storm"),
            ),
        )

    /** How high the clouds sit, on the same argument: one number a word bends. */
    val CEILING = Parameter.ranged(
        "ceiling",
        help = "How high the clouds sit.",
        landmarks = listOf(
                Parameter.Landmark(-1.0, "low overhead"),
                Parameter.Landmark(0.0, "vanilla's", isVanilla = true),
                Parameter.Landmark(1.0, "far above"),
            ),
        ).perBiome()

    /**
     * How far you see underwater — [HAZE]'s sibling, and named for the same direction it obscures.
     *
     * The water's *colour* is deliberately not here, and this is what remains reachable without it: the
     * colour would only ever have moved the fog and left the surface vanilla blue, where a distance moves
     * the one thing a swimmer actually experiences.
     */
    val MURK = Parameter.ranged(
        "murk",
        help = "How far you can see underwater.",
        landmarks = listOf(
                Parameter.Landmark(-1.0, "clear water"),
                Parameter.Landmark(0.0, "ordinary"),
                Parameter.Landmark(1.0, "you can barely see"),
            ),
        ).perBiome()

    /**
     * What colour the light itself is — a world *lit* red, which is a different thing from a red sky.
     *
     * One parameter over both the sky's light and the ambient, because a writer who says the light is green
     * means all of it.
     */
    val TINT = colour("tint", "The colour of the light everything is lit by.")

    /**
     * What hangs in the air — vanilla's own particles, named. Populative in spirit and a dial in shape: an
     * Age has one kind of dust in it, and the day that stops being true this becomes a claim.
     */
    val MOTES = Parameter(
        "motes",
        listOf(Parameter.DEFAULT) + Motes.ALL,
        help = "What hangs in the air.",
    ).perBiome()

    private fun colour(name: String, help: String) =
        Parameter(name, listOf(Parameter.DEFAULT) + Colour.ALL, help = help).perBiome()

    /**
     * What the grass is tinted, and what the leaves are — **the ground rather than the air**, kept here
     * because this is where a [Look] is assembled and splitting the parameters from [lookIn] would put the
     * two halves of one answer in two files.
     *
     * Sited like every other colour here, which is the whole of `purple grass in swamp`.
     */
    val GRASSCOLOUR = colour("colour", "The colour of the grass.")

    val LEAFCOLOUR = colour("colour", "The colour of the leaves.")

    /**
     * This Age's own layer laid over the ones vanilla built, or the system untouched where the sentence
     * said nothing about the air.
     *
     * Applied when the Age opens rather than written into its recipe's generator: an attribute is a fact
     * about the *level* rather than about the ground, and nothing in generation reads one.
     */
    fun settle(level: ServerLevel, salt: Long, parts: AgeParts, template: AgeTemplate) {
        val options = parts.optionsFor(Aspect.CLIMATE)
        val everywhere = airIn(options, salt, biome = null) + lightFrom(parts, template)
        val corners = cornersOf(parts).associateWith { airIn(options, salt, it) }
        val world = worldItWasWrittenOver(level, template)
        val saysNothing = everywhere.isEmpty() && corners.all { it.value.isEmpty() }
        if (world == null && saysNothing) return
        val system = EnvironmentAttributeSystem.builder().addDefaultLayers(level)
        // Under the Age's own, since it is what the world was like before the book said anything.
        world?.let { BorrowedAir.played(system, it) }
        if (everywhere.isNotEmpty()) {
            val air = EnvironmentAttributeMap.builder()
            everywhere.forEach { it.into(air) }
            system.addConstantLayer(air.build())
        }
        // **A corner of the world is a positional layer**, which is the same mechanism vanilla uses to let
        // biomes provide attributes at all — it is handed a position and asks the level what is there.
        for ((biome, air) in corners) air.forEach { it.onto(system, level, biome) }
        // **The field, not vanilla's setter for it.** A level builds its attributes when it is built and
        // offers no way to set them afterwards, which is exactly what an Age needs — its look is the
        // recipe's, not a dimension file's. The setter that would do it is `@Deprecated
        // @VisibleForTesting`, and a hook kept for tests can go at any release; the field cannot, because
        // the system is read off it every tick. So the widener opens the field and this writes it.
        level.environmentAttributes = system.build()
    }

    /**
     * What the *eye* sees, which the server cannot decide alone: `ClientLevel` builds its own attribute
     * system in its constructor from a private final field, so these cross on a payload and are installed
     * client-side. See [co.voik.ephemeris.sky.LevelLookPayload].
     */
    fun lookIn(parts: AgeParts, salt: Long, biome: Identifier? = null): Look {
        val air = parts.optionsFor(Aspect.AIR)
        val vault = parts.optionsFor(Aspect.SKY)
        val water = parts.optionsFor(Aspect.WATERS)
        fun band(options: Options, parameter: Parameter) =
            options.steer(parameter, salt, biome)?.let(Span.NATURAL::fractionOf)?.toFloat()
        return Look(
            sky = colourOf(vault, SKY, biome),
            fog = colourOf(air, FOG, biome),
            cloud = colourOf(parts.optionsFor(Aspect.CLOUD), CLOUD, biome),
            tint = colourOf(air, TINT, biome),
            motes = air.of(MOTES, biome).takeUnless { it == Parameter.DEFAULT },
            murk = band(water, MURK),
            haze = band(air, HAZE),
            ceiling = band(vault, CEILING),
            grass = colourOf(parts.optionsFor(Aspect.GRASS), GRASSCOLOUR, biome),
            // **One word for every leaf.** Litter and dead brush are leaves that have dried, and a world
            // with purple trees over green leaf litter is the same mistake as purple grass under green
            // leaves — one level further down.
            foliage = colourOf(parts.optionsFor(Aspect.LEAVES), LEAFCOLOUR, biome),
            dryFoliage = colourOf(parts.optionsFor(Aspect.LEAVES), LEAFCOLOUR, biome),
        )
    }

    /**
     * The look an Age with nothing lighting it insists on, under whatever its air was told.
     *
     * [lightFrom] already reaches the light *level*, and that half worked: a lightless Age spawns monsters
     * at noon and grows nothing that needs sky. What it did not reach is the half you can see — the sky
     * stayed blue with clouds in it, which reads as broad daylight over a world the game considers pitch
     * dark (Jonah, 2026-08-05, walked).
     *
     * **A floor, not a setting** — a writer who said `lightless` *and* named a fog colour gets the colour
     * they asked for, because [Look.over] keeps whatever was said.
     *
     * **And a floor only where the world underneath has none.** The dark is two statements, and they are
     * not the same kind of thing. That nothing is overhead is a *fact*, true of any world shut or unlit,
     * and it takes the overcast away. That the air is nearly black is a *stand-in* for the blue one the
     * overworld's biomes would otherwise paint — and a template already dark has real answers where this
     * has one colour, so laying it over the nether flattened crimson, warped and soul-sand fog to a single
     * grey.
     */
    fun unlitLook(parts: AgeParts, template: AgeTemplate): Look {
        if (!Sky.isLightless(parts)) return Look.NOTHING
        val nothingIsUpThere = Look(cloud = NO_CLOUD)
        if (Sky.isLightless(template.world())) return nothingIsUpThere
        return nothingIsUpThere.copy(sky = STARLESS, fog = STARLESS, tint = STARLESS)
    }

    /** Not quite black: pure zero reads as a hole cut in the world rather than as a dark sky. */
    private val STARLESS = Rgba(0.02f, 0.02f, 0.03f)

    /**
     * **No cloud at all**, which is a zero *alpha* rather than a dark colour — the cloud pass is skipped on
     * one and drawn on the other, and there is no separate switch for it.
     *
     * A near-black `STARLESS` here painted the clouds of a sealed Age dark instead of removing them, and
     * it overrode the `#00000000` the dimension type ships for exactly this. The look says the whole thing
     * rather than half of it and leaves the file to agree.
     */
    private val NO_CLOUD = Rgba(0.0f, 0.0f, 0.0f, 0.0f)

    /**
     * Every biome any dial of this aspect was confined to, visual or not.
     *
     * Asked of the aspect rather than of a list written here: which parameters may be sited is the parameter's
     * own answer now ([Parameter.confinable]), and the list this replaced had to be kept in step by hand.
     */
    fun cornersOf(parts: AgeParts): List<Identifier> =
        Aspect.entries.flatMap { aspect ->
            val options = parts.optionsFor(aspect)
            val names = aspect.confinableParameters.map(Parameter::name) + listOfNotNull(aspect.confinablePool?.name)
            names.flatMap(options::confinedIn)
        }.distinct()

    private fun colourOf(options: Options, parameter: Parameter, biome: Identifier?): Rgba? =
        Colour.named(options.of(parameter, biome))

    /**
     * How much light the sky gives — **derived from what is overhead, never written** (world model §4).
     *
     * `SKY_LIGHT_LEVEL` is a gameplay rule and not a look: read against 26.1.2, its only reader is
     * `Level.updateSkyBrightness`, which turns it into `skyDarken`, which decides whether monsters spawn,
     * whether saplings grow, whether grass spreads and whether ice melts. **The lightmap does not read it**
     * — `LightmapRenderStateExtractor` takes `SKY_LIGHT_FACTOR`, `SKY_LIGHT_COLOR` and `AMBIENT_LIGHT_COLOR`
     * — which is the whole reason a lightless Age used to look like noon while the game held it pitch dark.
     *
     * So a writer never sets it. A world with a sun in it has daylight and one with none does not, and the
     * `skylight` switch that picks the dimension type says the same thing from the other side.
     */
    private fun lightFrom(parts: AgeParts, template: AgeTemplate): List<Asked<*>> {
        val nothingIsUpThere = Sky.isLightless(parts)
        if (!nothingIsUpThere) return emptyList()
        // **Unless the world it was written over is already dark**, which answers this better than a zero:
        // the nether's own is 4, a dim constant, and it is why it is never truly black in there. The same
        // deferral [unlitLook] makes about the colour of the air.
        if (Sky.isLightless(template.world())) return emptyList()
        return listOf(Asked(EnvironmentAttributes.SKY_LIGHT_LEVEL, NO_DAYLIGHT))
    }

    /**
     * The attributes of the world [level] was written over, or null where it is already wearing that
     * world's own type and they are in the stack below already.
     */
    private fun worldItWasWrittenOver(level: ServerLevel, template: AgeTemplate): EnvironmentAttributeMap? {
        if (level.dimensionTypeRegistration().`is`(template.dimensionType)) return null
        return level.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)
            .getOrThrow(template.dimensionType).value().attributes()
    }

    /** Every attribute the sentence set **where [biome] is the ground**, or Age-wide where it is null. */
    private fun airIn(options: Options, salt: Long, biome: Identifier?): List<Asked<*>> = buildList {
        if (options.isTrue(EVAPORATION, biome)) add(Asked(EnvironmentAttributes.WATER_EVAPORATES, true))
    }

    /**
     * One attribute and the value asked of it, kept together so the pair stays **typed**.
     *
     * A map of attribute to value cannot: `EnvironmentAttribute<Value>` is generic, so a map keyed by one
     * is star-projected and every value in it is an `Any` the builder will not take. Holding the pair in a
     * class parameterised on the same `Value` is what lets `set` and `addPositionalLayer` be called at all.
     */
    private class Asked<Value : Any>(
        private val attribute: EnvironmentAttribute<Value>,
        private val value: Value,
    ) {
        fun into(air: EnvironmentAttributeMap.Builder) {
            air.set(attribute, value)
        }

        /** The same value, but only where [biome] is what the ground holds — everywhere else, what was below. */
        fun onto(system: EnvironmentAttributeSystem.Builder, level: ServerLevel, biome: Identifier) {
            system.addPositionalLayer(attribute) { below, at, _ ->
                val here = level.getBiome(BlockPos.containing(at)).unwrapKey().orElse(null)?.identifier()
                if (here == biome) value else below
            }
        }
    }

    private const val NO_DAYLIGHT = 0f
}
