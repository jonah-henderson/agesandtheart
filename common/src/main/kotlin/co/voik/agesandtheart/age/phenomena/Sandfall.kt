package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Phenomenon
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.entity.EntityTypeTest
import kotlin.math.cos
import kotlin.math.sin

/**
 * An Age that columns of sand walk across, burying what they cross (design §5.2.2).
 *
 * **The phenomenon is the column ([SandColumn]); this is only the weather that sends them.** Everything
 * that happens once one is standing happens on the entity, which is what lets this stay a handful of rolls
 * per tick however many columns are out.
 *
 * **Its telegraph is the strongest in the set, and that is what it is for.** §5.2 asks a process to be
 * inexorable and legible with a visible direction, and a column standing from the ground to the sky moving
 * in a straight line is exactly that — the only hazard here that can be seen from another biome. Because
 * the counterplay is *spatial* (get out from under it), the warning has to be spatial too, which is the
 * granularity rule `decisions.md` sets.
 *
 * **What it denies is the surface, and light.** Farms, paths and doors go under; a falling block breaks a
 * torch, so a buried base goes dark and begins spawning things in itself. A roof answers the first and
 * lighting with something a block cannot break answers the second. **And it is a supply** — burial hands
 * you unlimited sand and therefore glass, which is the one hazard in the set that is also a reason to be
 * there.
 */
object Sandfall {

    /**
     * One tick of it: whether another column stands up, and where.
     *
     * **The brief's two levers, one each.** The rung is how many may stand together — `teeming sandfall` is
     * four columns at once — and the book's frequency is the wait between them, so `frequent sandfall` is one
     * column arriving more often and `constant sandfall` is never a moment without one.
     */
    fun wander(level: ServerLevel, befalling: Befalling, dials: SandfallDials) {
        val behaviour = SandfallBehaviour.of(level.server)
        // A pack that wants an Age with none says so by writing none, and is not overruled by a rung.
        if (behaviour.atMost <= NONE) return

        val standing = standingIn(level)
        if (standing >= behaviour.atMostFor(befalling.amount, dials.often)) return
        // A constant sandfall always has a column walking: the next arrives as soon as none is. And one whose roll
        // came up with nowhere to stand is still on its way.
        val owed = PhenomenaCeiling.isOwed(level, Phenomenon.SANDFALL) ||
            (befalling.recurrence.isConstant && standing == NONE) || level in stillToArrive
        // The rung shortens the wait as it raises the count, and the frequency divides what is left.
        val between = (behaviour.betweenSpawnsFor(befalling.amount, dials.often) / befalling.recurrence.often)
            .toInt().coerceAtLeast(AT_ONCE)
        if (!owed && level.random.nextInt(between) != NOW) return
        if (!PhenomenaCeiling.mayBegin(level, Phenomenon.SANDFALL)) return

        if (raiseOneNearSomebody(level, behaviour, dials)) stillToArrive -= level else stillToArrive += level
    }

    /**
     * The levels whose column came up and found nowhere it could stand, which try again next tick rather than wait
     * for another roll. Weakly held, and a smoothing of when weather arrives rather than anything worth keeping.
     */
    private val stillToArrive: MutableSet<ServerLevel> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    /** How many are already out. Bounded by [SandfallBehaviour.atMost], so this is a walk over a handful. */
    private fun standingIn(level: ServerLevel): Int =
        level.getEntities(EntityTypeTest.forClass(SandColumn::class.java)) { true }.size

    /**
     * Stands one up out past somebody, headed roughly back at them.
     *
     * **Roughly, and not at them.** A column aimed exactly at a player is a scripted event rather than
     * weather; one aimed within [SPREAD_DEGREES] of them usually passes near and sometimes passes wide,
     * which is `decisions.md`'s "unpredictable scheduling, never unannounced arrival" — where it goes is a
     * surprise, that it is coming is not.
     *
     * **Several bearings are tried because the far side of the spawn ring may not be loaded**, and a column
     * may only be raised where there is already a chunk to stand it on ([SandColumn.raise]). Whether one stood.
     */
    private fun raiseOneNearSomebody(level: ServerLevel, behaviour: SandfallBehaviour, dials: SandfallDials): Boolean {
        val watcher = Sampling.somebody(level) ?: return false
        val random = level.random
        // Within the chunks that tick entities, where a column may stand: a server simulating fewer than the ring
        // reaches drew spots it could never use, and lost the column.
        val live = (level.server.playerList.simulationDistance - CHUNKS_SHORT_OF_THE_EDGE) * CHUNK_WIDTH
        val furthest = minOf(behaviour.furthestSpawn, live).coerceAtLeast(AT_ONCE)
        val nearest = minOf(behaviour.nearestSpawn, furthest / 2)
        val spread = furthest - nearest
        repeat(BEARINGS_TRIED) {
            val away = nearest + random.nextInt(spread.coerceAtLeast(AT_ONCE))
            val bearing = random.nextDouble() * FULL_TURN
            val heading = bearing + HALF_TURN + (random.nextDouble() - random.nextDouble()) * SPREAD_DEGREES
            val raised = raise(
                level = level,
                atX = watcher.x - sin(bearing * Mth.DEG_TO_RAD) * away,
                atZ = watcher.z + cos(bearing * Mth.DEG_TO_RAD) * away,
                headingDegrees = heading.toFloat(),
                dials = dials,
            )
            if (raised != null) return true
        }
        return false
    }

    /**
     * **The one place a column is rolled**, whoever asked for one: its speed, and its width, depth and
     * lifetime at these [dials]. A [lifetime] in ticks replaces the rolled one.
     */
    fun raise(
        level: ServerLevel,
        atX: Double,
        atZ: Double,
        headingDegrees: Float,
        dials: SandfallDials,
        lifetime: Int? = null,
    ): SandColumn? {
        val behaviour = SandfallBehaviour.of(level.server)
        val column = behaviour.column
        val random = level.random
        // Each read at its own dial — see [SandfallBehaviour].
        val narrowest = behaviour.narrowestHalfWidthAt(dials.size)
        val widest = behaviour.widestHalfWidthAt(dials.size)
        val shortest = behaviour.shortestLifeAt(dials.long)
        val longest = behaviour.longestLifeAt(dials.long)
        val raised = SandColumn.raise(
            level = level,
            atX = atX,
            atZ = atZ,
            headingDegrees = headingDegrees,
            speed = (column.slowestSpeed + random.nextDouble() * (column.fastestSpeed - column.slowestSpeed)) * dials.pace,
            lifetime = lifetime ?: (shortest + random.nextInt((longest - shortest).coerceAtLeast(AT_ONCE))),
            // No two quite alike, which is the whole of why this is a range rather than a number.
            fullHalfWidth = narrowest + random.nextDouble() * (widest - narrowest),
            depth = behaviour.depthAt(dials.depth),
            scour = dials.scour,
        )
        if (raised != null) PhenomenaCeiling.began(level, Phenomenon.SANDFALL, raised)
        return raised
    }

    /** How far off a bearing straight back at the player one may be aimed. */
    private const val SPREAD_DEGREES = 55.0

    private const val BEARINGS_TRIED = 8

    /** How many chunks short of the simulated edge a column stands, so its whole width is ticking. */
    private const val CHUNKS_SHORT_OF_THE_EDGE = 1
    private const val CHUNK_WIDTH = 16
    private const val FULL_TURN = 360.0
    private const val HALF_TURN = 180.0
    private const val NONE = 0
    private const val NOW = 0
    private const val AT_ONCE = 1
}
