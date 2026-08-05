package co.voik.agesandtheart.age.aspect

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.attribute.EnvironmentAttributeMap
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import co.voik.agesandtheart.math.Rgba
import co.voik.agesandtheart.sky.Look
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

    /** How high the clouds sit, on the same argument: one number a word bends. */
    val CEILING = Parameter.ranged("ceiling")

    /**
     * The colour of the water — **what you see through it**, since that is what vanilla lets an Age
     * decide. The surface tint lives on `BiomeSpecialEffects` and needs a biome authored to move, which is
     * what `agesandtheart:plasma` is; the fog underwater is an attribute and needs nothing.
     *
     * One knob rather than three, on the same argument as [HAZE]: there is no world worth writing where
     * the water is one colour and the water's fog another.
     */
    val WATER = colour("water")

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
    fun settle(level: ServerLevel, options: Options, salt: Long) {
        val everywhere = airIn(options, salt, biome = null)
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
        water = colourOf(options, WATER, biome),
        tint = colourOf(options, TINT, biome),
        motes = options.of(MOTES, biome).takeUnless { it == AS_EVER },
        haze = options.steer(HAZE, salt, biome)?.let(Span.NATURAL::fractionOf)?.toFloat(),
        ceiling = options.steer(CEILING, salt, biome)?.let(Span.NATURAL::fractionOf)?.toFloat(),
    )

    /** Every biome any dial of this aspect was confined to, visual or not. */
    fun cornersOf(options: Options): List<Identifier> =
        listOf(DAYLIGHT, SUNBURN, EVAPORATION, SKY, FOG, CLOUD, WATER, TINT, MOTES, HAZE, CEILING)
            .flatMap(options::confinedIn)
            .distinct()

    private fun colourOf(options: Options, parameter: Parameter, biome: Identifier?): Rgba? =
        Colour.named(options.of(parameter, biome))

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
}
