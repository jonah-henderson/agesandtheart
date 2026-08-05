package co.voik.agesandtheart.age.aspect

import net.minecraft.util.StringRepresentable

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
enum class Phenomenon(override val key: String) : AspectPreset {
    /**
     * A world in permanent storm, struck far more often than weather alone would.
     *
     * Built on the Age's **own** weather rather than beside it: with `WeatherData` per Age
     * ([co.voik.agesandtheart.age.phenomena.AgeWeather]), `ServerLevel.tickThunder` does the whole job —
     * targeting, lightning rods, the skeleton-horse trap and the bolt — and a tempest is that asked for
     * more often. Nothing here reimplements lightning.
     */
    TEMPEST("tempest"),
    ;

    override val aspect = Aspect.PHENOMENA

    override fun getSerializedName(): String = key

    companion object {
        /** The phenomenon called [key], or null where nothing is. */
        fun named(key: String): Phenomenon? = entries.firstOrNull { it.key == key }
    }
}
