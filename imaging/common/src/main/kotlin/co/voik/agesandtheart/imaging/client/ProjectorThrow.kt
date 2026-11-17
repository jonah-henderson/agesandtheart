package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.Imaging
import co.voik.agesandtheart.imaging.SlideCarouselBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.level.Level
import net.minecraft.util.ARGB
import net.minecraft.world.level.block.BeaconBeamBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Where a projector throws its picture, worked out on the client from the blocks: the light directly behind the
 * carousel, the lens the first clear glass ahead of it, and the picture landing on whatever stands in the cone beyond.
 *
 * The picture's size follows the distances, as a real projector's does: a slide [SLIDE_WIDTH] wide, [toLens] blocks
 * behind the lens, is as wide as `SLIDE_WIDTH * d / toLens` at `d` blocks past it, and each further layer of glass makes
 * it [EACH_LAYER] times wider again. What it lands on is found by rays across the cone; each face they reach is cut into
 * pieces, and a piece is lit where a ray from the lens reaches it first, so a rough wall shows the picture whole only
 * from near the projector's line.
 */
class ProjectorThrow private constructor(
    /** The lit pieces: four corners a piece, x, y, z, u, v each, in the world. */
    val pieces: FloatArray,
    val assembly: Assembly,
) {

    /**
     * What the throw was worked out from, so it is only worked out again when one of them moves. [tint] is the lens's
     * colour, mixed from its stained layers as a beacon mixes its beam's, white where every layer is clear.
     */
    data class Assembly(val facing: Direction, val light: BlockState, val toLens: Int, val layers: Int, val tint: Int)

    companion object {

        val LENSES: TagKey<Block> = TagKey.create(Registries.BLOCK, Imaging.id("projector_lenses"))

        const val SLIDE_WIDTH = 0.5
        private const val PICTURE_ASPECT = 16.0 / 9.0
        const val EACH_LAYER = 1.5
        const val REACH = 32.0
        private const val FARTHEST_LENS = 8
        private const val MOST_LAYERS = 4
        private const val RAYS_ACROSS = 64
        private const val RAYS_DOWN = 36
        private const val CUTS = 4
        private const val OFF_THE_FACE = 0.004

        /** Whether a light stands directly behind [carousel], which is all the rays from its front need. */
        fun isLit(level: Level, carousel: BlockPos, state: BlockState): Boolean =
            level.getBlockState(carousel.relative(state.getValue(SlideCarouselBlock.FACING).opposite)).lightEmission > 0

        /** The assembly in front of [carousel], or null where there is no light behind it or no lens ahead. */
        fun assemblyAt(level: Level, carousel: BlockPos, state: BlockState): Assembly? {
            val facing = state.getValue(SlideCarouselBlock.FACING)
            val light = level.getBlockState(carousel.relative(facing.opposite))
            if (light.lightEmission <= 0) return null
            var toLens = 1
            while (toLens <= FARTHEST_LENS) {
                val ahead = level.getBlockState(carousel.relative(facing, toLens))
                if (isALens(ahead)) break
                if (!ahead.isAir) return null
                toLens++
            }
            if (toLens > FARTHEST_LENS) return null
            var layers = 1
            while (layers < MOST_LAYERS && isALens(level.getBlockState(carousel.relative(facing, toLens + layers)))) layers++
            val stained = (0..<layers).mapNotNull { (level.getBlockState(carousel.relative(facing, toLens + it)).block as? BeaconBeamBlock)?.color }
            val tint = stained.map { it.textureDiffuseColor and WHITE }.reduceOrNull { mixed, next -> ARGB.average(mixed, next) and WHITE } ?: WHITE
            return Assembly(facing, light, toLens, layers, tint)
        }

        /** Clear glass, or stained, which colours the throw as it colours a beacon's beam. */
        private fun isALens(state: BlockState): Boolean = state.`is`(LENSES) || state.block is BeaconBeamBlock

        private const val WHITE = 0xFFFFFF

        fun of(level: Level, carousel: BlockPos, assembly: Assembly): ProjectorThrow {
            val forward = Vec3.atLowerCornerOf(assembly.facing.unitVec3i)
            val right = Vec3.atLowerCornerOf(assembly.facing.clockWise.unitVec3i)
            val up = Vec3(0.0, 1.0, 0.0)
            // From the far side of the lens, so the glass itself is never what is lit.
            val lens = Vec3.atCenterOf(carousel.relative(assembly.facing, assembly.toLens + assembly.layers - 1)).add(forward.scale(0.5))
            val tanAcross = SLIDE_WIDTH / 2.0 / assembly.toLens * EACH_LAYER.pow(assembly.layers - 1)
            val tanDown = tanAcross / PICTURE_ASPECT
            val cone = Cone(lens, forward, right, up, tanAcross, tanDown)

            val faces = LinkedHashSet<Pair<BlockPos, Direction>>()
            for (row in 0..RAYS_DOWN) {
                for (column in 0..RAYS_ACROSS) {
                    val across = (2.0 * column / RAYS_ACROSS - 1.0) * tanAcross
                    val down = (1.0 - 2.0 * row / RAYS_DOWN) * tanDown
                    val direction = forward.add(right.scale(across)).add(up.scale(down)).normalize()
                    firstFace(level, lens, direction)?.let(faces::add)
                }
            }
            val pieces = ArrayList<Float>()
            faces.forEach { (block, face) -> cutAndLight(level, cone, block, face, pieces) }
            return ProjectorThrow(pieces.toFloatArray(), assembly)
        }

        private class Cone(val lens: Vec3, val forward: Vec3, val right: Vec3, val up: Vec3, val tanAcross: Double, val tanDown: Double) {
            /** Where [point] falls on the picture, nought to one across and down, or null where it is outside the cone. */
            fun pictureAt(point: Vec3): Pair<Double, Double>? {
                val from = point.subtract(lens)
                val depth = from.dot(forward)
                if (depth <= 0.0) return null
                val across = from.dot(right) / depth / tanAcross
                val down = from.dot(up) / depth / tanDown
                return (0.5 + across / 2.0) to (0.5 - down / 2.0)
            }
        }

        /** Each face cut [CUTS] by [CUTS], a piece kept where it is in the cone and nothing stands between it and the lens. */
        private fun cutAndLight(level: Level, cone: Cone, block: BlockPos, face: Direction, into: MutableList<Float>) {
            val normal = Vec3.atLowerCornerOf(face.unitVec3i)
            val centre = Vec3.atCenterOf(block).add(normal.scale(0.5 + OFF_THE_FACE))
            val (alongA, alongB) = edgesOf(face)
            for (a in 0..<CUTS) {
                for (b in 0..<CUTS) {
                    val corners = listOf(a to b, a + 1 to b, a + 1 to b + 1, a to b + 1).map { (i, j) ->
                        centre.add(alongA.scale(i.toDouble() / CUTS - 0.5)).add(alongB.scale(j.toDouble() / CUTS - 0.5))
                    }
                    val placed = corners.map { corner -> cone.pictureAt(corner)?.let { Corner(corner, it.first, it.second) } }
                    if (placed.any { it == null }) continue
                    val onThePicture = clippedToThePicture(placed.filterNotNull())
                    if (onThePicture.size < TRIANGLE) continue
                    val middle = onThePicture.map { it.at }.reduce(Vec3::add).scale(1.0 / onThePicture.size)
                    if (firstFace(level, cone.lens, middle.subtract(cone.lens).normalize()) != (block to face)) continue
                    emit(onThePicture, into)
                }
            }
        }

        private class Corner(val at: Vec3, val u: Double, val v: Double)

        /**
         * The part of a piece that falls on the picture: its outline cut against each edge of the picture in turn
         * (Sutherland and Hodgman), so nothing past an edge is drawn and the texture never repeats. A picture smaller
         * than the piece comes out whole.
         */
        private fun clippedToThePicture(piece: List<Corner>): List<Corner> {
            val edges: List<(Corner) -> Double> = listOf({ it.u }, { 1.0 - it.u }, { it.v }, { 1.0 - it.v })
            return edges.fold(piece) { outline, insideBy ->
                if (outline.isEmpty()) return@fold outline
                val kept = ArrayList<Corner>()
                for (index in outline.indices) {
                    val from = outline[index]
                    val to = outline[(index + 1) % outline.size]
                    val fromInside = insideBy(from) >= 0.0
                    val toInside = insideBy(to) >= 0.0
                    if (fromInside) kept += from
                    if (fromInside != toInside) {
                        val share = insideBy(from) / (insideBy(from) - insideBy(to))
                        kept += Corner(
                            from.at.add(to.at.subtract(from.at).scale(share)),
                            from.u + (to.u - from.u) * share,
                            from.v + (to.v - from.v) * share,
                        )
                    }
                }
                kept
            }
        }

        /** An outline as quads fanned from its first corner, the last corner of each doubled where it is a triangle. */
        private fun emit(outline: List<Corner>, into: MutableList<Float>) {
            fun add(corner: Corner) {
                into += corner.at.x.toFloat(); into += corner.at.y.toFloat(); into += corner.at.z.toFloat()
                into += corner.u.toFloat(); into += corner.v.toFloat()
            }
            var next = 1
            while (next + 1 < outline.size) {
                val last = if (next + 2 < outline.size) outline[next + 2] else outline[next + 1]
                add(outline[0]); add(outline[next]); add(outline[next + 1]); add(last)
                next += 2
            }
        }

        private const val TRIANGLE = 3

        /** Two edges of [face], wound so its pieces face outward. */
        private fun edgesOf(face: Direction): Pair<Vec3, Vec3> = when (face) {
            Direction.UP -> Vec3(0.0, 0.0, 1.0) to Vec3(1.0, 0.0, 0.0)
            Direction.DOWN -> Vec3(1.0, 0.0, 0.0) to Vec3(0.0, 0.0, 1.0)
            Direction.NORTH -> Vec3(0.0, 1.0, 0.0) to Vec3(1.0, 0.0, 0.0)
            Direction.SOUTH -> Vec3(1.0, 0.0, 0.0) to Vec3(0.0, 1.0, 0.0)
            Direction.WEST -> Vec3(0.0, 0.0, 1.0) to Vec3(0.0, 1.0, 0.0)
            Direction.EAST -> Vec3(0.0, 1.0, 0.0) to Vec3(0.0, 0.0, 1.0)
        }

        /** The first face a ray from [from] reaches on a block light cannot pass, within [REACH], stepping block by block. */
        private fun firstFace(level: Level, from: Vec3, direction: Vec3): Pair<BlockPos, Direction>? {
            var x = floor(from.x).toInt()
            var y = floor(from.y).toInt()
            var z = floor(from.z).toInt()
            val stepX = if (direction.x > 0) 1 else -1
            val stepY = if (direction.y > 0) 1 else -1
            val stepZ = if (direction.z > 0) 1 else -1
            val deltaX = if (direction.x == 0.0) Double.MAX_VALUE else abs(1.0 / direction.x)
            val deltaY = if (direction.y == 0.0) Double.MAX_VALUE else abs(1.0 / direction.y)
            val deltaZ = if (direction.z == 0.0) Double.MAX_VALUE else abs(1.0 / direction.z)
            var nextX = (if (stepX > 0) x + 1 - from.x else from.x - x) * deltaX
            var nextY = (if (stepY > 0) y + 1 - from.y else from.y - y) * deltaY
            var nextZ = (if (stepZ > 0) z + 1 - from.z else from.z - z) * deltaZ
            val position = BlockPos.MutableBlockPos()
            while (true) {
                val entered = when {
                    nextX <= nextY && nextX <= nextZ -> { x += stepX; val at = nextX; nextX += deltaX; at to (if (stepX > 0) Direction.WEST else Direction.EAST) }
                    nextY <= nextZ -> { y += stepY; val at = nextY; nextY += deltaY; at to (if (stepY > 0) Direction.DOWN else Direction.UP) }
                    else -> { z += stepZ; val at = nextZ; nextZ += deltaZ; at to (if (stepZ > 0) Direction.NORTH else Direction.SOUTH) }
                }
                if (entered.first > REACH) return null
                position.set(x, y, z)
                if (!level.isLoaded(position)) return null
                if (level.getBlockState(position).isSolidRender) return position.immutable() to entered.second
            }
        }
    }
}
