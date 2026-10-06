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
     * **The rung is the brief's two levers at once.** A stronger claim raises how many may stand together
     * *and* shortens the wait between them — `teeming sandfall` is four columns arriving four times as
     * often, `scarce sandfall` is one arriving four times as rarely — and the phenomenon needs no parameter of
     * its own to be dialled.
     */
    fun wander(level: ServerLevel, density: Double, dials: SandfallDials) {
        val behaviour = SandfallBehaviour.of(level.server)
        // A pack that wants an Age with none says so by writing none, and is not overruled by a rung.
        if (behaviour.atMost <= NONE) return

        if (standingIn(level) >= behaviour.atMostFor(density, dials.often)) return
        val owed = PhenomenaCeiling.isOwed(level, Phenomenon.SANDFALL)
        if (!owed && level.random.nextInt(behaviour.betweenSpawnsFor(density, dials.often)) != NOW) return
        if (!PhenomenaCeiling.mayBegin(level, Phenomenon.SANDFALL)) return

        raiseOneNearSomebody(level, behaviour, dials)
    }

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
     * may only be raised where there is already a chunk to stand it on ([SandColumn.raise]).
     */
    private fun raiseOneNearSomebody(level: ServerLevel, behaviour: SandfallBehaviour, dials: SandfallDials) {
        val watcher = Sampling.somebody(level) ?: return
        val random = level.random
        val spread = behaviour.furthestSpawn - behaviour.nearestSpawn
        repeat(BEARINGS_TRIED) {
            val away = behaviour.nearestSpawn + random.nextInt(spread.coerceAtLeast(AT_ONCE))
            val bearing = random.nextDouble() * FULL_TURN
            val heading = bearing + HALF_TURN + (random.nextDouble() - random.nextDouble()) * SPREAD_DEGREES
            val raised = raise(
                level = level,
                atX = watcher.x - sin(bearing * Mth.DEG_TO_RAD) * away,
                atZ = watcher.z + cos(bearing * Mth.DEG_TO_RAD) * away,
                headingDegrees = heading.toFloat(),
                dials = dials,
            )
            if (raised != null) return
        }
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
            speed = column.slowestSpeed + random.nextDouble() * (column.fastestSpeed - column.slowestSpeed),
            lifetime = lifetime ?: (shortest + random.nextInt((longest - shortest).coerceAtLeast(AT_ONCE))),
            // No two quite alike, which is the whole of why this is a range rather than a number.
            fullHalfWidth = narrowest + random.nextDouble() * (widest - narrowest),
            depth = behaviour.depthAt(dials.depth),
        )
        if (raised != null) PhenomenaCeiling.began(level, Phenomenon.SANDFALL, raised)
        return raised
    }

    /** How far off a bearing straight back at the player one may be aimed. */
    private const val SPREAD_DEGREES = 55.0

    private const val BEARINGS_TRIED = 8
    private const val FULL_TURN = 360.0
    private const val HALF_TURN = 180.0
    private const val NONE = 0
    private const val NOW = 0
    private const val AT_ONCE = 1
}
