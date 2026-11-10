package co.voik.agesandtheart.station

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where on a drying rack each input hangs, stated once for what draws it, what drips from it and what a click hangs
 * there. In the model's own pixels, from the rack's lower block, the model facing north: the first three spots down
 * the left of the rack as it is faced, top rung first, and the next three down its right, each just in front of and
 * under its rung.
 */
object DryingRackHangers {

    /** An item's middle, in the model's pixels: [across] the rack, [up] from its foot and [out] toward its back. */
    data class Spot(val across: Double, val up: Double, val out: Double)

    /** Where the input in [slot] hangs. */
    fun spotOf(slot: Int): Spot {
        val rung = RUNGS[slot % RUNGS.size]
        val across = ACROSS[slot / RUNGS.size]
        return Spot(across, rung.underside - HANG, rung.front - IN_FRONT)
    }

    /** Where the input in [slot] hangs in the world, the rack at [pos] facing [facing]. */
    fun inWorld(pos: BlockPos, facing: Direction, slot: Int): Vec3 {
        val spot = spotOf(slot)
        return toWorld(pos, facing, Vec3(spot.across, spot.up, spot.out))
    }

    /** [world] as a point in the model's pixels, the rack at [pos] facing [facing]. */
    fun toModel(pos: BlockPos, facing: Direction, world: Vec3): Vec3 {
        val local = world.subtract(Vec3.atLowerCornerOf(pos)).scale(UNITS_PER_BLOCK)
        return turned(local, -clockwiseTurnOf(facing))
    }

    /** As the blockstate turns the rack's model, which faces north unturned: a quarter clockwise from above a step. */
    fun clockwiseTurnOf(facing: Direction): Double = (facing.toYRot() + HALF_TURN) % FULL_TURN

    private fun toWorld(pos: BlockPos, facing: Direction, model: Vec3): Vec3 =
        Vec3.atLowerCornerOf(pos).add(turned(model, clockwiseTurnOf(facing)).scale(1.0 / UNITS_PER_BLOCK))

    /** [point] turned [degrees] clockwise, seen from above, about the middle of the block. */
    private fun turned(point: Vec3, degrees: Double): Vec3 {
        val angle = Math.toRadians(degrees)
        val x = point.x - MIDDLE
        val z = point.z - MIDDLE
        return Vec3(MIDDLE + x * cos(angle) - z * sin(angle), point.y, MIDDLE + x * sin(angle) + z * cos(angle))
    }

    /** A rung of Jonah's model: the height of its underside and how far back its front face stands. */
    private data class Rung(val underside: Double, val front: Double)

    /** Top, middle and bottom. */
    private val RUNGS = listOf(Rung(27.25, 7.0), Rung(18.0, 5.25), Rung(8.75, 3.75))

    /** The left and right of the rack, faced from the north: its left is east. */
    private val ACROSS = listOf(10.5, 5.5)

    /** From a rung's underside to an item's middle: an item hangs over the rung's lower edge, as if tied there. */
    private const val HANG = 2.0

    /** And this far in front of the rung, so it hangs clear of it. */
    private const val IN_FRONT = 0.6

    /** An item's half height, which drips fall from the bottom of. */
    const val HALF_AN_ITEM = 3.0

    const val UNITS_PER_BLOCK = 16.0
    private const val MIDDLE = 8.0
    private const val HALF_TURN = 180.0
    private const val FULL_TURN = 360.0
}
