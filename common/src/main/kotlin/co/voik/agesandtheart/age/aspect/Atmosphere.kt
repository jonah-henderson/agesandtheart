package co.voik.agesandtheart.age.aspect

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.attribute.EnvironmentAttributeMap
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import co.voik.runtimelevels.Rgba
import co.voik.runtimelevels.sky.Look
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
 *
 * **Only the half a server can decide is here.** `ServerLevel.setEnvironmentAttributes` is public, so the
 * gameplay attributes — whether monsters burn, whether water boils away, how much light the sky gives —
 * need nothing else. The *visual* half is built into `ClientLevel`'s own constructor from a private final
 * field, so a green sky wants a mixin and a payload, which is the route `SkySpec` already proved and is
 * not built.
 */
object Atmosphere {

    /**
     * How much light the sky gives, from a world under an unbroken overcast to one that never sees day.
     *
     * Ranged, because it is a quantity a word bends rather than a state a word picks — `sunless` bounds it
     * at the bottom exactly as `arid` bounds a climate axis.
     */
    val DAYLIGHT = Parameter.ranged("daylight")

    /** Whether the sun burns what walks in it — an Age where it does not is an Age monsters keep. */
    val SUNBURN = Parameter("sunburn", AS_EVER, "never", "always")

    /** Whether standing water boils away, as it does in the nether. */
    val EVAPORATION = Parameter("evaporation", AS_EVER, "always")

    /**
     * The three the eye sees, each taking one of [Colour]'s nine.
     *
     * **A colour and not a hex triple** — §3.2's rule at its least arguable: "green" is a thing a person
     * says about a sky where `#6DB563` is a fact about our arithmetic.
     */
    val SKY = colour("sky")
    val FOG = colour("fog")
    val CLOUD = colour("cloud")

    /**
     * How close the fog closes in — **one knob for what vanilla holds as two distances**, since a writer
     * says "thick" rather than "starting at 32 and ending at 96". The two are derived, and the granular
     * pair stays available the day a word wants it.
     */
    val HAZE = Parameter.ranged("haze")

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
    val RAINFALL = Parameter.ranged("rainfall")
    val THUNDER = Parameter.ranged("thunder")

    /** How high the clouds sit, on the same argument: one number a word bends. */
    val CEILING = Parameter.ranged("ceiling")

    /**
     * How far you see underwater — [HAZE]'s sibling, and named for the same direction it obscures.
     *
     * The water's *colour* is deliberately not here, and this is what remains reachable without it: the
     * colour would only ever have moved the fog and left the surface vanilla blue, where a distance moves
     * the one thing a swimmer actually experiences.
     */
    val MURK = Parameter.ranged("murk")

    /**
     * What colour the light itself is — a world *lit* red, which is a different thing from a red sky.
     *
     * One knob over both the sky's light and the ambient, because a writer who says the light is green
     * means all of it.
     */
    val TINT = colour("tint")

    /**
     * What hangs in the air — vanilla's own particles, named. Populative in spirit and a dial in shape: an
     * Age has one kind of dust in it, and the day that stops being true this becomes a claim.
     */
    val MOTES = Parameter("motes", listOf(AS_EVER) + Motes.ALL)

    private fun colour(name: String) = Parameter(name, listOf(AS_EVER) + Colour.ALL)

    /**
     * This Age's own layer laid over the ones vanilla built, or the system untouched where the sentence
     * said nothing about the air.
     *
     * Applied when the Age opens rather than written into its recipe's generator: an attribute is a fact
     * about the *level* rather than about the ground, and nothing in generation reads one.
     */
    fun settle(level: ServerLevel, options: Options, sky: Options, salt: Long) {
        val everywhere = airIn(options, salt, biome = null) + lightFrom(sky)
        val corners = cornersOf(options).associateWith { airIn(options, salt, it) }
        if (everywhere.isEmpty() && corners.all { it.value.isEmpty() }) return
        val system = EnvironmentAttributeSystem.builder().addDefaultLayers(level)
        if (everywhere.isNotEmpty()) {
            val air = EnvironmentAttributeMap.builder()
            everywhere.forEach { it.into(air) }
            system.addConstantLayer(air.build())
        }
        // **A corner of the world is a positional layer**, which is the same mechanism vanilla uses to let
        // biomes provide attributes at all — it is handed a position and asks the level what is there.
        for ((biome, air) in corners) air.forEach { it.onto(system, level, biome) }
        level.setEnvironmentAttributes(system.build())
    }

