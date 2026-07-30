package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Span
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable

/**
 * What an Age is *like*, said as climate rather than as a list of biomes (design §3.2, §4.4).
 *
 * This is the **vague** half of biome authoring, and the half most sentences will use. "A hot, dry world"
 * names nothing in particular; it shifts the climate field, and vanilla's own climate-to-biome table then
 * yields deserts, badlands and savannas — with their features, mobs, structures, surface rules and skies —
 * without a single biome having been named. [BiomePreference] is the exact half beside it.
 *
 * That split *is* the precision ladder (§4.4) rather than an analogy to it: an evocative or restrictive word
 * shifts weights across every candidate, which is exactly what bounding an axis here does, and only an exact
 * word names a value. It is also cheap, because `AgeBiomeSource` already computes the six climate parameters
 * itself — the inert-sampler problem forced us to own that path, and this is the dividend.
 *
 * **Only two of vanilla's six parameters are here, and where each of the others went is the useful part.** Each
 * of the six does two jobs: it feeds the noise router that shapes terrain, *and* it indexes the biome table. An
 * Age takes its shape from a [co.voik.agesandtheart.worldgen.field.TerrainField], so only the second job
 * transfers — and how much of it survives differs sharply per axis.
 *
 * - **Temperature and humidity** partition the table into whole families (snowy → taiga → plains → savanna
 *   → desert). That survives intact, and is what makes "a hot, dry world" work. These two are the whole of what
 *   a writer's climate vocabulary reaches.
 * - **Weirdness** picks the odd variant over the ordinary one within a family. Real, but *conceptually it is
 *   about biomes rather than about climate* (Jonah, 2026-07-29) — "even though it's implemented as part of
 *   climate, that's where its vocabulary needs to go, even if we then later interpret it back to climate". So it
 *   left this enum when climate became an aspect, and **Biomes picks it up in step 5**; `familiar`/`unusual`/
 *   `exceptional` are unsayable until then. It is the clearest case of §3.1's rule that aspects are machinery
 *   and vocabulary is language, with the mapping held internally.
 * - **Continentalness and erosion were cut** (Jonah, 2026-07-27, after walking them and seeing nothing) and are
 *   now expected back as **terrain** modifiers rather than climate ones. They describe *shape* — how much sea,
 *   how much relief — and an Age's shape does not come from climate. Worse than invisible, continentalness is
 *   incoherent here: shifting it toward ocean returns ocean biomes over ground that stayed dry hills, because
 *   nothing made an ocean. Their home is a terrain preset that delegates to vanilla's noise generation, where
 *   they would finally have something to shape; *"an age made of small islands implies both noise generation
 *   and low continentalness"* (Jonah), which needs no new mechanism — one word choosing that preset and setting
 *   its parameter, exactly as `basalt` already sets `stone`.
 */
enum class ClimateAxis(val key: String) : StringRepresentable {
    /** Cold to hot. */
    TEMPERATURE("temperature"),

    /** Dry to wet. */
    HUMIDITY("humidity"),
    ;

    /**
     * The knob a writer's words bound — a [Span] rather than one of a list of named steps.
     *
     * **This carried a ladder (`cool`/`cold`/`warm`/`hot`) until 2026-07-29 and no longer does.** The ladder had
     * to belong to *something* a writer could ask for, and everything that could hold it needed a preset per
     * combination of axes. Ranges on the words are linear where that was combinatorial, and they keep an axis's
     * numbers next to the other axes the same word speaks about, which is the whole of why a vague word cannot
     * produce an incoherent climate. A named step survives only as something a word may compile down to.
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
 * **A [Span] per axis, replacing an offset-and-narrowing pair.** The old shape moved the whole axis and
 * optionally squeezed it, which needed two numbers to say what one interval says better: "between 0.4 and 0.6"
 * *is* both a shift and a narrowing, and it composes with another word's interval in one obvious way where an
 * offset and a narrowing each needed a rule. It also removed the last place two climate words could produce
 * §3.3's temperate mush, because intervals either overlap — and broaden — or they do not, and where they do not
 * the aspect fractures instead (see [co.voik.agesandtheart.age.aspect.Parameter.Kind.RANGED]).
 *
 * An axis nobody spoke about is simply absent, so the value passes through untouched. That is what lets this
 * whole migration cost every existing Age nothing at all.
 *
 * Depth is deliberately not here: it is the one climate parameter an Age answers for itself ([ClimateDepth]),
 * and bending it would let a surface biome claim the rock below or a cave biome reach daylight.
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
