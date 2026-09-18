package co.voik.agesandtheart.portal

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * That a phasmium frame is found by the nether portal's rules: the sizes, the missing corners, and what a
 * receptacle may count as "on the frame".
 *
 * Frames are built in the X plane, rising from y 0, with the opening's bottom-left at x 0 and running west,
 * which is the direction vanilla's search reads as rightward for that plane.
 */
class LinkingPortalShapeCheck : FunSpec({

    test("the smallest nether portal opening is found, without its corners") {
        val world = frame(width = 2, height = 3)
        val portal = LinkingPortalShape.find(world::footing, FLOOR, inside(1, 1), Direction.Axis.X)
        check(portal != null) { "a 2x3 opening in a cornerless frame was not found" }
        check(portal.width == 2 && portal.height == 3) { "found ${portal.width}x${portal.height}, not 2x3" }
        check(portal.isUnlit) { "an empty frame was found already lit" }
    }

    test("an opening one wide is refused") {
        val world = frame(width = 1, height = 3)
        check(LinkingPortalShape.find(world::footing, FLOOR, inside(0, 1), Direction.Axis.X) == null) {
            "a 1-wide opening was accepted"
        }
    }

    test("an opening two high is refused") {
        val world = frame(width = 2, height = 2)
        check(LinkingPortalShape.find(world::footing, FLOOR, inside(0, 1), Direction.Axis.X) == null) {
            "a 2-high opening was accepted"
        }
    }

    test("the largest opening is found and one larger is not") {
        val largest = frame(LinkingPortalShape.MAX_WIDTH, LinkingPortalShape.MAX_HEIGHT)
        check(LinkingPortalShape.find(largest::footing, FLOOR, inside(0, 1), Direction.Axis.X) != null) {
            "a 21x21 opening was refused"
        }
        val tooWide = frame(LinkingPortalShape.MAX_WIDTH + 1, height = 3)
        check(LinkingPortalShape.find(tooWide::footing, FLOOR, inside(0, 1), Direction.Axis.X) == null) {
            "a 22-wide opening was accepted"
        }
    }

    test("a block in the opening stops the frame being a portal") {
        val world = frame(width = 3, height = 4).with(inside(1, 2), PortalFooting.BLOCKED)
        check(LinkingPortalShape.find(world::footing, FLOOR, inside(0, 0), Direction.Axis.X) == null) {
            "an obstructed opening was accepted"
        }
    }

    test("a gap in the frame stops it being a portal") {
        val world = frame(width = 3, height = 4).with(inside(-1, 2), PortalFooting.OPEN)
        check(LinkingPortalShape.find(world::footing, FLOOR, inside(0, 0), Direction.Axis.X) == null) {
            "a frame with a missing side block was accepted"
        }
    }

    test("the frame is found from anywhere in the opening, and a lit one counts as lit") {
        val lit = frame(width = 3, height = 4).fillOpening(PortalFooting.LIT)
        val fromTheTop = LinkingPortalShape.find(lit::footing, FLOOR, inside(2, 3), Direction.Axis.X)
        check(fromTheTop?.bottomLeft == inside(0, 0)) { "searched from the top corner, found ${fromTheTop?.bottomLeft}" }
        check(fromTheTop != null && fromTheTop.isLit) { "a full opening of portal was not seen as lit" }
    }

    test("only the frame's own blocks frame it") {
        val portal = LinkingPortalShape.find(frame(2, 3)::footing, FLOOR, inside(0, 0), Direction.Axis.X)
        check(portal != null) { "no portal to ask" }
        val onTheSide = inside(-1, 1)
        val onTheTop = inside(1, 3)
        val aCorner = inside(-1, -1)
        val inFront = inside(0, 1).north()
        check(portal.isFramedBy(onTheSide) && portal.isFramedBy(onTheTop)) { "a side or top block did not count" }
        check(!portal.isFramedBy(aCorner)) { "a corner counted, which the rules never ask for" }
        check(!portal.isFramedBy(inFront)) { "a block in front of the opening counted as its frame" }
    }
}) {
    private class Blocks(private val footings: Map<BlockPos, PortalFooting>) {
        fun footing(position: BlockPos): PortalFooting = footings[position] ?: PortalFooting.OPEN

        fun with(position: BlockPos, footing: PortalFooting) = Blocks(footings + (position to footing))

        fun fillOpening(footing: PortalFooting): Blocks =
            Blocks(footings + footings.keys.filter { footings[it] == PortalFooting.OPEN }.associateWith { footing })
    }

    private companion object {
        const val FLOOR = -64

        /** A position [across] the opening from its bottom-left and [up] from its floor. */
        fun inside(across: Int, up: Int): BlockPos = BlockPos(-across, up, 0)

        /** A frame round an opening of [width] by [height], standing in air. */
        fun frame(width: Int, height: Int): Blocks {
            val sides = (0..<height).flatMap { up -> listOf(inside(-1, up), inside(width, up)) }
            val topAndBottom = (0..<width).flatMap { across -> listOf(inside(across, -1), inside(across, height)) }
            val opening = (0..<height).flatMap { up -> (0..<width).map { across -> inside(across, up) } }
            return Blocks(
                (sides + topAndBottom).associateWith { PortalFooting.FRAME } +
                    opening.associateWith { PortalFooting.OPEN },
            )
        }
    }
}
