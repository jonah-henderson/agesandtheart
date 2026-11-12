package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.SizeScale
import co.voik.ephemeris.sky.CloudPattern
import kotlin.math.log10
import kotlin.math.pow

/**
 * The shape of an Age's vanilla clouds, drawn by Ephemeris from a picture made for the Age ([CloudPattern]): how much of
 * the sky is cloud (`few`, `unbroken`), whether it lies in bits or patches (`scattered`, `patchy`), whether it is torn
 * into rags, how large it is (`colossal`, `small`), and whether it is an overcast sheet holed through (`porous`).
 */
object CloudShape {

    /** How much of the sky is cloud: a tenth of vanilla's at the bottom, vanilla's in the middle, all but gaps at the top. */
    val COVER = Parameter.ranged(
        "cover",
        help = "How much of the sky is cloud.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "a tenth of vanilla's"),
            Parameter.Landmark(0.0, "vanilla's", isVanilla = true),
            Parameter.Landmark(1.0, "the whole sky but for a few gaps"),
        ),
    )

    /** Whether the cloud lies in small scattered bits or broad patches with sky between. */
    val CLUMPING = Parameter.ranged(
        "clumping",
        help = "Whether the clouds lie in small bits or broad patches.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "small scattered bits"),
            Parameter.Landmark(0.0, "vanilla's clumps", isVanilla = true),
            Parameter.Landmark(1.0, "broad patches"),
        ),
    )

    /** Whether the clouds are torn into rags streaming along their drift. Shares `torn` with the sky and the land. */
    val TORN = Parameter.flag("torn", help = "Whether the clouds are torn into rags streaming along their drift.")

    /** How large a cloud's cells are, on [SizeScale]'s steps like every other size. */
    val SIZE = Parameter.ranged(
        "size",
        help = "How large the clouds are.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "a quarter"),
            Parameter.Landmark(-0.5, "half"),
            Parameter.Landmark(0.0, "vanilla's", isVanilla = true),
            Parameter.Landmark(0.5, "twice"),
            Parameter.Landmark(1.0, "four times"),
        ),
    )

    /**
     * How holed the clouds are — `porous clouds`: an overcast sheet with round holes through it, a share of it at the
     * most. Only an aimed word turns it; where nothing says how much of the sky is cloud, the sheet is whole but for them.
     */
    val POROSITY = Parameter.ranged(
        "porosity",
        help = "How much of an overcast sheet of cloud is cut away in round holes.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "whole"),
            Parameter.Landmark(0.0, "whole"),
            Parameter.Landmark(1.0, "a quarter holes"),
        ),
    ).onlyWhenAimed()

    /** The share of the sky in cloud at [cover] on its axis: a tenth of vanilla's to vanilla's evenly in its logarithm. */
    fun shareAt(cover: Double): Float {
        val held = cover.coerceIn(-1.0, 1.0)
        val share = if (held <= 0.0) VANILLAS * 10.0.pow(held) else VANILLAS + held * (MOST - VANILLAS)
        return share.toFloat()
    }

    /** Where [multiple] times vanilla's cover stands on [COVER] — what `few clouds` and `teeming clouds` ask for. */
    fun coverAt(multiple: Double): Double {
        val share = (VANILLAS * multiple).coerceIn(VANILLAS / 10.0, MOST)
        return if (share <= VANILLAS) log10(share / VANILLAS) else (share - VANILLAS) / (MOST - VANILLAS)
    }

    /** The shape [clouds] say their clouds take, or null where they say nothing of it and keep vanilla's. */
    fun patternIn(clouds: Options, seed: Long): CloudPattern? {
        val holes = clouds.steer(POROSITY, seed)?.let { (it.coerceIn(0.0, 1.0) * MOST_HOLES).toFloat() } ?: 0.0f
        val sheet = if (holes > 0.0f) WHOLE_SHEET else null
        val cover = clouds.steer(COVER, seed)?.let(::shareAt) ?: sheet
        val clumping = clouds.steer(CLUMPING, seed)?.toFloat() ?: CloudPattern().clumping
        val torn = clouds.isTrue(TORN)
        val scale = clouds.steer(SIZE, seed)?.let { SizeScale.factorAt(it).toFloat() } ?: CloudPattern().cellScale
        val pattern = CloudPattern(cover, clumping, torn, scale, seed xor PATTERN_SALT, holes)
        val saysNothing = !pattern.drawsItsOwnPicture && pattern.cellScale == CloudPattern().cellScale
        return pattern.takeUnless { saysNothing }
    }

    private const val VANILLAS = CloudPattern.VANILLA_COVER.toDouble()
    private const val MOST = 0.97

    /** The share of a sheet a porosity of one cuts away in holes, and the sheet they are cut through where no cover is said. */
    private const val MOST_HOLES = 0.25
    private const val WHOLE_SHEET = 1.0f
    private const val PATTERN_SALT = 0x3C1D_7E55_2A90_B4F1L
}
