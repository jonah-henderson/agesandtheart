package co.voik.agesandtheart.content

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * **The shape of a drifting body, checked without looking at one** — which is the whole reason
 * [OreClusters] is pure.
 *
 * What these guard is the three things a picture would not tell you quickly: that a body is in **one
 * piece** (a cluster in two halves reads as a bug and a collider in two halves *is* one), that the same
 * number always grows the same rock on every client, and that the tiers are visibly different sizes rather
 * than the same rock scaled.
 */
class OreClustersCheck : FunSpec({

    fun everyShape() = (0..<OreClusters.SHAPES).toList()

    /**
     * **Connected, six ways.** A cube weathered from the outside in should hold this by construction —
     * which is exactly why it is worth asserting: it is what notices the day the weathering is tuned into
     * eating a body's waist through.
     */
    test("every body is one piece") {
        for (tier in 0..<DriftingOre.MOST_TIERS) {
            for (shape in everyShape()) {
                val cells = OreClusters.of(shape, tier).map { it.at }.toSet()
                val reached = HashSet<BlockPos>()
                // **From a cell that is actually there.** Seeding at the origin was wrong: the origin is a
                // cell like any other and the weather may have taken it, and counting it anyway made a
                // whole body look like one piece too many.
                val queue = ArrayDeque(listOf(cells.first()))
                while (queue.isNotEmpty()) {
                    val next = queue.removeFirst()
                    if (!reached.add(next)) continue
                    Direction.entries.forEach { heading ->
                        val beside = next.relative(heading)
                        if (beside in cells) queue.addLast(beside)
                    }
                }
                check(reached.size == cells.size) {
                    "shape $shape at tier $tier is in pieces: ${reached.size} of ${cells.size} joined up"
                }
            }
        }
    }

    /** The same number is the same rock, or two clients draw different bodies at the same position. */
    test("a shape is the same rock every time it is asked for") {
        for (shape in listOf(0, 7, 63)) {
            for (tier in 0..<DriftingOre.MOST_TIERS) {
                check(OreClusters.of(shape, tier) == OreClusters.of(shape, tier)) {
                    "shape $shape at tier $tier grew differently the second time"
                }
            }
        }
    }

    /**
     * And different numbers are different rocks — **at the sizes that have room to be**.
     *
     * The smallest body is a two-cube with one cell weathered off it, so there are eight of it and there
     * can never be more; that is what a pebble is. The tiers with room are held to the full count.
     */
    test("different shapes are different rocks, where there is room for them") {
        for (tier in 1..<DriftingOre.MOST_TIERS) {
            val distinct = everyShape().map { OreClusters.of(it, tier).map { cell -> cell.at }.toSet() }.toSet()
            check(distinct.size > OreClusters.SHAPES / 2) {
                "tier $tier came out as only ${distinct.size} distinct rocks of ${OreClusters.SHAPES}"
            }
        }
    }

    /**
     * **A tier is a size you can see**, not a number in a tooltip: a body worth nine of the smallest has
     * to look worth nine of them or the climb reads as arbitrary.
     */
    test("each tier is plainly bigger than the one below") {
        for (tier in 1..<DriftingOre.MOST_TIERS) {
            val below = OreClusters.of(0, tier - 1).size
            val here = OreClusters.of(0, tier).size
            check(here > below) { "tier $tier has $here cells against tier ${tier - 1}'s $below" }
        }
    }

    /**
     * **The box holds the rock, and holds it centred** — the one thing about a body that is invisible in a
     * screenshot and lethal in play, since it is the same box a player stands on and an arrow is stopped by.
     *
     * A cell at offset `o` fills `o` to `o + 1`, so both the near and the far face have to be inside half
     * the span. Reading only the offsets would have made the box half a block short on every positive side.
     */
    test("every cell sits inside the span the box is built from") {
        for (tier in 0..<DriftingOre.MOST_TIERS) {
            val half = OreClusters.spanOf(tier) / 2.0
            for (shape in everyShape()) {
                for (cell in OreClusters.of(shape, tier)) {
                    val faces = listOf(cell.at.x, cell.at.y, cell.at.z).flatMap { listOf(it, it + 1) }
                    check(faces.all { it >= -half && it <= half }) {
                        "shape $shape at tier $tier has a cell at ${cell.at} outside a span of ${half * 2}"
                    }
                }
            }
        }
    }

    /**
     * **The collider is the cluster, cell for cell** — filled where the rock is and empty where it is not.
     *
     * These are two descriptions of one body written in different units, and they have to agree exactly:
     * a collider that is bigger is standing on air and arrows stopping short of the stone, which is what
     * the cube was doing; one that is smaller is falling through a rock you can see. Neither shows up
     * anywhere but in play, and both read as the physics being broken rather than as a number being wrong.
     */
    test("the collider fills the cluster and nothing but the cluster") {
        for (tier in 0..<DriftingOre.MOST_TIERS) {
            for (shape in listOf(0, 17, 63)) {
                val cells = OreClusters.of(shape, tier).map { it.at }.toSet()
                val boxes = OreClusters.shapeOf(shape, tier).toAabbs()
                fun filled(at: BlockPos) = boxes.any { it.contains(at.x + HALF, at.y + HALF, at.z + HALF) }
                val side = OreClusters.sideOf(tier)
                val across = -(side / 2)..<(-(side / 2) + side)
                for (x in across) for (y in across) for (z in across) {
                    val at = BlockPos(x, y, z)
                    check(filled(at) == (at in cells)) {
                        val complaint = if (at in cells) "left a hole where the rock is" else "fills a gap"
                        "shape $shape at tier $tier $complaint, at $at"
                    }
                }
            }
        }
    }

    /** Bounded, because a renderer caches by shape and unbounded shapes would rebuild every mesh a frame. */
    test("the shapes are bounded and what is drawn is few enough to draw") {
        val largest = OreClusters.facesOf(0, DriftingOre.MOST_TIERS - 1).size
        check(largest <= MOST_CELLS_WORTH_DRAWING) {
            "the largest body draws $largest cells, which is a lot to submit per body per frame"
        }
    }
}) {
    private companion object {
        /**
         * Each drawn cell is a block model submitted per body per frame. A six-cube is a *platform* rather
         * than a pebble, which is the point of it, so this is set where the top tier actually lands and is
         * a number to watch in a bench rather than a limit anybody chose.
         */
        const val MOST_CELLS_WORTH_DRAWING = 140

        /** The middle of a cell, which is what a shape is asked about. */
        const val HALF = 0.5
    }
}
