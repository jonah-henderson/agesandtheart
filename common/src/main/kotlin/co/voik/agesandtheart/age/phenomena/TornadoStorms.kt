package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.generation.Ages
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.entity.EntityTypeTest
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * An Age whose thunderstorms bring tornadoes down (`notes/the-art-design.md` §5.2.2, "Tornado").
 *
 * **The phenomenon is the tornado ([Tornado]); this is only the weather that sends them**, as [Sandfall] sends its
 * columns. One comes only while the Age thunders, upwind of somebody so its travel brings it their way, and about once
 * an in-game day for each player in an ordinary Age; [Phenomenon.TORNADO] raises the thunder enough for that. **Never
 * more than one at once in an Age** (Jonah): each is hundreds of entities in the air. The frequency is the wait
 * between them, the strength how wide and strong each is, and the pace how fast it goes.
 */
object TornadoStorms {

    /** One tick of it: whether another tornado gathers, and where. */
    fun gather(level: ServerLevel, befalling: Befalling, dials: TornadoDials) {
        if (!level.isThundering) return
        val watching = Sampling.watchers(level).size
        if (watching == 0) return
        val isOneStanding = level.getEntities(EntityTypeTest.forClass(Tornado::class.java)) { true }.isNotEmpty()
        if (isOneStanding) return
        val owed = PhenomenaCeiling.isOwed(level, Phenomenon.TORNADO) || befalling.recurrence.isConstant
        val between = (BETWEEN_WHILE_THUNDERING / befalling.recurrence.often / (ONE + dials.often) / watching)
            .toInt().coerceAtLeast(ONE)
        if (!owed && level.random.nextInt(between) != NOW) return
        if (!PhenomenaCeiling.mayBegin(level, Phenomenon.TORNADO)) return
        val raised = raiseUpwindOfSomebody(level, dials) ?: return
        PhenomenaCeiling.began(level, Phenomenon.TORNADO, raised)
    }

    /**
     * Gathers one upwind of somebody, a little to one side, so the wind brings it their way and it may pass near or
     * wide: where it goes is a surprise, that it is coming is not. Several places are tried, since the far side may
     * not be loaded.
     */
    private fun raiseUpwindOfSomebody(level: ServerLevel, dials: TornadoDials): Tornado? {
        val watcher = Sampling.somebody(level) ?: return null
        val random = level.random
        val live = (level.server.playerList.simulationDistance - CHUNKS_SHORT_OF_THE_EDGE) * CHUNK_WIDTH
        val furthest = minOf(FURTHEST, live.toDouble()).coerceAtLeast(NEAREST)
        val bearing = Atmosphere.prevailingBearingDegrees(Ages.recipeOf(level)?.seed ?: level.seed) * Mth.DEG_TO_RAD
        val downwindX = cos(bearing)
        val downwindZ = sin(bearing)
        repeat(PLACES_TRIED) {
            val away = Mth.nextDouble(random, NEAREST, furthest)
            val aside = Mth.nextDouble(random, -FURTHEST_ASIDE, FURTHEST_ASIDE)
            val atX = watcher.x - downwindX * away - downwindZ * aside
            val atZ = watcher.z - downwindZ * away + downwindX * aside
            val raised = Tornado.raise(level, atX, atZ, sizeFor(random.nextDouble(), dials.size), dials.pace)
            if (raised != null) return raised
        }
        return null
    }

    /**
     * How wide one is, from nought for the narrowest to one for the widest: mostly narrow in an ordinary Age, the
     * widest rare, and the harder its size dial the wider all of them.
     */
    private fun sizeFor(draw: Double, size: Double): Double =
        size + (ONE - size) * draw.pow(MOSTLY_NARROW) * ORDINARY_REACH

    /**
     * How long, in ticks of thunder, between tornadoes for each player in an ordinary Age: once a day, if its storms
     * thunder about a third of the time. Calibrated by eye, not measured.
     */
    private const val BETWEEN_WHILE_THUNDERING = 8000.0

    /** How far upwind of somebody one gathers, in blocks, and how far to one side. */
    private const val NEAREST = 96.0
    private const val FURTHEST = 176.0
    private const val FURTHEST_ASIDE = 48.0
    private const val PLACES_TRIED = 8
    private const val CHUNKS_SHORT_OF_THE_EDGE = 1
    private const val CHUNK_WIDTH = 16

    /** An ordinary one reaches at most half way from the narrowest to the widest, and is mostly near the narrowest. */
    private const val ORDINARY_REACH = 0.5
    private const val MOSTLY_NARROW = 2.0

    private const val ONE = 1
    private const val NOW = 0
}
