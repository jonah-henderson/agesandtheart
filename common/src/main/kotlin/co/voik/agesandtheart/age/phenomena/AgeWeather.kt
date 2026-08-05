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
