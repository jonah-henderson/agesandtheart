package co.voik.agesandtheart.age.aspect

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.attribute.EnvironmentAttributeMap
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes

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
     * This Age's own layer laid over the ones vanilla built, or the system untouched where the sentence
     * said nothing about the air.
     *
     * Applied when the Age opens rather than written into its recipe's generator: an attribute is a fact
     * about the *level* rather than about the ground, and nothing in generation reads one.
     */
    fun settle(level: ServerLevel, options: Options, salt: Long) {
        val asked = EnvironmentAttributeMap.builder()
        var saidAnything = false
        options.steer(DAYLIGHT, salt)?.let { dial ->
            asked.set(EnvironmentAttributes.SKY_LIGHT_LEVEL, Span.NATURAL.fractionOf(dial).toFloat() * FULL_DAYLIGHT)
            saidAnything = true
        }
        burning(options)?.let {
            asked.set(EnvironmentAttributes.MONSTERS_BURN, it)
            saidAnything = true
        }
        if (options.of(EVAPORATION) != AS_EVER) {
            asked.set(EnvironmentAttributes.WATER_EVAPORATES, true)
            saidAnything = true
        }
        if (!saidAnything) return
        level.setEnvironmentAttributes(
            EnvironmentAttributeSystem.builder()
                .addDefaultLayers(level)
                .addConstantLayer(asked.build())
                .build(),
        )
    }

    /** Null where the writer left it as it ever was, which is the answer that lays no layer. */
    private fun burning(options: Options): Boolean? = when (options.of(SUNBURN)) {
        "never" -> false
        "always" -> true
        else -> null
    }

    /** What an option reads as when a writer left an attribute alone — vanilla's own answer, whatever it is. */
    const val AS_EVER = "as_ever"

    /** Vanilla's sky light at noon, which is the top of the axis. */
    private const val FULL_DAYLIGHT = 15f
}
