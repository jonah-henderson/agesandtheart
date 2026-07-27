package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.age.slot.Parameter
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable
import kotlin.math.abs

/**
 * What an Age is *like*, said as climate rather than as a list of biomes (design §3.2, §4.4).
 *
 * This is the **vague** half of biome authoring, and the half most sentences will use. "A hot, dry world"
 * names nothing in particular; it shifts the climate field, and vanilla's own climate-to-biome table then
 * yields deserts, badlands and savannas — with their features, mobs, structures, surface rules and skies —
 * without a single biome having been named. [BiomePreference] is the exact half beside it.
 *
 * That split *is* the precision ladder (§4.4) rather than an analogy to it: an evocative or restrictive word
 * shifts weights across every candidate, which is exactly what an offset here does, and only an exact word
 * names a value. It is also cheap, because `AgeBiomeSource` already computes the six climate parameters
 * itself — the inert-sampler problem forced us to own that path, and this is the dividend.
 *
 * **Three of vanilla's six parameters are deliberately absent, and the reason generalises.** Each of the six
 * does two jobs: it feeds the noise router that shapes terrain, *and* it indexes the biome table. An Age
 * takes its shape from a [co.voik.agesandtheart.worldgen.field.TerrainField], so only the second job
 * transfers — and how much of it survives differs sharply per axis.
 *
 * - **Temperature and humidity** partition the table into whole families (snowy → taiga → plains → savanna
 *   → desert). That survives intact, and is what makes "a hot, dry world" work.
 * - **Weirdness** picks the odd variant over the ordinary one within a family. Subtler, but real, and it
 *   describes a property of the *biomes* rather than of the ground — so it belongs here.
 * - **Continentalness and erosion were cut** (Jonah, 2026-07-27, after walking them and seeing nothing).
 *   They describe *shape* — how much sea, how much relief — and an Age's shape does not come from climate.
 *   Worse than invisible, continentalness is incoherent: shifting it toward ocean returns ocean biomes over
 *   ground that stayed dry hills, because nothing made an ocean. `oceanic` and `jagged` are **landform**
 *   words wearing climate clothing, and their real home is a landform preset that delegates to vanilla's
 *   noise generation — which does not exist yet. See `notes/the-art-design.md` §3.2.
 */
enum class ClimateAxis(val key: String, private vararg val ladder: Pair<String, Double>) : StringRepresentable {
    /** Cold to hot. */
    TEMPERATURE("temperature", "cool" to -0.35, "cold" to -0.6, "warm" to 0.35, "hot" to 0.6),

    /** Dry to wet. */
    HUMIDITY("humidity", "dry" to -0.35, "arid" to -0.6, "damp" to 0.35, "drenched" to 0.6),

    /**
     * How often the odd variant of a biome wins over the ordinary one — old-growth over ordinary birch,
     * windswept over plain, the badlands' stranger cousins.
     *
     * The steps say how *remarkable* the world is rather than how weird, because "weirdness" is vanilla's
     * word for the noise and not a thing a writer would ever say. Note [NATURAL] and `familiar` are not the
     * same: the first is the writer saying nothing and leaving vanilla's own mix alone, the second is the
     * writer actively asking for the ordinary. `normal` was the obvious name for that and is too easily
     * read as the default it is not.
     */
    WEIRDNESS("weirdness", "familiar" to -0.4, "unusual" to 0.4, "exceptional" to 0.7),
    ;

    /**
     * Each named step and what it does.
     *
     * `by lazy` rather than built in the constructor, and not by preference: an enum entry's arguments are
     * evaluated before the companion exists, so calling a companion helper there fails to compile. The same
     * trap `Slot.presets` documents, and the reason `:common:codeccheck` exists.
     */
    val steps: Map<String, ClimateShift> by lazy {
        mapOf(NATURAL to ClimateShift()) + ladder.associate { (name, offset) ->
            // The gentler step of a pair only offsets, so a cool world keeps its warm corners; the stronger
            // one also narrows, which is the difference between "cold" and a world with no warmth in it.
            // That is the precision ladder showing up inside a single axis.
            name to ClimateShift(offset, if (abs(offset) >= NARROWS_ABOVE_THIS_OFFSET) NARROWING_OF_A_STRONG_WORD else 0.0)
        }
    }

