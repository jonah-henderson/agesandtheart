package co.voik.agesandtheart.age.phenomena

import net.minecraft.server.level.ServerLevel

/**
 * An Age in permanent storm, struck far more often than weather alone would strike it.
 *
 * **Nothing here makes lightning.** `ServerLevel.tickThunder` is public and already does the whole of it —
 * a position in the chunk, vanilla's own targeting, the lightning-rod search, the skeleton-horse trap and
 * the bolt — so a tempest is that *asked for more often*, and every behaviour hanging off a strike comes
 * along without being reimplemented or kept in step.
 *
 * That only works because an Age owns its weather ([AgeWeather]): `tickThunder` gates on `isRaining()` and
 * `isThundering()`, which before 26.1's schedule became per-Age would have meant storming the overworld to
 * storm an Age.
 */
object Tempest {

    fun strike(level: ServerLevel, density: Double) {
        keepStorming(level)
        val watching = level.players()
        if (watching.isEmpty()) return

        val random = level.random
        repeat(Happenings.timesFor(density, ORDINARY_VISITS)) {
            val near = watching[random.nextInt(watching.size)].chunkPosition()
            val chunk = level.chunkSource.getChunkNow(
                near.x + random.nextInt(NEARBY * 2 + 1) - NEARBY,
                near.z + random.nextInt(NEARBY * 2 + 1) - NEARBY,
            ) ?: return@repeat
            level.tickThunder(chunk)
        }
    }

    /**
     * Keeps the Age's own weather at a storm, topping the timers up before vanilla's cycle can end it.
     *
     * Setting the flags rather than the levels: `advanceWeatherCycle` reads these and does the ramping and
     * the telling of that dimension's players itself, which is the whole reason [AgeWeather] is a mixin on
     * the schedule rather than a fight over the state.
     */
    private fun keepStorming(level: ServerLevel) {
        val weather = level.weatherData
        if (weather.isThundering && weather.thunderTime > TOP_UP_BELOW) return
        weather.setRaining(true)
        weather.setThundering(true)
        weather.setRainTime(KEEP_STORMING)
        weather.setThunderTime(KEEP_STORMING)
        weather.setClearWeatherTime(0)
        weather.setDirty()
    }

    /**
     * How many chunks a tempest looks at per tick, at an ordinary rung.
     *
     * Each visit is one of vanilla's own rolls, which is `1 in 100000` — so this many, twenty times a
     * second, is a strike somewhere near a player every ten seconds or so, and `teeming` is four times
     * that. The knob is the *number of rolls* rather than a rate of our own, so a tempest can never strike
     * anywhere vanilla would not have.
     */
    private const val ORDINARY_VISITS = 512

    /** How far from a player a strike may land, in chunks — inside a normal render distance. */
    private const val NEARBY = 8

    /** Long enough that the cycle never gets to clear it, short enough to be a number and not forever. */
    private const val KEEP_STORMING = 24000
    private const val TOP_UP_BELOW = 1200
}
