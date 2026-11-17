package co.voik.agesandtheart.imaging

import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Util
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.Vec3
import java.awt.image.BufferedImage
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The server's own drawing of a picture: one ray a pixel from where it was taken, coloured as a map colours what it
 * hits (grass and leaves tinted for their biome, [BiomeTints]), shaded by the face it hits and faded toward the sky with
 * distance. What can be walked through and is not liquid, grass and flowers among it, is looked past, as it mostly is in
 * the photograph. It is what everyone but the photographer
 * sees where pictures are not shared, and what a picture develops from when its photograph never arrives.
 *
 * Drawn a share of rays a tick, so a picture never holds the server up, and only through chunks already loaded: a ray
 * that reaches an unloaded one is sky, since loading chunks for a picture is not worth what it costs.
 */
object Sketches {

    const val WIDTH = 192
    const val HEIGHT = 108

    /** The field of view a picture is drawn at when its photograph never said. */
    const val ORDINARY_FIELD_OF_VIEW = 70.0f

    private const val RAYS_A_TICK = 2_000
    private const val REACH = 160.0
    private const val ASPECT = 16.0 / 9.0

    private class Job(val server: MinecraftServer, val id: UUID, val about: PictureStore.About) {
        val pixels = IntArray(WIDTH * HEIGHT)
        var next = 0
    }

    private val jobs = ArrayDeque<Job>()

    fun draw(server: MinecraftServer, id: UUID, about: PictureStore.About) {
        jobs.addLast(Job(server, id, about))
    }

