package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.Constants
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.WeatherData

/**
 * An Age's own weather, kept where the overworld keeps its own.
 *
 * `MinecraftServer` builds the shared one with `getDataStorage().computeIfAbsent(WeatherData.TYPE)`; this is
 * that call character for character, against the **Age's** storage, so an Age's weather lives in the Age's
 * world folder and is loaded and saved by the same machinery as everything else there.
 *
 * Reached from [co.voik.agesandtheart.mixin.ServerLevelMixin], which is where the argument for all of this
 * is written down.
 */
object AgeWeather {

    /**
     * How much of the time an Age rains, and how much of that is thunder — each a fraction of its axis,
     * where [ORDINARY_SHARE] is "leave it as vanilla would have it".
     *
     * A pair rather than two arguments so a phenomenon can insist on conditions without knowing how they
     * are applied, and so the two can be [atLeast] one another.
     */
    data class Conditions(val rainfall: Double = ORDINARY_SHARE, val thunder: Double = ORDINARY_SHARE) {
        /** The wetter and stormier of the two — how a phenomenon raises a floor without lowering one. */
        fun atLeast(other: Conditions): Conditions =
            Conditions(maxOf(rainfall, other.rainfall), maxOf(thunder, other.thunder))

        val saysNothing: Boolean get() = rainfall == ORDINARY_SHARE && thunder == ORDINARY_SHARE

        companion object {
            val ORDINARY = Conditions()
        }
    }

    /**
     * Steers [level]'s weather toward [wants], one tick's worth.
     *
     * **Only ever shortens a timer, never extends one**, which is what keeps this from fighting
     * `advanceWeatherCycle` — the cycle decrements and we clamp, so the two converge instead of pushing a
     * value back and forth forever. The whole axis is still covered, because wanting *more* weather cuts
     * the gap between spells and wanting *less* cuts the spells:
     *
     * - wetter than ordinary → the wait for rain is capped, so rain returns sooner
     * - drier than ordinary → the rain itself is capped, so it passes sooner
     *
     * At the top of the axis the cap is nearly zero and rain restarts as soon as it stops, which is the
     * drowned Age; at the bottom every spell is cut short, which is the parched one.
     */
    /**
     * What a walk can ask an Age's sky to do — the three states vanilla's own `/weather` names.
     *
     * Here rather than in the command because the mapping onto `WeatherData`'s five fields is weather's
     * business, not Brigadier's: "raining" is two flags and three timers, and a caller that had to know
     * that would be a caller that could get it wrong.
     */
    enum class Asked(val key: String) {
        CLEAR("clear"),
        RAIN("rain"),
        THUNDER("thunder"),
    }

    /** Puts [data] into [asked], for as long as vanilla would have. */
    fun set(data: WeatherData, asked: Asked) {
        val spell = A_GOOD_WHILE
        data.setClearWeatherTime(if (asked == Asked.CLEAR) spell else 0)
        data.isRaining = asked != Asked.CLEAR
        data.setRainTime(if (asked == Asked.CLEAR) 0 else spell)
        data.setThundering(asked == Asked.THUNDER)
        data.setThunderTime(if (asked == Asked.THUNDER) spell else 0)
    }

    /** Long enough to walk in, in ticks — vanilla's own `/weather` default of five minutes. */
    private const val A_GOOD_WHILE = 6000

    fun steer(level: ServerLevel, wants: Conditions) {
        if (wants.saysNothing) return
        val weather = level.dataStorage.computeIfAbsent(WeatherData.TYPE)
        val rainTime = capped(wants.rainfall, weather.isRaining, weather.rainTime, ORDINARY_RAIN)
        val thunderTime = capped(wants.thunder, weather.isThundering, weather.thunderTime, ORDINARY_THUNDER)
        if (rainTime == weather.rainTime && thunderTime == weather.thunderTime) return
        weather.rainTime = rainTime
        weather.thunderTime = thunderTime
        // Clear weather is a third timer that suppresses both, and a wet Age must not sit under one.
        if (wants.rainfall > ORDINARY_SHARE) weather.clearWeatherTime = 0
        weather.setDirty()
    }

    /** [timeLeft], never raised — see [steer] for why only one direction is safe. */
    private fun capped(wants: Double, happening: Boolean, timeLeft: Int, ordinary: Int): Int {
        val wantsMore = wants > ORDINARY_SHARE
        if (wantsMore == happening) return timeLeft
        val distance = if (wantsMore) wants - ORDINARY_SHARE else ORDINARY_SHARE - wants
        val cap = (ordinary * (1.0 - distance / ORDINARY_SHARE)).toInt().coerceAtLeast(0)
        return timeLeft.coerceAtMost(cap)
    }

    /** The middle of a ranged axis, which is where a writer who said nothing leaves it. */
    const val ORDINARY_SHARE = 0.5

    /**
     * Vanilla's own spells, in ticks, as the scale everything is a share of — half a day of rain and about
     * an eighth of one of thunder. Read as the anchor rather than as a limit: a dial only ever cuts.
     */
    private const val ORDINARY_RAIN = 12000
    private const val ORDINARY_THUNDER = 3600

    /**
     * The weather belonging to [level], or null where the level is not an Age and vanilla's own should
     * answer.
     *
     * **Told by the namespace**, not by asking `AgeSavedData`. Every dimension we make is
     * `agesandtheart:<something>` and nothing else registers one, so this is exact — and it stays a string
     * comparison on a method vanilla calls for every level on every tick, rather than a saved-data lookup.
     */
    @JvmStatic
    fun of(level: ServerLevel): WeatherData? {
        if (level.dimension().identifier().namespace != Constants.MOD_ID) return null
        return level.dataStorage.computeIfAbsent(WeatherData.TYPE)
    }
}
