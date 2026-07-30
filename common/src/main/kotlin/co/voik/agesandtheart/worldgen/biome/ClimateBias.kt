package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Span
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable

/**
 * What an Age is *like*, said as climate rather than as a list of biomes (design §3.2, §4.4) — the
 * **vague** half of biome authoring, where [BiomePreference] is the exact half. "A hot, dry world" names
 * nothing in particular; it shifts the climate field, and vanilla's table yields deserts and savannas with
 * their features, mobs, structures and skies.
 *
 * **Only two of vanilla's six parameters are here.** Each of the six feeds the noise router that shapes
 * terrain *and* indexes the biome table, and an Age takes its shape from a
 * [co.voik.agesandtheart.worldgen.field.TerrainField], so only the second job transfers:
 *
 * - **Temperature and humidity** partition the table into whole families, which survives intact.
 * - **Weirdness** picks the odd variant within a family. Real, but conceptually a *biome* idea, so its
 *   vocabulary belongs to Biomes rather than here.
 * - **Continentalness and erosion were cut**, and are expected back as *terrain* modifiers: they describe
 *   shape, and shifting continentalness toward ocean returns ocean biomes over dry hills, because nothing
 *   made an ocean.
 */
enum class ClimateAxis(val key: String) : StringRepresentable {
    /** Cold to hot. */
    TEMPERATURE("temperature"),

    /** Dry to wet. */
    HUMIDITY("humidity"),
    ;

    /**
     * The knob a writer's words bound — a [Span] rather than one of a list of named steps. A ladder needed
     * a preset per combination of axes; ranges on the words are linear where that was combinatorial, and
     * they keep an axis's numbers beside the other axes the same word speaks about, which is why a vague
     * word cannot produce an incoherent climate.
     */
    val parameter: Parameter get() = Parameter.ranged(key)

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<ClimateAxis> = StringRepresentable.fromEnum(ClimateAxis::values)
    }
}

/**
 * Every axis an Age was told to stay within, together — the whole of "a hot, dry world" as data.
 *
 * **A [Span] per axis**, since "between 0.4 and 0.6" *is* both a shift and a narrowing, and it composes
 * with another word's interval in one obvious way: intervals either overlap — and broaden — or they do
 * not, and where they do not the aspect fractures
 * (see [co.voik.agesandtheart.age.aspect.Parameter.Kind.RANGED]). That leaves nowhere for §3.3's
 * temperate mush. An axis nobody spoke about is absent, so its value passes through untouched.
 *
 * Depth is deliberately not here: it is the one parameter an Age answers for itself ([ClimateDepth]), and
 * bending it would let a surface biome claim the rock below or a cave biome reach daylight.
 */
data class ClimateBias(private val byAxis: Map<ClimateAxis, Span> = emptyMap()) {
    /** [value] on [axis], as this Age reads it — untouched where nothing was said. */
    fun shift(axis: ClimateAxis, value: Float): Float = byAxis[axis]?.remap(value) ?: value

    /** Whether anything was said at all, so a generator can skip a bias that would change nothing. */
    val isIdle: Boolean get() = byAxis.isEmpty()

    companion object {
        val NONE = ClimateBias()

        private val SPAN_CODEC: Codec<Span> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("least").forGetter(Span::least),
                Codec.DOUBLE.fieldOf("most").forGetter(Span::most),
            ).apply(instance, ::Span)
        }

        val CODEC: Codec<ClimateBias> = Codec.unboundedMap(ClimateAxis.CODEC, SPAN_CODEC)
            .xmap(::ClimateBias, ClimateBias::byAxis)
    }
}