    fun tick(server: MinecraftServer) {
        var budget = RAYS_A_TICK
        while (budget > 0) {
            val job = jobs.firstOrNull() ?: return
            val level = server.getLevel(job.about.pose.dimension)
            if (level == null) {
                jobs.removeFirst()
                continue
            }
            val view = View(job.about)
            while (budget > 0 && job.next < job.pixels.size) {
                job.pixels[job.next] = colourAt(level, view, job.next % WIDTH, job.next / WIDTH)
                job.next++
                budget--
            }
            if (job.next == job.pixels.size) {
                jobs.removeFirst()
                val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, WIDTH, HEIGHT, job.pixels, 0, WIDTH) }
                Util.ioPool().execute { PictureStore.keepSketch(job.server, job.id, image, job.about) }
            }
        }
    }

    /** Where the picture looks from and which ways are forward, right and up in it. */
    private class View(about: PictureStore.About) {
        val eye = Vec3(about.pose.eyeX, about.pose.eyeY, about.pose.eyeZ)
        val forward: Vec3 = Vec3.directionFromRotation(about.pose.pitch, about.pose.yaw)
        val right: Vec3 = forward.cross(Vec3(0.0, 1.0, 0.0)).normalize()
        val up: Vec3 = right.cross(forward)
        val halfHeight = tan(Math.toRadians(about.fieldOfView / 2.0))
        val halfWidth = halfHeight * ASPECT
    }

    private fun colourAt(level: ServerLevel, view: View, column: Int, row: Int): Int {
        val across = (2.0 * (column + 0.5) / WIDTH - 1.0) * view.halfWidth
        val down = (1.0 - 2.0 * (row + 0.5) / HEIGHT) * view.halfHeight
        val direction = view.forward.add(view.right.scale(across)).add(view.up.scale(down)).normalize()
        return hit(level, view.eye, direction) ?: skyColour(level, direction)
    }

    /**
     * The colour of the first block along the ray with a map colour, stepping block by block (Amanatides and Woo), or
     * null where it reaches the sky or an unloaded chunk.
     */
    private fun hit(level: ServerLevel, from: Vec3, direction: Vec3): Int? {
        var x = floor(from.x).toInt()
        var y = floor(from.y).toInt()
        var z = floor(from.z).toInt()
        val stepX = if (direction.x > 0) 1 else -1
        val stepY = if (direction.y > 0) 1 else -1
        val stepZ = if (direction.z > 0) 1 else -1
        val deltaX = if (direction.x == 0.0) Double.MAX_VALUE else abs(1.0 / direction.x)
        val deltaY = if (direction.y == 0.0) Double.MAX_VALUE else abs(1.0 / direction.y)
        val deltaZ = if (direction.z == 0.0) Double.MAX_VALUE else abs(1.0 / direction.z)
        var nextX = boundary(from.x, x, stepX) * deltaX
        var nextY = boundary(from.y, y, stepY) * deltaY
        var nextZ = boundary(from.z, z, stepZ) * deltaZ
        var travelled = 0.0
        var lastAxis = 1
        val position = BlockPos.MutableBlockPos()
        while (travelled < REACH) {
            if (y < level.minY || y > level.maxY) return null
            val chunk = level.chunkSource.getChunkNow(x shr CHUNK_BITS, z shr CHUNK_BITS) ?: return null
            position.set(x, y, z)
            val state = chunk.getBlockState(position)
            val isSeen = !state.isAir && !isLookedPast(state, level, position)
            if (isSeen) {
                val colour = colourOf(state, level, position)
                if (colour != null) return shaded(level, colour, lastAxis, direction, travelled)
            }
            when {
                nextX <= nextY && nextX <= nextZ -> { x += stepX; travelled = nextX; nextX += deltaX; lastAxis = 0 }
                nextY <= nextZ -> { y += stepY; travelled = nextY; nextY += deltaY; lastAxis = 1 }
                else -> { z += stepZ; travelled = nextZ; nextZ += deltaZ; lastAxis = 2 }
            }
        }
        return null
    }

    private fun isLookedPast(state: BlockState, level: ServerLevel, position: BlockPos): Boolean {
        val canBeWalkedThrough = state.getCollisionShape(level, position).isEmpty
        val isLiquid = !state.fluidState.isEmpty
        return canBeWalkedThrough && !isLiquid && state.block !is SnowLayerBlock
    }

    /** The block's map colour, its biome's grass or leaves standing in for the map's one green; null where it has none. */
    private fun colourOf(state: BlockState, level: ServerLevel, position: BlockPos): Int? {
        val colour = state.getMapColor(level, position)
        return when {
            colour == MapColor.NONE -> null
            colour == MapColor.GRASS -> scaled(BiomeTints.grassOf(level.getBiome(position).value()), GRASS_TEXTURE)
            state.block is LeavesBlock && colour == MapColor.PLANT -> scaled(leavesOf(state, level, position), LEAVES_TEXTURE)
            else -> colour.col
        }
    }

    /** Spruce and birch keep their own leaves whatever the biome, as they do in the game. */
    private fun leavesOf(state: BlockState, level: ServerLevel, position: BlockPos): Int = when {
        state.`is`(Blocks.SPRUCE_LEAVES) -> SPRUCE_LEAVES
        state.`is`(Blocks.BIRCH_LEAVES) -> BIRCH_LEAVES
        else -> BiomeTints.leavesOf(level.getBiome(position).value())
    }

    private fun boundary(from: Double, cell: Int, step: Int): Double = if (step > 0) cell + 1 - from else from - cell

    /** Lit from above as a map is, the far away faded into the sky. */
    private fun shaded(level: ServerLevel, colour: Int, axis: Int, direction: Vec3, distance: Double): Int {
        val light = when (axis) {
            1 -> if (direction.y < 0) TOP_LIGHT else BOTTOM_LIGHT
            0 -> EAST_WEST_LIGHT
            else -> NORTH_SOUTH_LIGHT
        }
        val haze = (distance / REACH).let { it * it }
        return blend(scaled(colour, light), skyColour(level, direction), haze)
    }

    private fun skyColour(level: ServerLevel, direction: Vec3): Int {
        if (!level.dimensionType().hasSkyLight()) return if (level.dimensionType().hasCeiling()) NETHER else VOID
        val height = direction.y.coerceIn(0.0, 1.0)
        return blend(HORIZON, ZENITH, sqrt(height))
    }

    private fun scaled(colour: Int, by: Double): Int {
        val red = ((colour shr 16 and 0xFF) * by).toInt().coerceIn(0, 0xFF)
        val green = ((colour shr 8 and 0xFF) * by).toInt().coerceIn(0, 0xFF)
        val blue = ((colour and 0xFF) * by).toInt().coerceIn(0, 0xFF)
        return (red shl 16) or (green shl 8) or blue
    }

    private fun blend(from: Int, to: Int, toward: Double): Int {
        fun channel(shift: Int) = ((from shr shift and 0xFF) * (1 - toward) + (to shr shift and 0xFF) * toward).toInt()
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private const val CHUNK_BITS = 4

    /** How much darker than its tint the grass and leaf textures draw, on average. */
    private const val GRASS_TEXTURE = 0.75
    private const val LEAVES_TEXTURE = 0.62
    private const val SPRUCE_LEAVES = 0x619961
    private const val BIRCH_LEAVES = 0x80A755
    private const val TOP_LIGHT = 1.0
    private const val NORTH_SOUTH_LIGHT = 0.86
    private const val EAST_WEST_LIGHT = 0.78
    private const val BOTTOM_LIGHT = 0.6
    private const val HORIZON = 0xC4D6F5
    private const val ZENITH = 0x6E9CF0
    private const val NETHER = 0x3A0E0A
    private const val VOID = 0x0C0C14
}
