package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.ORDINARY_SHARE
import co.voik.agesandtheart.age.aspect.ORDINARY_SPELL
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.WeatherConditions
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.WeatherData
import java.util.WeakHashMap

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
     * What a walk can ask an Age's sky to do, by name.
     *
     * The three vanilla names, **and every phenomenon that has an opinion about weather** — which is a
     * question the phenomena already answer, since [Phenomenon.insistsOn] is exactly "what this one needs
     * the sky to be doing". So `tempest` appears here for free and `inferno` does not, because one is
     * weather-like and the other only lives in it, and nothing had to say which is which.
     */
    fun asked(): Map<String, WeatherConditions> = buildMap {
        put("clear", WeatherConditions(rainfall = NONE, thunder = NONE))
        put("rain", WeatherConditions(rainfall = FULLY, thunder = NONE))
        put("thunder", WeatherConditions(rainfall = FULLY, thunder = FULLY))
        for (phenomenon in Phenomenon.entries) {
            if (!phenomenon.insistsOn.saysNothing) put(phenomenon.key, phenomenon.insistsOn)
        }
    }

    /**
     * Puts [level]'s sky into [wants] for a good while, **and holds the Age off its own parameters meanwhile.**
     *
     * The hold is the whole reason this is not two lines. [steer] runs every tick and [capped] cuts a spell
     * the Age does not want straight to zero, so asking a dry Age for rain would have been undone before
     * the next frame — the command would have reported success and changed nothing visible, which is worse
     * than refusing. An inferno is precisely such an Age, and its rain is precisely what wanted walking.
     *
     * Ephemeral and unpersisted on purpose: it is a walk's business, not an Age's, and losing it with the
     * level costs nothing but the Age reasserting itself sooner.
     */
    fun set(level: ServerLevel, data: WeatherData, wants: WeatherConditions) {
        val raining = wants.rainfall > ORDINARY_SHARE
        val thundering = wants.thunder > ORDINARY_SHARE
        data.clearWeatherTime = if (raining) 0 else A_GOOD_WHILE
        data.isRaining = raining
        data.rainTime = if (raining) A_GOOD_WHILE else 0
        data.setThundering(thundering)
        data.thunderTime = if (thundering) A_GOOD_WHILE else 0
        data.setDirty()
        heldUntil[level] = level.gameTime + A_GOOD_WHILE
    }

    /**
     * Ages a walk has asked for weather, and the tick of that level's own clock each stops being humoured.
     * Weakly keyed on the level, so a hold goes with the level it was set in.
     */
    private val heldUntil = WeakHashMap<ServerLevel, Long>()

    private fun beingHumoured(level: ServerLevel): Boolean {
        val until = heldUntil[level] ?: return false
        if (level.gameTime < until) return true
        heldUntil.remove(level)
        return false
    }

    /** Long enough to walk in, in ticks — vanilla's own `/weather` default of five minutes. */
    private const val A_GOOD_WHILE = 6000

    /** The ends of a share, for the three states that are not a phenomenon's. */
    private const val NONE = 0.0
    private const val FULLY = 1.0

    fun steer(level: ServerLevel, wants: WeatherConditions) {
        if (wants.saysNothing || beingHumoured(level)) return
        val weather = level.dataStorage.computeIfAbsent(WeatherData.TYPE)
        lengthenANewSpell(level, weather, wants.spellLength)
        val rainTime = capped(wants.rainfall, weather.isRaining, weather.rainTime, ORDINARY_RAIN)
        val thunderTime = capped(wants.thunder, weather.isThundering, weather.thunderTime, ORDINARY_THUNDER)
        if (rainTime == weather.rainTime && thunderTime == weather.thunderTime) return
        weather.rainTime = rainTime
        weather.thunderTime = thunderTime
        // Clear weather is a third timer that suppresses both, and a wet Age must not sit under one.
        if (wants.rainfall > ORDINARY_SHARE) weather.clearWeatherTime = 0
        weather.setDirty()
    }

    /**
     * **The one place a timer is raised, and only once a spell.** [steer] must never raise a timer tick by
     * tick, or it and `advanceWeatherCycle` push one value back and forth forever. Raising it once, the
     * first tick a spell is seen, is a spell that was drawn longer — the cycle then counts it down as it
     * would any other.
     */
    private fun lengthenANewSpell(level: ServerLevel, weather: WeatherData, spellLength: Double) {
        if (!weather.isRaining) {
            lengthened.remove(level)
            return
        }
        if (spellLength <= ORDINARY_SPELL || lengthened.containsKey(level)) return
        lengthened[level] = true
        val atLeast = (ORDINARY_RAIN * spellLength).toInt()
        if (weather.rainTime < atLeast) {
            weather.rainTime = atLeast
            weather.setDirty()
        }
    }

    /** The Ages whose current spell of rain has already been lengthened — see [lengthenANewSpell]. */
    private val lengthened = WeakHashMap<ServerLevel, Boolean>()

    /** [timeLeft], never raised — see [steer] for why only one direction is safe. */
    private fun capped(wants: Double, happening: Boolean, timeLeft: Int, ordinary: Int): Int {
        val wantsMore = wants > ORDINARY_SHARE
        if (wantsMore == happening) return timeLeft
        val distance = if (wantsMore) wants - ORDINARY_SHARE else ORDINARY_SHARE - wants
        val cap = (ordinary * (1.0 - distance / ORDINARY_SHARE)).toInt().coerceAtLeast(0)
        return timeLeft.coerceAtMost(cap)
    }

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
