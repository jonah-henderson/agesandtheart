package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
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
 * encounter is made of. One that finds itself above the abyss, however it came to be there, heads straight
 * back down — to deep water, or failing that as low in the water as it can find.
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
        if (!isInTheDeep()) {
            // Out of the abyss it goes back down at once rather than waiting out its restlessness in the
            // shallows (Jonah, walked A7 2026-09-14), and keeps looking while it is still out.
            val timeToLook = settledFor++ % LOOKS_FOR_THE_DEEP_EVERY == 0
            if (timeToLook) wayDown()?.let(::driftTo)
            return
        }
        if (++settledFor < restlessness()) return
        settledFor = 0
        somewhereElseDeep()?.let(::driftTo)
    }

    private fun driftTo(at: BlockPos) {
        fish.navigation.moveTo(at.x + HALF, at.y + HALF, at.z + HALF, DRIFTS_AT)
    }

    private fun isInTheDeep(): Boolean = fish.level().getFluidState(fish.blockPosition()).`is`(DeepWater.DEEP_WATER)

    /**
     * Deep water to go down to, or failing that the lowest water it can find — the bottom of the shallows
     * being as near the deep as it can get — or null where there is nothing lower than it already is.
     *
     * Straight down first, since after a hunt the abyss is usually right under it; then darts spread wide and
     * low, as [somewhereElseDeep] throws them. The first deep water found ends it.
     */
    private fun wayDown(): BlockPos? {
        val level = fish.level()
        val from = fish.blockPosition()
        var lowest: BlockPos? = null
        for (step in 1..SOUNDS_DOWN) {
            val at = from.below(step)
            val fluid = level.getFluidState(at)
            if (fluid.`is`(DeepWater.DEEP_WATER)) return at
            if (!fluid.`is`(FluidTags.WATER)) break
            lowest = at
        }
        repeat(TRIES) {
            val at = from.offset(
                fish.random.nextInt(-SEARCHES_ACROSS, SEARCHES_ACROSS + 1),
                -fish.random.nextInt(SOUNDS_DOWN + 1),
                fish.random.nextInt(-SEARCHES_ACROSS, SEARCHES_ACROSS + 1),
            )
            val fluid = level.getFluidState(at)
            if (fluid.`is`(DeepWater.DEEP_WATER)) return at
            val isLowerWater = fluid.`is`(FluidTags.WATER) && at.y < (lowest?.y ?: from.y)
            if (isLowerWater) lowest = at
        }
        return lowest
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
                // **Down further than up**, so idling settles it deeper rather than letting a long series
                // of even draws walk it toward the ceiling of the abyss. Where its reward is, is where it
                // should be found (Jonah, 2026-09-10).
                fish.random.nextInt(-SINKS, RISES),
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

        /** Three down for every one up — see [somewhereElseDeep]. */
        const val RISES = 8
        const val SINKS = 24

        /** Out of the abyss, how often it looks for a way back down — once a second. */
        const val LOOKS_FOR_THE_DEEP_EVERY = 20

        /** How far straight down it looks, and how deep its darts reach: a hunt gives up sixteen over the line. */
        const val SOUNDS_DOWN = 48
        const val SEARCHES_ACROSS = 24

        const val DRIFTS_AT = 0.7
        const val HALF = 0.5
    }
}
