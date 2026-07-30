package co.voik.agesandtheart.age.aspect

import com.mojang.serialization.Codec
import net.minecraft.util.StringRepresentable
import kotlin.math.abs
import kotlin.math.ln

/**
 * How much of an Age one preset covers, relative to the others in its aspect. Named steps rather than a
 * number (design §3.2), spelled as a writer would say them, since §4.5's quantifiers attach here.
 *
 * The ladder is geometric, ×4 a rung, which is what makes the extremes worth having: [DOMINANT] against
 * [RARE] divides a world 98.5% to 1.5%, and 1.5% of an infinite world is somewhere you go looking for.
 */
enum class Share(val key: String, val weight: Double) : StringRepresentable {
    /** Most of the world. What a preset gets when nothing competes with it. */
    DOMINANT("dominant", 64.0),

    /** A large minority — you will cross one without trying. */
    COMMON("common", 16.0),

    /** Islands, met every few territories. */
    SCATTERED("scattered", 4.0),

    /** Somewhere out there. A fifth of a percent to a percent and a half of the world, and worth finding. */
    RARE("rare", 1.0),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Share> = StringRepresentable.fromEnum(Share::values)

        /**
         * The rung nearest [ratio] of the strongest claim in the aspect — snapped in *log* space, because the
         * ladder is geometric and a linear nearest would collapse everything below a half onto [RARE].
         */
        fun nearest(ratio: Double): Share {
            if (ratio >= 1.0) return DOMINANT
            val wanted = ln(ratio.coerceAtLeast(FAINTEST_RATIO) * DOMINANT.weight)
            return entries.minBy { rung -> abs(ln(rung.weight) - wanted) }
        }

        /**
         * These shares with the smallest raised until it covers at least [LEAST_SHARE_OF_A_WORLD]. A word
         * a writer wrote must be findable, and the ladder alone can go below that — two dominant
         * territories and one rare leaves the rare one under a percent, which is a spatial silent drop.
         */
        fun findable(shares: List<Share>): List<Share> {
            if (shares.size <= 1) return shares
            val total = shares.sumOf { it.weight }
            val smallest = shares.minBy { it.weight }
            if (smallest.weight / total >= LEAST_SHARE_OF_A_WORLD || smallest == DOMINANT) return shares
            val raised = entries[entries.indexOf(smallest) - 1]
            return findable(shares.map { if (it == smallest) raised else it })
        }

        /** Below a hundredth of the world a territory stops being scarce and starts being absent. */
        const val LEAST_SHARE_OF_A_WORLD = 0.01

        // Anything fainter than this is RARE anyway, and it keeps the logarithm finite.
        private const val FAINTEST_RATIO = 1.0e-6
    }
}
