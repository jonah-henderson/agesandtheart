package co.voik.agesandtheart.client

import java.util.WeakHashMap
import net.minecraft.util.Mth
import co.voik.agesandtheart.ChunkBlockIndex
import co.voik.agesandtheart.worldgen.fissure.StarFissureBlock
import co.voik.ephemeris.client.OffscreenLevelRender
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.phys.Vec3

/**
 * Every star fissure in sight, drawn as vanilla draws an end portal's starfield, in one submission — as [WoundField]
 * draws the wounds, and for the same reason.
 *
 * A fissure used to carry a block entity so vanilla's end-portal renderer had something to hang on. Collapse is meant
 * to spread, and its tears are fissures, so the count climbs for as long as an Age comes apart; and every section
 * rebuilt near them walked every block entity in the chunks around it, so each rebuild cost more the further the
 * collapse had gone. What is left scales with what is in sight.
 *
 * **A face two fissures share is not drawn.** It can only be seen from inside a fissure, which is falling through
 * one, and then [StarFissureVeil] draws the field and none of these are drawn at all.
 *
 * **Which faces show is worked out a chunk at a time and kept**, and only worked out again when a fissure in the chunk
 * or beside it comes or goes: a collapse's floor is thousands of them, and asking every one's six neighbours every
 * frame was most of what this cost.
 */
object FissureField {

    private val index = ChunkBlockIndex.matching { state -> state.block is StarFissureBlock }

    /** Each chunk's showing faces, four ints apiece — x, y, z and the face — by level and packed chunk. */
    private val faces = WeakHashMap<Level, HashMap<Long, IntArray>>()

    fun stocked(level: Level, chunk: ChunkAccess) {
        index.stocked(level, chunk)
        forgetAround(level, chunk.pos.x, chunk.pos.z)
    }

    fun emptied(level: Level, at: ChunkPos) {
        index.emptied(level, at)
        forgetAround(level, at.x, at.z)
    }

    /** A block changing where this client can see it, which is how a fissure torn after its chunk arrived is heard of. */
    fun noticed(level: Level, at: BlockPos, was: BlockState, now: BlockState) {
        val wasOne = was.block is StarFissureBlock
        val isOne = now.block is StarFissureBlock
        if (wasOne == isOne) return
        if (isOne) index.arrived(level, at, Unit) else index.gone(level, at)
        forgetAround(level, at.x shr CHUNK_BITS, at.z shr CHUNK_BITS)
    }

    /** Everything, for a client leaving a server outright. */
    fun forget() {
        index.forget()
        faces.clear()
    }

    /** A chunk's faces, and its four neighbours', whose edges it borders, to be worked out again when next drawn. */
    private fun forgetAround(level: Level, chunkX: Int, chunkZ: Int) {
        val here = faces[level] ?: return
        here.remove(ChunkPos.pack(chunkX, chunkZ))
        here.remove(ChunkPos.pack(chunkX + 1, chunkZ))
        here.remove(ChunkPos.pack(chunkX - 1, chunkZ))
        here.remove(ChunkPos.pack(chunkX, chunkZ + 1))
        here.remove(ChunkPos.pack(chunkX, chunkZ - 1))
    }

