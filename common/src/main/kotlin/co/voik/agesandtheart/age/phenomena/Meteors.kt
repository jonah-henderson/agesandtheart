package co.voik.agesandtheart.age.phenomena

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An Age that the sky falls on in showers (design §5.2).
 *
 * **The phenomenon is the storm ([MeteorStorm]); this is only the weather that gathers them.** Everything
 * that happens once one is up happens on the entity, which is what lets this stay a couple of rolls a tick
 * however long a storm runs — the same division `Sandfall` makes, and for the same reason.
 *
 * **Its three dials are all instability's** (Jonah, 2026-09-06), which is one more than any other
 * phenomenon here has: how *often* a storm gathers, how *long* it lasts, and how *hard* the bodies come
 * in. A rung raises all three, so `teeming meteors` is not merely more storms but worse ones — which is
 * what stops the top of the ramp reading as the bottom repeated.
 */
object Meteors {

    /**
     * One tick of it: whether another storm gathers, and where.
     *
     * **At most one at a time under an ordinary claim.** A shower is an event you notice and answer, and
     * two at once over the same country would read as weather rather than as an arrival — so the count is
     * what a rung raises first.
     */
    fun fall(level: ServerLevel, density: Double, fury: Double) {
        if (level.players().isEmpty()) return
        if (gatheringIn(level) >= atMostFor(density)) return
        if (level.random.nextInt(betweenStormsFor(density)) != NOW) return
        gatherOneNearSomebody(level, density, fury)
    }

    /** How many are already up. Bounded by [atMostFor], so this is a walk over one or two. */
    private fun gatheringIn(level: ServerLevel): Int =
        level.getEntities(EntityTypeTest.forClass(MeteorStorm::class.java)) { true }.size

    /**
     * Gathers one out past somebody, high up.
     *
     * **Out past, and never overhead.** A storm centred on a player is a scripted event rather than
     * weather, and design §5.2 refuses it in as many words: falling *near you* rather than *over an area
     * you are in* makes shelter useless, because you are being aimed at. One gathered a little way off is
     * a place you can see being pounded, walk out of, or walk toward once it is over.
     */
    private fun gatherOneNearSomebody(level: ServerLevel, density: Double, fury: Double) {
        val random = level.random
        val somebody = level.players()[random.nextInt(level.players().size)]
        val bearing = random.nextDouble() * FULL_TURN
        val away = NEAREST_APPROACH + random.nextDouble() * (FURTHEST_APPROACH - NEAREST_APPROACH)
        val where = Vec3(
            somebody.x + cos(bearing) * away,
            somebody.y + OVERHEAD,
            somebody.z + sin(bearing) * away,
        )
        val falling = MeteorStorm.SHORTEST_FALL +
            random.nextInt(MeteorStorm.LONGEST_FALL - MeteorStorm.SHORTEST_FALL + ONE)
        MeteorStorm.gatherAt(level, where, bodiesFor(density), lengthenedBy(falling, fury), fury)
    }

    /** How many storms may be up at once — one ordinarily, and more as a rung asks for more. */
    private fun atMostFor(density: Double): Int = (ONE + density * MORE_AT_ONCE).roundToInt()

    /** How many bodies a storm splits into, which is also what the sky promises. */
    private fun bodiesFor(density: Double): Int = (FEWEST_BODIES + density * MORE_BODIES).roundToInt()

    /** A storm's own length, stretched by how fierce the Age is. */
    private fun lengthenedBy(falling: Int, fury: Double): Int =
        (falling * (ONE_WHOLE + fury * LONGER_WHEN_FIERCE)).roundToInt()

    /**
     * How long to wait between storms, in ticks of rolling.
     *
     * A quiet Age goes minutes between them; a teeming one is scarcely out of one. Divided rather than
     * subtracted so the ends of the range stay proportionate however the middle is tuned.
     */
    private fun betweenStormsFor(density: Double): Int =
        (BETWEEN_STORMS / (ONE_WHOLE + density * MORE_OFTEN)).roundToInt().coerceAtLeast(ONE)

    private const val NOW = 0
    private const val ONE = 1
    private const val ONE_WHOLE = 1.0

    /** Far enough out to be somewhere else, near enough to see and to reach afterwards. */
    private const val NEAREST_APPROACH = 40.0
    private const val FURTHEST_APPROACH = 110.0

    /** Where a storm hangs while it drops, above the player it gathered near. */
    private const val OVERHEAD = 70.0

    private const val MORE_AT_ONCE = 1.5
    private const val FEWEST_BODIES = 3.0
    private const val MORE_BODIES = 6.0
    private const val LONGER_WHEN_FIERCE = 0.6
    private const val BETWEEN_STORMS = 3600.0
    private const val MORE_OFTEN = 5.0

    private const val FULL_TURN = 2.0 * PI
}
