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

/** Rain often enough that it is a fact about the Age, and broken enough that the light gets through. */
private const val SHOWERY = 0.55

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
     * only raise, and a curtain wants a *clear* sky. Nor does `art/word/auroral.json` lean the climate
     * cold, though it drafted with one: a narrowing word is priced in its lowest-ordinal aspect and
     * phenomena are last, so any second parameter would have repriced `auroral` as a word about that other
     * thing. A writer wanting the cold has `frozen`, and the variation is drawn from the Age's seed.
     */
    AURORA("aurora"),

    /**
     * A bow standing opposite the Age's light — the second phenomenon that is *seen* rather than done, and
     * the first that needs the weather to be doing something.
     *
     * **It befalls nothing**, as an aurora does not: it comes on its own days, it stands where the light
     * puts it, and what a player does about it is look. Everything it does happens on the client.
     *
     * **The one phenomenon here that insists on rain, and the one for which a floor is the right shape.**
     * An inferno wants a dry Age and a curtain a clear one, so neither could ask [insistsOn] for anything;
     * a bow is sunlight bent through falling water and wants exactly what a floor can give. It cannot be
     * said in `art/word/rainbows.json` either — a rainfall bound there is a second aspect, and the pricing
     * rule above would take the word away from phenomena altogether.
     *
     * **Showery rather than streaming.** A bow needs the rain to *stop*, or at least to thin: the light has
     * to reach the drops. An Age held at a downpour would have the wettest sky and no bows in it.
     */
    RAINBOW("rainbow", AgeWeather.Conditions(rainfall = SHOWERY)),

    /**
     * Columns of sand that walk the Age, burying what they cross — the Outer Wilds nod §5.2 has named since
     * it was written, and the first phenomenon that is an **entity**
     * ([co.voik.agesandtheart.age.phenomena.SandColumn]).
     *
     * **The strongest telegraph in the set.** §5.2 asks a process to be inexorable and legible with a
     * visible direction; a column standing from the ground to the sky and moving in a straight line is the
     * only hazard here you can see from another biome. Its counterplay is spatial, so its warning is too,
     * which is the granularity rule the set is judged on.
     *
     * **It denies the surface and it denies light**, and those want different answers: a roof stops the
     * burial, and a light a falling block cannot break stops a buried base going dark and spawning things
     * in itself. **It is also the one hazard that is a supply** — burial hands you unlimited sand and so
     * unlimited glass, which is the reason to be in an Age that has one.
     *
     * **Insists on no weather**, like an inferno and for the same reason: it wants a dry Age, and
     * [insistsOn] is a floor that can only raise. Nor does `art/word/sandfall.json` lean the climate arid,
     * which is the trap `auroral` paid for — a narrowing word is priced in its lowest-ordinal aspect and
     * phenomena are last, so a second parameter would reprice the word as one about climate.
     */
    SANDFALL("sandfall"),
    ;

    override val aspect = Aspect.PHENOMENA

    override fun getSerializedName(): String = key

    companion object {
        /** The phenomenon called [key], or null where nothing is. */
        fun named(key: String): Phenomenon? = entries.firstOrNull { it.key == key }
    }
}
