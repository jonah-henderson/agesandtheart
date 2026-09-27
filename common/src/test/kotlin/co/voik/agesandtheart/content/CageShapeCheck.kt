package co.voik.agesandtheart.content

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.structure.BoundingBox

/**
 * That a cage is found by its edges and corners: with its faces open or filled, whatever it holds, only by
 * a device set on it, and not where more frame runs on past it.
 *
 * Cages are built with the inside's lowest corner at the origin, so the outline runs from -1 to the size.
 */
class CageShapeCheck : FunSpec({

    test("the smallest cage is found from a device beside an edge") {
        val found = CageShape.around(cage(1, 1, 1)::isFrame, onAnEdge())
        check(found == outline(1, 1, 1)) { "a 1x1x1 cage was found as $found" }
    }

    test("a cage is found from beside any corner, including the far one") {
        val world = cage(3, 2, 4)
        val nearCorner = BlockPos(-2, -1, -1)
        val farCorner = BlockPos(3, 2, 4).above()
        check(CageShape.around(world::isFrame, nearCorner) == outline(3, 2, 4)) { "not found from the near corner" }
        check(CageShape.around(world::isFrame, farCorner) == outline(3, 2, 4)) { "not found from the far corner" }
    }

    test("a missing corner stops it being a cage, wherever the device is") {
        val world = cage(3, 2, 4).without(BlockPos(-1, -1, -1))
        val besideTheGap = CageShape.around(world::isFrame, onAnEdge())
        val farFromIt = CageShape.around(world::isFrame, BlockPos(3, 2, 4).above())
        check(besideTheGap == null && farFromIt == null) { "a cage missing a corner was found: $besideTheGap, $farFromIt" }
    }

    test("the largest cage is found and one larger is not") {
        val largest = CageShape.MAX_INSIDE
        val found = CageShape.around(cage(largest, largest, largest)::isFrame, onAnEdge())
        check(found == outline(largest, largest, largest)) { "a 21-block cage was found as $found" }
        val tooLong = CageShape.around(cage(largest + 1, 2, 2)::isFrame, onAnEdge())
        check(tooLong == null) { "a 22-long cage was accepted as $tooLong" }
    }

    test("a gap in an edge stops it being a cage") {
        val world = cage(3, 3, 3).without(BlockPos(1, 3, 3))
        val found = CageShape.around(world::isFrame, onAnEdge())
        check(found == null) { "a cage with a missing edge block was accepted as $found" }
    }

    test("a cage with every face filled in is found as itself, from the middle of a face") {
        val world = walledCage(5, 4, 6)
        val onTheFloor = BlockPos(2, -2, 3)
        val found = CageShape.around(world::isFrame, onTheFloor)
        check(found == outline(5, 4, 6)) { "a walled cage was found as $found" }
    }

    test("the largest walled cage is found as itself") {
        val largest = CageShape.MAX_INSIDE
        val found = CageShape.around(walledCage(largest, largest, largest)::isFrame, BlockPos(10, -2, 10))
        check(found == outline(largest, largest, largest)) { "the largest walled cage was found as $found" }
    }

    test("a cage stood on a floor of frame wider than itself is not found, the walk running on over the floor") {
        val floor = (-6..9).flatMap { x -> (-6..9).map { z -> BlockPos(x, -1, z) } }
        val world = cage(3, 3, 3).with(floor)
        val found = CageShape.around(world::isFrame, onAnEdge())
        check(found == null) { "a cage on a wide floor was found as $found" }
    }

    test("frame inside a cage does not hide it") {
        val pillar = (0..<4).map { y -> BlockPos(2, y, 2) }
        val world = cage(5, 4, 5).with(pillar)
        val found = CageShape.around(world::isFrame, onAnEdge())
        check(found == outline(5, 4, 5)) { "a cage with a pillar in it was found as $found" }
    }

    test("a device set on nothing, or on frame that is no cage's, finds nothing") {
        val world = cage(3, 3, 3)
        val inTheOpen = BlockPos(20, 20, 20)
        check(CageShape.around(world::isFrame, inTheOpen) == null) { "a device beside no frame found a cage" }
        val stray = BlockPos(10, 0, 10)
        val besideAStrayBlock = stray.above()
        val found = CageShape.around(world.with(listOf(stray))::isFrame, besideAStrayBlock)
        check(found == null) { "a device beside a lone frame block found $found" }
    }

    test("a device standing in an open face, against an edge, is set on it") {
        val world = cage(3, 3, 3)
        val inTheFloor = BlockPos(1, -1, 0)
        val found = CageShape.around(world::isFrame, inTheFloor)
        check(found == outline(3, 3, 3)) { "a device in the floor against an edge found $found" }
    }
}) {
    private class Blocks(private val frame: Set<BlockPos>) {
        fun isFrame(position: BlockPos): Boolean = position.immutable() in frame

        fun with(more: Collection<BlockPos>) = Blocks(frame + more)

        fun without(position: BlockPos) = Blocks(frame - position)
    }

    private companion object {
        /** Beside the bottom edge that runs along x at the near side, from outside the cage. */
        fun onAnEdge(): BlockPos = BlockPos(0, -2, -1)

        fun outline(width: Int, height: Int, depth: Int) = BoundingBox(-1, -1, -1, width, height, depth)

        /** The twelve edges of a cage and its eight corners. */
        fun cage(width: Int, height: Int, depth: Int): Blocks {
            val alongX = (0..<width).flatMap { x ->
                listOf(-1, height).flatMap { y -> listOf(-1, depth).map { z -> BlockPos(x, y, z) } }
            }
            val alongY = (0..<height).flatMap { y ->
                listOf(-1, width).flatMap { x -> listOf(-1, depth).map { z -> BlockPos(x, y, z) } }
            }
            val alongZ = (0..<depth).flatMap { z ->
                listOf(-1, width).flatMap { x -> listOf(-1, height).map { y -> BlockPos(x, y, z) } }
            }
            val corners = listOf(-1, width).flatMap { x ->
                listOf(-1, height).flatMap { y -> listOf(-1, depth).map { z -> BlockPos(x, y, z) } }
            }
            return Blocks((alongX + alongY + alongZ + corners).toSet())
        }

        /** Every block of a cage's outline, faces and corners included. */
        fun walledCage(width: Int, height: Int, depth: Int): Blocks {
            fun isOnTheOutline(x: Int, y: Int, z: Int) =
                x == -1 || x == width || y == -1 || y == height || z == -1 || z == depth
            val shell = (-1..width).flatMap { x ->
                (-1..height).flatMap { y -> (-1..depth).map { z -> BlockPos(x, y, z) } }
            }.filter { isOnTheOutline(it.x, it.y, it.z) }
            return Blocks(shell.toSet())
        }
    }
}