    /**
     * What the *eye* sees, which the server cannot decide alone: `ClientLevel` builds its own attribute
     * system in its constructor from a private final field, so these cross on a payload and are installed
     * client-side. See [co.voik.agesandtheart.sky.LookPayload].
     */
    fun lookIn(options: Options, salt: Long, biome: Identifier? = null): Look = Look(
        sky = colourOf(options, SKY, biome),
        fog = colourOf(options, FOG, biome),
        cloud = colourOf(options, CLOUD, biome),
        tint = colourOf(options, TINT, biome),
        motes = options.of(MOTES, biome).takeUnless { it == AS_EVER },
        murk = options.steer(MURK, salt, biome)?.let(Span.NATURAL::fractionOf)?.toFloat(),
        haze = options.steer(HAZE, salt, biome)?.let(Span.NATURAL::fractionOf)?.toFloat(),
        ceiling = options.steer(CEILING, salt, biome)?.let(Span.NATURAL::fractionOf)?.toFloat(),
    )

    /**
     * The look an Age's **sky switch** insists on, under whatever its air was told.
     *
     * `Sky.SKYLIGHT` already reaches the light *level* through [settle], and that half worked: a lightless
     * Age spawns monsters at noon and grows nothing that needs sky. What it did not reach is the half you
     * can see — the sky stayed blue with clouds in it, which reads as broad daylight over a world the game
     * considers pitch dark (Jonah, 2026-08-05, walked).
     *
     * So the same one statement reaches this layer too: no skylight means a black sky, black fog, no cloud
     * and no light of its own. **A floor, not a setting** — a writer who said `lightless` *and* named a fog
     * colour gets the colour they asked for, because [Look.over] keeps whatever was said.
     */
    fun unlitLook(sky: Options): Look =
        if (sky.of(Sky.SKYLIGHT) != "none") Look.NOTHING
        else Look(sky = STARLESS, fog = STARLESS, cloud = STARLESS, tint = STARLESS)

    /** Not quite black: pure zero reads as a hole cut in the world rather than as a dark sky. */
    private val STARLESS = Rgba(0.02f, 0.02f, 0.03f)

    /** Every biome any dial of this aspect was confined to, visual or not. */
    fun cornersOf(options: Options): List<Identifier> =
        listOf(DAYLIGHT, SUNBURN, EVAPORATION, SKY, FOG, CLOUD, TINT, MOTES, HAZE, CEILING, MURK)
            .flatMap(options::confinedIn)
            .distinct()

    private fun colourOf(options: Options, parameter: Parameter, biome: Identifier?): Rgba? =
        Colour.named(options.of(parameter, biome))

    /**
     * What an Age's *sky* does to the air — which is one switch, and it has to be said in two places.
     *
     * `Sky.SKYLIGHT` picks a dimension type that stops skylight **propagating**; this stops the sky
     * **giving** any, which is the half a player sees. Walked without it and the world still looked lit at
     * noon, because the level a lit world reads is the attribute and the timeline keeps it at full
     * (Jonah, 2026-08-05). They are one statement, so a writer says it once and both layers hear it.
     *
     * Not a `sets` on the word: the two live in different aspects and a narrowing word has its say in the
     * one section it was laid in, so `sky lightless` would otherwise reach only half of what it means.
     */
    private fun lightFrom(sky: Options): List<Asked<*>> =
        if (sky.of(Sky.SKYLIGHT) == "none") listOf(Asked(EnvironmentAttributes.SKY_LIGHT_LEVEL, NO_DAYLIGHT)) else emptyList()

    /** Every attribute the sentence set **where [biome] is the ground**, or Age-wide where it is null. */
    private fun airIn(options: Options, salt: Long, biome: Identifier?): List<Asked<*>> = buildList {
        options.steer(DAYLIGHT, salt, biome)?.let {
            add(Asked(EnvironmentAttributes.SKY_LIGHT_LEVEL, Span.NATURAL.fractionOf(it).toFloat() * FULL_DAYLIGHT))
        }
        burning(options, biome)?.let { add(Asked(EnvironmentAttributes.MONSTERS_BURN, it)) }
        if (options.of(EVAPORATION, biome) != AS_EVER) add(Asked(EnvironmentAttributes.WATER_EVAPORATES, true))
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

    /** Null where the writer left it as it ever was, which is the answer that lays no layer. */
    private fun burning(options: Options, biome: Identifier?): Boolean? = when (options.of(SUNBURN, biome)) {
        "never" -> false
        "always" -> true
        else -> null
    }

    /** What an option reads as when a writer left an attribute alone — vanilla's own answer, whatever it is. */
    const val AS_EVER = "as_ever"

    /** Vanilla's sky light at noon, which is the top of the axis. */
    private const val FULL_DAYLIGHT = 15f

    private const val NO_DAYLIGHT = 0f
}
