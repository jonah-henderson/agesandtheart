package co.voik.agesandtheart.desk

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.ExtraCodecs
import net.minecraft.util.StringRepresentable

/** The tiers a full grammar guide trades pages between. A page counts as one of its own tier or of any below it. */
enum class StudyTier(private val key: String) : StringRepresentable {
    COMMON("common"),
    UNCOMMON("uncommon"),
    RARE("rare"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<StudyTier> = StringRepresentable.fromEnum(StudyTier::values)
    }
}

/**
 * What studying one new word of a tier takes at a full guide: [price] pages of [paidIn] or above, and [seconds] of
 * study. The word is drawn from [buckets]; a reader who knows every word there is given experience instead.
 */
data class GrammarTrade(
    val buckets: Set<String>,
    val price: Int,
    val paidIn: StudyTier,
    val seconds: Int,
    val experience: Experience,
) {
    val ticks: Int get() = seconds * TICKS_PER_SECOND

    /** How many more pages [held] (each page's tier) needs to pay for this, or none. */
    fun shortfall(held: List<StudyTier>): Int = (price - held.count(::pays)).coerceAtLeast(0)

    /**
     * Which of [held] pay for this, by index: the lowest tiers that may pay, so a rare page is spent only where
     * nothing cheaper is left. Null where they are too few.
     */
    fun paidWith(held: List<StudyTier>): Set<Int>? {
        if (shortfall(held) > 0) return null
        return held.indices.filter { pays(held[it]) }.sortedBy { held[it].ordinal }.take(price).toSet()
    }

    private fun pays(tier: StudyTier): Boolean = tier.ordinal >= paidIn.ordinal

    /** Experience points, drawn evenly between [least] and [most], as an ore gives. */
    data class Experience(val least: Int, val most: Int) {
        companion object {
            val CODEC: Codec<Experience> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.INT.fieldOf("least").forGetter(Experience::least),
                    Codec.INT.fieldOf("most").forGetter(Experience::most),
                ).apply(instance, ::Experience)
            }
        }
    }

    companion object {
        private const val TICKS_PER_SECOND = 20

        val CODEC: Codec<GrammarTrade> = RecordCodecBuilder.create { instance ->
            instance.group(
                ExtraCodecs.compactListCodec(Codec.STRING).fieldOf("buckets").forGetter { it.buckets.toList() },
                Codec.INT.fieldOf("price").forGetter(GrammarTrade::price),
                StudyTier.CODEC.fieldOf("paid_in").forGetter(GrammarTrade::paidIn),
                Codec.INT.fieldOf("seconds").forGetter(GrammarTrade::seconds),
                Experience.CODEC.fieldOf("experience").forGetter(GrammarTrade::experience),
            ).apply(instance) { buckets, price, paidIn, seconds, experience ->
                GrammarTrade(buckets.toSet(), price, paidIn, seconds, experience)
            }
        }
    }
}
