package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.phenomena.AgeWeather
import net.minecraft.util.StringRepresentable

/**
 * Well past ordinary but short of never-stopping, which leaves a rung somewhere to go.
 *
 * Top-level rather than in the companion: an enum constant is built before its own companion exists, so a
 * constant beside them is the only one they can read.
 */
private const val MOSTLY = 0.85

/**
 * A process that befalls an Age — what [Phenomena]'s claims name (design §3.1, §5.2).
 *
 * **Ours, unlike every other population's values.** A creature is an entity type and a feature is a placed
 * feature; a phenomenon has nothing behind it in vanilla at all, so this pool is written rather than
 * derived — which is why it is an enum here beside `Terrain` and `Carvers` rather than a registry lookup.
 *
 * **Processes, not events** (§5.2). What earns a place here is inexorable and legible: a thing you can see
 * coming, adapt to or leave, rather than a cooldown that taxes you. "Lightning strikes at random" is the
 * failure mode; "this Age is a permanent electrical storm" is the shape.
 *
 * **Adding one is a constant, a word and a branch.** The constant here, `art/word/<name>.json` naming it,
 * and a case in [co.voik.agesandtheart.age.phenomena.Happenings.befall] — which is an exhaustive `when`, so
 * the build breaks until the new one is answered for.
 */
enum class Phenomenon(
    override val key: String,
    /**
     * The weather this insists on, however the Age's own dials were left.
     *
     * A **floor**, never a setting: a phenomenon that needs rain raises the rain, and one that needs none
     * leaves it where the writer put it. Keeping it declarative is what stops each phenomenon reaching for
     * the weather itself and the two ending up disagreeing about who owns it.
     */
    val insistsOn: AgeWeather.Conditions = AgeWeather.Conditions.ORDINARY,
) : AspectPreset {
    /**
     * A world in permanent storm, struck far more often than weather alone would.
     *
     * Built on the Age's **own** weather rather than beside it: with `WeatherData` per Age
     * ([co.voik.agesandtheart.age.phenomena.AgeWeather]), `ServerLevel.tickThunder` does the whole job —
     * targeting, lightning rods, the skeleton-horse trap and the bolt — and a tempest is that asked for
     * more often. Nothing here reimplements lightning.
     */
    TEMPEST("tempest", AgeWeather.Conditions(rainfall = MOSTLY, thunder = MOSTLY)),
    ;

    override val aspect = Aspect.PHENOMENA

    override fun getSerializedName(): String = key

    companion object {
        /** The phenomenon called [key], or null where nothing is. */
        fun named(key: String): Phenomenon? = entries.firstOrNull { it.key == key }
    }
}
