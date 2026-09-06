package co.voik.agesandtheart.content

import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player

/**
 * Boots with a rime blade under them: the ground stops holding you back.
 *
 * **Not merely slippery — faster than walking** (Jonah, 2026-09-05), and the parts are separate on purpose
 * because friction alone cannot do it. Vanilla runs a skater on one number: `travelInAir` reads the
 * block's friction, hands it to `getFrictionInfluencedSpeed` — which divides acceleration by its *cube* —
 * and then multiplies it by `0.91` to get the drag that decides how much speed survives the tick. So one
 * value sets both, and it sets them against each other: a slicker floor holds your speed better and
 * accelerates you *worse*, which is why blue ice tops out at under twice a walk however long the straight.
 *
 * **This takes the two apart.** [gripUnderfoot] answers the first question and [dampingOn] the second, so
 * how quickly a skater gets going and how long they keep it are independent dials.
 *
 * **Nothing is lost in the air** (Jonah, 2026-09-06), which is the one that makes them feel good rather
 * than merely fast: speed carries across a jump, so a gap is an opportunity instead of a stop. It is also
 * the one that needs a governor — air control adds a fixed nudge every tick, so with no drag at all
 * holding forward off a ledge would accelerate you without bound. [TOP_SPEED] is that governor, and it
 * sits at the speed the ground already gives, so a jump *keeps* speed and cannot build it.
 *
 * **What you buy and what you pay.** They are slow off the mark, quick once running, and they do not stop
 * — which is the whole trade. A corridor is worse than a plain, a ledge is a real hazard, and none of that
 * needed designing in: it is what low drag *is*.
 */
object RimeSkates {

    /**
     * The friction the *acceleration* is worked out from, or null where the ground's own answer stands.
     *
     * Lower than the ground is slick, and deliberately: this feeds nothing but
     * `getFrictionInfluencedSpeed`, whose cube law would otherwise make the slickness that carries a skater
     * along also be the thing stopping them getting going.
     *
     * Only while standing on something. In the air vanilla ignores this and uses the flying speed instead.
     */
    @JvmStatic
    fun gripUnderfoot(entity: LivingEntity): Float? =
        if (skating(entity) && entity.onGround()) GRIP_UNDER_A_PUSH else null

    /**
     * What survives of a skater's speed this tick, given what [vanilla] would have kept.
     *
     * Three answers rather than one: the ground holds nearly all of it, the air takes none of it at all,
     * and anything already over [TOP_SPEED] is bled back down. That last is not a special case so much as
     * the price of the second — see the class note.
     */
    @JvmStatic
    fun dampingOn(entity: LivingEntity, vanilla: Float): Float {
        if (!skating(entity)) return vanilla
        if (goingFasterThanTheyMay(entity)) return OVER_THE_TOP
        return if (entity.onGround()) HOLDS_ITS_SPEED else NOTHING_IN_THE_AIR
    }

    private fun skating(entity: LivingEntity): Boolean =
        entity is Player && entity.getItemBySlot(EquipmentSlot.FEET).item === AgeContent.RIME_SKATES

    private fun goingFasterThanTheyMay(entity: LivingEntity): Boolean {
        val movement = entity.deltaMovement
        val alongTheGround = movement.x * movement.x + movement.z * movement.z
        return alongTheGround > TOP_SPEED * TOP_SPEED
    }

    /**
     * Slicker than stone and nowhere near ice, which is what makes a skater quick off the mark.
     *
     * Against the cube law this is about three fifths more push per tick than blue ice's own number gave,
     * so the speed the old skates topped out at arrives in roughly a third of the time.
     */
    private const val GRIP_UNDER_A_PUSH = 0.88f

    /** Most of it, so a skater coasts: about eight ticks to shed half their speed, against five on ice. */
    private const val HOLDS_ITS_SPEED = 0.92f

    /** None of it. A jump is for crossing a gap without paying for it. */
    private const val NOTHING_IN_THE_AIR = 1.0f

    /** Above the ceiling, enough drag to come back under it without reading as a wall. */
    private const val OVER_THE_TOP = 0.90f

    /**
     * The ceiling, in blocks a tick — about thirteen a second, three times a walk.
     *
     * **Set at what the ground already gives**, so the air's freedom from drag lets a skater *keep* their
     * speed over a gap and never build it: hopping is not a way to go faster than skating.
     *
     * It is also the hard limit the design has always owed (§7.1.2): skates must not outrun a boat on blue
     * ice, which is believed to be about forty blocks a second. **That forty is quoted and never measured**
     * — if it turns out lower, this is the number that has to come down.
     */
    private const val TOP_SPEED = 0.66
}
