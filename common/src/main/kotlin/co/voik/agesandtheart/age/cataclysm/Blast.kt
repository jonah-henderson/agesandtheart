package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.age.phenomena.Gale
import co.voik.agesandtheart.age.phenomena.Sampling
import co.voik.agesandtheart.generation.Ages
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The air blast of an asteroid's strike (design §5.3 "The asteroid"), reaching the Age long after the flash and after
 * the rumble through the ground: **a shock front** that throws everything out in it away from the impact, then **a
 * wind** out of the impact's quarter harder than any gale, which nobody walks into, howling through the debris' travel
 * and the heat, surging again with the wall of ash and dying as the dust comes down.
 *
 * **A gale to everything that blows one** ([Gale.Blowing]), so the clouds race, the rain leans, the creatures and the
 * dropped things are driven and it is heard as a gale is; only its fury goes past a gale's whole, which puts its lean
 * past a sprint. Worked out from the schedule on either side rather than sent, and laid over whatever gale the Age
 * has of its own ([over]).
 */
object Blast {

    /** The moment the shock front reaches the Age. */
    fun shockAt(doom: Doom): Long = doom.impactAt + SHOCK_ARRIVES_AFTER

    /** The blast blowing at [time], or null before the shock front and once the dust has stilled it. */
    fun blowingAt(doom: Doom, time: Double): Gale.Blowing? {
        val since = time - shockAt(doom)
        if (since < 0.0) return null
        val risen = Mth.smoothstep((since / SHOCK_RISES_TICKS).coerceIn(0.0, 1.0).toFloat()).toDouble()
        val (lying, fury) = when (doom.phaseAt(time.toLong())) {
            ImpactPhase.TRAVEL -> risen to HOWLING + (SHOCK - HOWLING) * exp(-since / SHOCK_EASES_TICKS)
            ImpactPhase.HEAT -> risen to Mth.lerp(doom.progressThrough(ImpactPhase.HEAT, time), HOWLING, FIRESTORM)
            ImpactPhase.DUST -> {
                val sinceTheAsh = (time - doom.ashArrivesAt).coerceAtLeast(0.0)
                (1.0 - doom.progressThrough(ImpactPhase.DUST, time) / STILLED_BY) * risen to
                    FIRESTORM + (ASH_SURGE - FIRESTORM) * exp(-sinceTheAsh / ASH_SURGE_EASES_TICKS)
            }
            ImpactPhase.APPROACH, ImpactPhase.ENTRY, ImpactPhase.AFTER, null -> NONE to NONE
        }
        if (lying <= NONE) return null
        return Gale.Blowing(lying.coerceAtMost(WHOLLY), drivingDegrees(doom), fury)
    }

    /** Which way the blast drives, in a gale's degrees: straight away from the impact. */
    fun drivingDegrees(doom: Doom): Double = doom.bearingDegrees + QUARTER_TURN

    /** [blowing] with [blast] over it: swung round to the blast's bearing as it rises, and at the fiercer fury of the two. */
    fun over(blowing: Gale.Blowing?, blast: Gale.Blowing?): Gale.Blowing? = when {
        blast == null -> blowing
        blowing == null -> blast
        else -> Gale.Blowing(
            lying = maxOf(blowing.lying, blast.lying),
            bearingDegrees = Gale.turnedTowards(blowing.bearingDegrees, blast.bearingDegrees, blast.lying),
            fury = maxOf(blowing.fury, blast.fury),
        )
    }

    /** [blowing] over [level] with the blast of whatever end is coming to it over that. */
    fun over(level: ServerLevel, blowing: Gale.Blowing?): Gale.Blowing? {
        val doom = Ages.recipeOf(level)?.let(Cataclysms::of) ?: return blowing
        return over(blowing, blowingAt(doom, level.gameTime.toDouble()))
    }

    /**
     * The shock front passing over [level]: everything living or dropped near a player and out in it thrown away from
     * the impact and off its feet. Players are thrown by their own clients ([throwOff]), which move them.
     */
    fun strike(level: ServerLevel, doom: Doom) {
        val thrown = HashSet<Entity>()
        for (player in Sampling.watchers(level)) {
            val around = AABB(player.blockPosition()).inflate(THROWN_WITHIN)
            for (entity in level.getEntities(player, around, ::isThrown)) {
                if (thrown.add(entity)) throwOff(entity, doom, Sampling.exposureAt(level, entity.blockPosition()))
            }
        }
    }

    private fun isThrown(entity: Entity): Boolean {
        val isMovable = entity is LivingEntity || entity is ItemEntity
        return isMovable && entity !is Player && !entity.isPassenger && !entity.isNoGravity && !entity.isSpectator
    }

    /** [entity] thrown by the shock front as hard as it is [exposed] to it. */
    fun throwOff(entity: Entity, doom: Doom, exposed: Float) {
        if (exposed <= 0.0f) return
        val driving = Math.toRadians(drivingDegrees(doom))
        val away = Vec3(cos(driving) * THROWN_ALONG, THROWN_UP, sin(driving) * THROWN_ALONG).scale(exposed.toDouble())
        entity.push(away)
    }

    /** Twenty seconds after the flash, and fourteen after the rumble through the ground: air is slower than rock. */
    private const val SHOCK_ARRIVES_AFTER = 400L

    /** Half a second from a still air to the whole of the wind. */
    private const val SHOCK_RISES_TICKS = 10.0

    /**
     * How furious the wind is, a whole gale being one and its lean going as the square: past half again a sprint
     * behind the shock front, easing over a quarter of a minute to about a sprint and holding there through the
     * debris' travel, then dropping through the heat to a hard gale fanning the fires.
     */
    private const val SHOCK = 2.2
    private const val HOWLING = 1.8
    private const val FIRESTORM = 1.0
    private const val SHOCK_EASES_TICKS = 300.0

    /** How far through the dust the wind has died, and how hard it surges as the wall of ash arrives, easing over how long. */
    private const val STILLED_BY = 0.5
    private const val ASH_SURGE = 1.7
    private const val ASH_SURGE_EASES_TICKS = 200.0

    private const val THROWN_WITHIN = 48.0
    private const val THROWN_ALONG = 1.1
    private const val THROWN_UP = 0.5

    private const val QUARTER_TURN = 90.0
    private const val NONE = 0.0
    private const val WHOLLY = 1.0
}
