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
     * A world in permanent storm, struck far more often than weather alone would, and cratered where it
     * is struck.
     *
     * Built on the Age's **own** weather rather than beside it: with `WeatherData` per Age
     * ([co.voik.agesandtheart.age.phenomena.AgeWeather]), `ServerLevel.tickThunder` does the whole job —
     * targeting, lightning rods, the skeleton-horse trap and the bolt — and a tempest is that asked for
     * more often. Nothing here reimplements lightning; a bolt that lands in one is given a creeper's blast
     * and fire around it ([co.voik.agesandtheart.age.phenomena.Tempest.struck]).
     *
     * **A lightning rod grounds it**, which is the answer §5.2 asks a process to have: the storm is
     * inexorable and it can be lived with, by a writer who brings copper to the Age they wrote.
     */
    TEMPEST("tempest", AgeWeather.Conditions(rainfall = MOSTLY, thunder = MOSTLY)),

    /**
     * A world that burns: what can see the sky catches light, and what stands in the open burns by day.
     *
     * **Insists on no weather at all, deliberately.** It wants a *dry* Age and [insistsOn] is a floor that
     * can only raise, so asking here would be asking for the opposite of what it needs. The dryness is said
     * where it belongs instead — `art/word/inferno.json` bounds rainfall from above, which yields to a
     * writer who meant otherwise ([Setting.Bound]) where a floor here could not.
     *
     * And the rain that does fall is the lull rather than a leak: `FireBlock` puts itself out in it, so an
     * inferno Age gets its build-and-repair rhythm from vanilla with nothing written for it (§5.2.2).
     */
    INFERNO("inferno"),

    /**
     * A curtain of light standing over the cold — the first phenomenon that is *seen* rather than done.
     *
     * **It befalls nothing, and that is not an oversight.** §5.2's test is that a process be inexorable and
     * legible, with a visible direction and an answer of adapt or leave. An aurora passes it without
     * touching anybody: it comes on its own nights, it stands where the snow lies, and what a player does
     * about it is walk north to see it. Everything it does happens on the client, from arithmetic every
     * client can do for itself — so [co.voik.agesandtheart.age.phenomena.Happenings.befall] has nothing to
     * run and says so.
     *
     * **The first phenomenon that is sited.** [Phenomena]'s own note says the aspect "does not divide, it is
     * sited" and that there was no mechanism for it; this is that mechanism, and it is a rule the curtain
     * carries about the ground under the viewer rather than a territory the resolver cuts the world into.
     * `in <biome>` stays the wrong scope for a phenomenon, exactly as [Aspect.confinable] says.
     *
     * **Insists on no weather**, like an inferno and for the same reason: [insistsOn] is a floor that can
     * only raise, and a curtain wants a *clear* sky. The lean toward cold is said in `art/word/auroral.json`
     * as a nudge, which composes with whatever else was written and can never fracture against it.
     */
    AURORA("aurora"),
    ;

    override val aspect = Aspect.PHENOMENA

    override fun getSerializedName(): String = key

    companion object {
        /** The phenomenon called [key], or null where nothing is. */
        fun named(key: String): Phenomenon? = entries.firstOrNull { it.key == key }
    }
}
