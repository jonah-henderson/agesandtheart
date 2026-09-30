package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * A scarab eating: a torchflower, which it grazes back to a shoot rather than taking, or a mushroom, which
 * it takes (design §7.1.2).
 *
 * **Grazing resets the flower to [GrazedTorchflowerBlock]**, so the colony never eats its own habitat. A
 * mushroom or a mushroom block is gone once eaten, which is griefing, and answers to `mob_griefing` as a
 * rabbit's carrots do.
 *
 * Where nothing is in reach it goes back to [Scarab.lastMeal] and looks again from there — the fox's
 * berry-eating goal with a memory, since a patch of torchflowers is a place worth returning to.
 */
class ScarabGraze(private val scarab: Scarab) : Goal() {

    private var food: BlockPos? = null
    private var returningTo: BlockPos? = null
    private var eatingFor = 0
    private var travelling = 0
    private var lookAgainAfter = 0L

    /** How close it has got to what it is going for, and how long since it last got closer. */
    private var closest = Double.MAX_VALUE
    private var sinceCloser = 0

    /** Food it could not reach, and until when it is left alone — a flower under a bush, a cap behind a wall. */
    private val outOfReach = mutableMapOf<BlockPos, Long>()

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean {
        val level = scarab.level() as? ServerLevel ?: return false
        if (!scarab.hasRoomToEat || level.gameTime < lookAgainAfter) return false
        food = foodNear(level, scarab.blockPosition())
        returningTo = if (food == null) patchWorthReturningTo() else null
        if (food == null && returningTo == null) lookAgainAfter = level.gameTime + LOOK_AGAIN_AFTER
        return food != null || returningTo != null
    }

    override fun canContinueToUse(): Boolean =
        scarab.hasRoomToEat && (food != null || returningTo != null) && travelling < GIVES_UP_AFTER

    override fun start() {
        eatingFor = 0
        travelling = 0
        closest = Double.MAX_VALUE
        sinceCloser = 0
    }

    override fun stop() {
        food = null
        returningTo = null
        scarab.navigation.stop()
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun tick() {
        val level = scarab.level() as? ServerLevel ?: return
        travelling++
        val eating = food
        if (eating != null) return graze(level, eating)
        val patch = returningTo ?: return
        val there = Vec3.atCenterOf(patch)
        scarab.headFor(there)
        if (!scarab.isNear(there, NEAR)) return
        returningTo = null
        food = foodNear(level, scarab.blockPosition())
        // An empty patch is forgotten, so a colony does not go on flying to a place it has eaten bare.
        if (food == null) scarab.lastMeal = null
    }

    private fun graze(level: ServerLevel, at: BlockPos) {
        val state = level.getBlockState(at)
        if (!isFood(level, state)) {
            food = null
            return
        }
        val over = Vec3.atBottomCenterOf(at).add(0.0, HOVERS_OVER_A_FLOWER, 0.0)
        scarab.headFor(over)
        if (!scarab.isNear(over, EATS_WITHIN)) return keepGettingCloser(level, at, over)
        scarab.lookControl.setLookAt(Vec3.atCenterOf(at))
        eatingFor++
        if (eatingFor < EATING_TICKS) return
        eat(level, at, state)
        food = null
    }

    /**
     * Gives up on [at] once it has stopped getting any closer to it, and leaves it alone a while — or a
     * flower it cannot get at holds it forever, since the nearest food is the one it always picks.
     */
    private fun keepGettingCloser(level: ServerLevel, at: BlockPos, over: Vec3) {
        val distance = scarab.position().distanceTo(over)
        if (distance < closest - PROGRESS) {
            closest = distance
            sinceCloser = 0
            return
        }
        if (++sinceCloser < NO_CLOSER_FOR) return
        outOfReach[at] = level.gameTime + LEFT_ALONE_FOR
        food = null
        closest = Double.MAX_VALUE
        sinceCloser = 0
    }

    private fun eat(level: ServerLevel, at: BlockPos, state: BlockState) {
        if (state.`is`(Blocks.TORCHFLOWER)) {
            level.setBlockAndUpdate(at, AgeContent.GRAZED_TORCHFLOWER_BLOCK.defaultBlockState())
            scarab.eat(Scarab.Meal.TORCHFLOWER)
            scarab.lastMeal = at
        } else {
            level.destroyBlock(at, false, scarab)
            scarab.eat(Scarab.Meal.MUSHROOM)
        }
    }

    private fun patchWorthReturningTo(): BlockPos? =
        scarab.lastMeal?.takeUnless { scarab.isNear(Vec3.atCenterOf(it), NEAR) }

    /**
     * The nearest thing it would eat that it can get at — torchflowers, and where it may disturb the world,
     * mushrooms — with **open air over it**, since it eats hovering, and nothing it has lately given up on.
     */
    private fun foodNear(level: ServerLevel, from: BlockPos): BlockPos? {
        outOfReach.values.removeIf { it <= level.gameTime }
        fun hasRoomOver(at: BlockPos) = level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty
        fun isWithinReach(at: BlockPos) = at !in outOfReach && hasRoomOver(at)
        return level.findBlocksInBoxByManhattanDistance(from, SEARCH_REACH, SEARCH_DEPTH)
            .filterState { state -> isFood(level, state) }
            .filterPos(::isWithinReach)
            .findFirst()
            .orElse(null)
    }

    private fun isFood(level: ServerLevel, state: BlockState): Boolean {
        val isMushroom = MUSHROOMS.any(state::`is`)
        return state.`is`(Blocks.TORCHFLOWER) || (isMushroom && scarab.mayDisturbTheWorld(level))
    }

    private companion object {
        val MUSHROOMS = listOf(
            Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK,
        )

        const val SEARCH_REACH = 10

        /** Up and down from where it flies, which is enough for a flower underneath and a cap overhead. */
        const val SEARCH_DEPTH = 6
        const val NEAR = 2.0
        const val HOVERS_OVER_A_FLOWER = 0.6
        const val EATS_WITHIN = 0.6
        const val EATING_TICKS = 40
        const val GIVES_UP_AFTER = 1200
        const val LOOK_AGAIN_AFTER = 100L

        /** How much closer counts as getting closer, and how long without it before it gives up — five seconds. */
        const val PROGRESS = 0.25
        const val NO_CLOSER_FOR = 100

        /** Three minutes, and then it may try the flower again: a bush may have been cleared. */
        const val LEFT_ALONE_FOR = 3600L
    }
}