    /** The knob a writer turns, its first option being the world left as vanilla made it. */
    val parameter: Parameter get() = Parameter(key, steps.keys.toList())

    /** What [option] does to this axis — nothing, for a name it does not know. */
    fun shiftFor(option: String): ClimateShift = steps[option] ?: ClimateShift()

    override fun getSerializedName(): String = key

    companion object {
        /** The default on every axis: whatever vanilla's climate already said. */
        const val NATURAL = "natural"

        /** Past this much shift, a word stops merely moving the world and starts homogenising it. */
        private const val NARROWS_ABOVE_THIS_OFFSET = 0.5

        /** How much variation a strong climate word takes away. Taste; expect retuning. */
        private const val NARROWING_OF_A_STRONG_WORD = 0.45

        val CODEC: Codec<ClimateAxis> = StringRepresentable.fromEnum(ClimateAxis::values)
    }
}

/**
 * What one axis was told to do: move, and optionally stop varying.
 *
 * **One shift per axis, always.** A climate axis is an enumerated *predicative* parameter (design §3.2) —
 * "the world **is** hot" conflicts with "the world **is** cold" exactly as "the rock **is** blackstone"
 * conflicts with tuff — so two words about one axis **contend**: the seed picks and the loser is charged
 * as displaced. There is no combining step, and nothing here ever merges two shifts.
 *
 * An earlier draft had opposing offsets *sum*, cancelling to a full-range world charged nothing, on the
 * grounds that §3.3 rejected bipolar axes for averaging contradictions into mush. **That was over-anxious
 * and it was wrong.** §3.3's objection is to averaging *inside one word's effect*, where the player wrote
 * something wild and got temperate nothing; two words competing is a different situation, and the settled
 * answer for it everywhere else in this design is contention priced by the instability index. Summing would
 * also have made climate the one parameter kind that behaves unlike every other predicative one.
 */
data class ClimateShift(
    /** How far along the axis the whole world moves. Zero leaves it where vanilla put it. */
    val offset: Double = 0.0,
    /**
     * How much the axis stops varying, from zero (full natural range) to one (the same everywhere).
     *
     * This is what makes "*uniformly* hot" different from "hot": the first is a world with no cold in it at
     * all, the second is a world whose cold places are merely temperate.
     */
    val narrowing: Double = 0.0,
) {
    /**
     * [value] as this Age reads it.
     *
     * Narrow first, then move: compressing toward the natural middle and *then* shifting keeps the offset
     * meaning the same thing whatever the narrowing is, where the other order would have narrowing quietly
     * eat part of the shift.
     */
    fun applyTo(value: Float): Float {
        val narrowed = value * (1.0 - narrowing.coerceIn(0.0, 1.0))
        return (narrowed + offset).coerceIn(-CLIMATE_REACH, CLIMATE_REACH).toFloat()
    }

    val isIdle: Boolean get() = offset == 0.0 && narrowing == 0.0

    companion object {
        /** Climate parameters live in [-1, 1]; going outside it only wastes the table's far corners. */
        private const val CLIMATE_REACH = 1.0

        val CODEC: Codec<ClimateShift> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.optionalFieldOf("offset", 0.0).forGetter(ClimateShift::offset),
                Codec.DOUBLE.optionalFieldOf("narrowing", 0.0).forGetter(ClimateShift::narrowing),
            ).apply(instance, ::ClimateShift)
        }
    }
}

/**
 * Every axis an Age was told to bend, together — the whole of "a hot, dry world" as data.
 *
 * Depth is deliberately absent: it is the one climate parameter an Age answers for itself ([ClimateDepth]),
 * and bending it would let a surface biome claim the rock below or a cave biome reach daylight.
 */
data class ClimateBias(private val byAxis: Map<ClimateAxis, ClimateShift> = emptyMap()) {
    /** [value] on [axis], as this Age reads it — untouched where nothing was said. */
    fun shift(axis: ClimateAxis, value: Float): Float = byAxis[axis]?.applyTo(value) ?: value

    companion object {
        val NONE = ClimateBias()

        val CODEC: Codec<ClimateBias> = Codec.unboundedMap(ClimateAxis.CODEC, ClimateShift.CODEC)
            .xmap(::ClimateBias, ClimateBias::byAxis)
    }
}
