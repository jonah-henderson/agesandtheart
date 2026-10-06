package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.CeilingCovers
import co.voik.agesandtheart.age.aspect.Phenomenon
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.saveddata.WeatherData

/**
 * How many phenomena may be running at once (`AgeConfig.phenomenaAtOnce`), in each Age or across the server.
 *
 * Counted are the events — a sandfall, a cave-in, a meteor storm, each running while an entity it raised
 * stands — and the spells of weather that a blizzard, a deluge or a tempest does its work in. A spell that
 * may not begin is held off by keeping its timer from running out, which is how vanilla's own cycle waits.
 * An inferno, an aurora and a rainbow are not counted.
 */
object PhenomenaCeiling {

    private val ledger = CeilingLedger<Identifier>()

    /** The entities each running event has raised; the event is over when the last is gone. */
    private val standing = HashMap<Episode<Identifier>, MutableList<Entity>>()

    private val EVENTS = setOf(Phenomenon.SANDFALL, Phenomenon.TECTONICS, Phenomenon.METEORS)
    private val RIDE_THE_RAIN = setOf(Phenomenon.BLIZZARD, Phenomenon.DELUGE)
    private val RIDE_THE_THUNDER = setOf(Phenomenon.TEMPEST)

    /** Every scope when the ceiling covers the whole server. */
    private val THE_WHOLE_SERVER = Any()

    /** How long a granted episode has to begin before it loses its turn: an event may find no ground at once. */
    private const val GRACE_TICKS = 200L

    /** What a held spell's timer is kept at, re-set every tick it waits. */
    private const val HELD_TICKS = 20

    /** The timer that makes vanilla's cycle turn the weather over on its next tick. */
    private const val TURNS_NEXT_TICK = 1

    private fun ceiling(): Int = AgeConfig.phenomenaAtOnce.get()

    private fun scopeOf(age: Identifier): Any = when (AgeConfig.phenomenaCeilingCovers.get()) {
        CeilingCovers.EACH_AGE -> age
        CeilingCovers.THE_SERVER -> THE_WHOLE_SERVER
    }

    private fun ageOf(level: ServerLevel): Identifier = level.dimension().identifier()

    private fun eventIn(level: ServerLevel, phenomenon: Phenomenon) = Episode(ageOf(level), setOf(phenomenon))

    /** Once a tick, before any Age's phenomena: lets go of what has ended or been left, and serves the line. */
    fun tick(server: MinecraftServer) {
        forgetAgesNobodyIsIn(server)
        letFinishedEventsGo()
        ledger.serve(server.tickCount.toLong(), ceiling(), ::scopeOf, GRACE_TICKS)
    }

    fun serverStopped() {
        for (age in agesInTheLedger()) ledger.forget(age)
        standing.clear()
    }

    /** Whether the line has reached [phenomenon] in [level], so it begins without waiting for its roll. */
    fun isOwed(level: ServerLevel, phenomenon: Phenomenon): Boolean = ledger.isGranted(eventIn(level, phenomenon))

    /** Whether an event may begin now; where it may not, it waits its turn. Ask after the roll succeeds. */
    fun mayBegin(level: ServerLevel, phenomenon: Phenomenon): Boolean =
        ledger.admits(eventIn(level, phenomenon), ceiling(), ::scopeOf)

    /** [raised] stands for [phenomenon] in [level] until it is gone, whoever raised it. */
    fun began(level: ServerLevel, phenomenon: Phenomenon, raised: Entity) {
        val event = eventIn(level, phenomenon)
        ledger.begin(event)
        standing.getOrPut(event) { mutableListOf() } += raised
    }

    /**
     * Admits or holds off [level]'s rain and thunder for what [befalls] it. Run after the weather is steered,
     * so a held timer is the last word on it this tick. A walk's forced weather is left alone.
     */
    fun holdTheWeather(level: ServerLevel, befalls: Set<Phenomenon>) {
        if (AgeWeather.beingHumoured(level)) return
        val weather = AgeWeather.of(level) ?: return
        val age = ageOf(level)
        val riding = befalls intersect RIDE_THE_RAIN
        if (riding.isNotEmpty()) gateTheRain(weather, Episode(age, riding))
        val storming = befalls intersect RIDE_THE_THUNDER
        if (storming.isNotEmpty()) gateTheThunder(weather, Episode(age, storming))
    }

    private fun gateTheRain(weather: WeatherData, spell: Episode<Identifier>) {
        if (weather.isRaining) {
            if (mayGoOn(spell)) return
            weather.isRaining = false
            weather.rainTime = HELD_TICKS
            weather.setDirty()
            return
        }
        ledger.end(spell)
        val timer = timerWhileClear(spell, weather.rainTime)
        if (timer == weather.rainTime) return
        weather.rainTime = timer
        weather.setDirty()
    }

    /** Thunder only strikes in rain, so a spell of it runs only while both are on. */
    private fun gateTheThunder(weather: WeatherData, spell: Episode<Identifier>) {
        if (weather.isThundering && weather.isRaining) {
            if (mayGoOn(spell)) return
            weather.setThundering(false)
            weather.thunderTime = HELD_TICKS
            weather.setDirty()
            return
        }
        ledger.end(spell)
        val timer = timerWhileClear(spell, weather.thunderTime)
        if (timer == weather.thunderTime) return
        weather.thunderTime = timer
        weather.setDirty()
    }

    private fun mayGoOn(spell: Episode<Identifier>): Boolean {
        if (ledger.isRunning(spell)) return true
        if (!ledger.admits(spell, ceiling(), ::scopeOf)) return false
        ledger.begin(spell)
        return true
    }

    private fun timerWhileClear(spell: Episode<Identifier>, timer: Int): Int = when {
        ledger.isGranted(spell) -> TURNS_NEXT_TICK
        ledger.isWaiting(spell) -> timer.coerceAtLeast(HELD_TICKS)
        else -> timer
    }

    private fun forgetAgesNobodyIsIn(server: MinecraftServer) {
        fun nobodyIsIn(age: Identifier): Boolean {
            val level = server.getLevel(ResourceKey.create(Registries.DIMENSION, age)) ?: return true
            return Sampling.watchers(level).isEmpty()
        }
        for (age in agesInTheLedger().filter(::nobodyIsIn)) {
            ledger.forget(age)
            standing.keys.removeAll { it.age == age }
        }
    }

    private fun letFinishedEventsGo() {
        for (raised in standing.values) raised.removeAll { it.isRemoved }
        standing.values.removeAll { it.isEmpty() }
        fun isAnEventWithNothingStanding(episode: Episode<Identifier>) =
            episode.kinds.all { it in EVENTS } && episode !in standing
        for (episode in ledger.runningNow().filter(::isAnEventWithNothingStanding)) ledger.end(episode)
    }

    private fun agesInTheLedger(): Set<Identifier> =
        (ledger.runningNow() + ledger.waitingNow() + ledger.grantedNow()).mapTo(HashSet()) { it.age }

    /** What the instrument says: the ceiling, and each episode with what it is doing. */
    fun describe(): List<String> = buildList {
        add("ceiling ${ceiling()}, covering ${AgeConfig.phenomenaCeilingCovers.get().name.lowercase()}")
        fun said(episode: Episode<Identifier>) = "${episode.kinds.joinToString("+") { it.key }} in ${episode.age}"
        for (episode in ledger.runningNow()) add("running: ${said(episode)}")
        for (episode in ledger.grantedNow()) add("granted: ${said(episode)}")
        for (episode in ledger.waitingNow()) add("waiting: ${said(episode)}")
    }
}
