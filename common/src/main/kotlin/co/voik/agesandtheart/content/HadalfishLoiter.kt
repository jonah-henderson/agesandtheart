package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * What a hadalfish does when nobody is there: almost nothing.
 *
 * **Hanging still is the behaviour, not the absence of one** (Jonah, 2026-09-10). Vanilla's `RandomSwimming`
 * would have it pottering about the whole time, which makes a thing look busy and harmless; a hunter should
 * be a shape in the dark that has not moved since you first saw it. So the default is to damp its drift to
 * a stop and wait, and only rarely to shift to another patch of the deep.
 *
 * **It only ever moves to deep water**, which keeps the mini-boss where its reward is: a fish that wandered
 * up into the shallows would be fightable from a boat with none of the pressure or the dark that the
 * encounter is made of.
 */
class HadalfishLoiter(private val fish: Hadalfish) : Goal() {

    private var settledFor = 0

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean = fish.target == null && fish.isInWater

    override fun canContinueToUse(): Boolean = canUse() && !fish.navigation.isDone

    override fun start() {
        settledFor = 0
    }

    override fun tick() {
        if (!fish.navigation.isDone) return
        // Hold station — and **hold depth**, which is the half that damping alone does not buy. Gravity is
        // reduced in water rather than absent, so a fish that only had its drift damped sank about three
        // blocks in five seconds and would have ended up on the seabed. Zeroing the vertical outright is
        // what makes it hang there, which is the whole image: a shape in the dark that has not moved.
        val drift = fish.deltaMovement
        fish.deltaMovement = Vec3(drift.x * SETTLING, 0.0, drift.z * SETTLING)
        if (++settledFor < restlessness()) return
        settledFor = 0
        somewhereElseDeep()?.let { fish.navigation.moveTo(it.x + HALF, it.y + HALF, it.z + HALF, DRIFTS_AT) }
    }

    /**
     * A patch of deep water within reach, or null — in which case it simply stays where it is.
     *
     * **Sampled rather than searched.** A few darts at random beat a flood fill for something that has
     * minutes to find somewhere to be, and a fish that occasionally fails to find anywhere is a fish that
     * stays put, which is the behaviour anyway.
     */
    private fun somewhereElseDeep(): BlockPos? {
        val level = fish.level()
        repeat(TRIES) {
            val at = fish.blockPosition().offset(
                fish.random.nextInt(-REACHES, REACHES),
                fish.random.nextInt(-RISES, RISES),
                fish.random.nextInt(-REACHES, REACHES),
            )
            if (level.getFluidState(at).`is`(DeepWater.DEEP_WATER)) return at
        }
        return null
    }

    /** How long it holds still, which is deliberately a long and uneven time. */
    private fun restlessness(): Int = STILL_FOR_AT_LEAST + fish.random.nextInt(STILL_FOR_UP_TO_ANOTHER)

    private companion object {
        /** Per tick. Its drift is a tenth of what it was inside a second. */
        const val SETTLING = 0.8

        /** Twenty to fifty seconds between moves. */
        const val STILL_FOR_AT_LEAST = 400
        const val STILL_FOR_UP_TO_ANOTHER = 600

        const val TRIES = 12
        const val REACHES = 20
        const val RISES = 8

        const val DRIFTS_AT = 0.7
        const val HALF = 0.5
    }
}