    /**
     * Draw them, at the seam where the block entities used to be drawn, camera-relative as [WoundField.submit] is: the
     * fissures of the level being drawn, a linking panel's as well as the player's own — and none of the player's own
     * while they are falling through a tear, as the block-entity renderer declined them.
     */
    fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, camera: Vec3) {
        val level = OffscreenLevelRender.levelBeingDrawn() ?: return
        val isFallingThroughOne = level === Minecraft.getInstance().level && StarFissureVeil.hidingTheTears()
        if (isFallingThroughOne) return
        val inRange = showingFacesNear(level, camera)
        if (inRange.isEmpty()) return
        collector.submitCustomGeometry(poseStack, RenderTypes.endPortal()) { pose, buffer ->
            for (chunk in inRange) {
                var at = 0
                while (at < chunk.size) {
                    faceOf(pose, buffer, chunk[at], chunk[at + 1], chunk[at + 2], FACES[chunk[at + 3]], camera)
                    at += INTS_A_FACE
                }
            }
        }
    }

    /** The showing faces of every chunk within the render distance of [camera] that holds a fissure, worked out as needed. */
    private fun showingFacesNear(level: Level, camera: Vec3): List<IntArray> {
        val known = faces.getOrPut(level) { HashMap() }
        val chunkX = Mth.floor(camera.x) shr CHUNK_BITS
        val chunkZ = Mth.floor(camera.z) shr CHUNK_BITS
        val reach = Minecraft.getInstance().options.effectiveRenderDistance
        val found = ArrayList<IntArray>()
        for (x in chunkX - reach..chunkX + reach) {
            for (z in chunkZ - reach..chunkZ + reach) {
                val chunk = ChunkPos(x, z)
                if (index.countIn(level, chunk) == 0) continue
                val showing = known.getOrPut(ChunkPos.pack(x, z)) { showingFacesIn(level, chunk) }
                if (showing.isNotEmpty()) found += showing
            }
        }
        return found
    }

    /** The faces of [chunk]'s fissures that border something other than a fissure. */
    private fun showingFacesIn(level: Level, chunk: ChunkPos): IntArray {
        val showing = ArrayList<Int>()
        for (fissure in index.valuesIn(level, chunk).keys) {
            for (face in FACES) {
                val beside = fissure.relative(face)
                val besideChunk = ChunkPos(beside.x shr CHUNK_BITS, beside.z shr CHUNK_BITS)
                if (index.valuesIn(level, besideChunk).containsKey(beside)) continue
                showing += fissure.x
                showing += fissure.y
                showing += fissure.z
                showing += face.ordinal
            }
        }
        return showing.toIntArray()
    }

    private fun faceOf(pose: PoseStack.Pose, buffer: VertexConsumer, x: Int, y: Int, z: Int, face: Direction, camera: Vec3) {
        val fromX = (x - camera.x).toFloat()
        val fromY = (y - camera.y).toFloat()
        val fromZ = (z - camera.z).toFloat()
        for ((cornerX, cornerY, cornerZ) in CORNERS.getValue(face)) buffer.addVertex(pose, fromX + cornerX, fromY + cornerY, fromZ + cornerZ)
    }

    private const val CHUNK_BITS = 4
    private const val INTS_A_FACE = 4

    /** Every face, indexed by `Direction.ordinal` as the packed faces store it. */
    private val FACES: Array<Direction> = Direction.entries.sortedBy { it.ordinal }.toTypedArray()

    /** The unit cube, each face wound so it faces outward where back faces are culled. */
    private val CORNERS: Map<Direction, List<Triple<Float, Float, Float>>> = mapOf(
        Direction.DOWN to listOf(t(1, 0, 0), t(1, 0, 1), t(0, 0, 1), t(0, 0, 0)),
        Direction.UP to listOf(t(0, 1, 1), t(1, 1, 1), t(1, 1, 0), t(0, 1, 0)),
        Direction.NORTH to listOf(t(0, 1, 0), t(1, 1, 0), t(1, 0, 0), t(0, 0, 0)),
        Direction.SOUTH to listOf(t(1, 0, 1), t(1, 1, 1), t(0, 1, 1), t(0, 0, 1)),
        Direction.WEST to listOf(t(0, 0, 1), t(0, 1, 1), t(0, 1, 0), t(0, 0, 0)),
        Direction.EAST to listOf(t(1, 1, 0), t(1, 1, 1), t(1, 0, 1), t(1, 0, 0)),
    )

    private fun t(x: Int, y: Int, z: Int) = Triple(x.toFloat(), y.toFloat(), z.toFloat())
}
