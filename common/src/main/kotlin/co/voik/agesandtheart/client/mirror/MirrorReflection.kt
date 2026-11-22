package co.voik.agesandtheart.client.mirror

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.client.panel.PanelCamera
import co.voik.agesandtheart.client.panel.PreviewPainter
import co.voik.agesandtheart.client.panel.PreviewWorld
import co.voik.agesandtheart.platform.Services
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import kotlin.math.abs

/** The plane a mirror's glass lies in: facing [facing], through [point]. Mirrors in one plane share a reflection. */
data class MirrorPlane(val facing: Direction, val point: Vec3) {
    private val normal = Vec3(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble())

    /** How far [position] stands in front of the glass; negative behind it. */
    fun distanceInFront(position: Vec3): Double = position.subtract(point).dot(normal)

    fun reflect(position: Vec3): Vec3 = position.subtract(normal.scale(2.0 * distanceInFront(position)))

    /** The same plane, as [facing] and how far along its axis it lies, so two mirrors in one plane compare equal. */
    val key: Pair<Direction, Long> get() = facing to Math.round(point.dot(normal) * KEY_PRECISION)

    private companion object {
        const val KEY_PRECISION = 1024.0
    }
}

/**
 * The reflection in the astrite mirrors nearest the player: the player's own level drawn a second time, from the
 * player's eye reflected through the glass, onto a small texture the mirrors then show.
 *
 * One plane a frame, the nearest one seen; every mirror in that plane shares it, and the others show their glass. The
 * level is drawn by a renderer of its own ([PreviewWorld.over]), so the player's is never disturbed, reaching only
 * [REACH_CHUNKS] out. What is behind the glass is cut away by an oblique near plane ([clipBehind]), which is what keeps
 * the wall a mirror hangs on out of its own reflection.
 *
 * In development the time each pass takes is logged every few seconds: this is an experiment in what a true
 * reflection costs.
 */
object MirrorReflection {
    private const val REACH_CHUNKS = 8
    private const val BLOCKS_PER_CHUNK = 16
    private const val TARGET_WIDTH = 512
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val REPORT_EVERY_NANOS = 5_000_000_000L

    private var world: PreviewWorld? = null
    private var camera: PanelCamera? = null
    private var target: TextureTarget? = null
    private val painter = PreviewPainter()
    private var probeTickedAt = Long.MIN_VALUE
    private var toldOfTheLoadedChunks = false

    private var nearestSeen: MirrorPlane? = null
    private var nearestSeenDistance = Double.MAX_VALUE

    /** The plane drawn this frame, and what its texture was drawn through: the reflected eye and view-projection. */
    var drawnPlane: MirrorPlane? = null
        private set
    private var drawnEye: Vec3 = Vec3.ZERO
    private val drawnViewProjection = Matrix4f()

    /** Whether the reflection is being drawn, when a mirror must not draw or look for one. */
    var isDrawing: Boolean = false
        private set

    private var passes = 0
    private var passNanos = 0L
    private var reportedAt = System.nanoTime()

    /** A mirror in [plane] was extracted for this frame, [distance] from the eye. */
    fun seen(plane: MirrorPlane, distance: Double) {
        if (isDrawing || distance >= nearestSeenDistance) return
        nearestSeen = plane
        nearestSeenDistance = distance
    }

    /** The reflection's colour texture, or null before one is drawn. */
    fun view(): GpuTextureView? = target?.colorTextureView

    /** Where [point] on the glass falls in the reflection's texture, as `u` and `v`. */
    fun textureCoordinatesOf(point: Vec3): Pair<Float, Float> {
        val relative = point.subtract(drawnEye)
        val clip = drawnViewProjection.transform(Vector4f(relative.x.toFloat(), relative.y.toFloat(), relative.z.toFloat(), 1f))
        return (clip.x / clip.w * HALF + HALF) to (clip.y / clip.w * HALF + HALF)
    }

    /** A block changed in [level]: the reflection's renderer is told too, since the level tells only its own. */
    fun blockChanged(level: ClientLevel, pos: BlockPos, oldState: BlockState, newState: BlockState) {
        val reflecting = world ?: return
        if (reflecting.level === level) reflecting.extractor.setBlockDirty(pos, oldState, newState)
    }

    /** Draws this frame's reflection, between the player's extraction and the drawing of the world. */
    fun drawFrame(delta: DeltaTracker) {
        val plane = nearestSeen
        nearestSeen = null
        nearestSeenDistance = Double.MAX_VALUE
        drawnPlane = null
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return forget()
        if (plane == null) return
        val playersCamera = minecraft.gameRenderer.mainCamera()
        val eye = playersCamera.position()
        if (plane.distanceInFront(eye) <= 0.0) return

        val started = System.nanoTime()
        val reflectingWorld = worldOver(level, minecraft)
        val reflectingTarget = targetFor(minecraft)
        val reflectedCamera = camera ?: PanelCamera(level, BlockPos.containing(eye)).also { camera = it }
        val reflectedEye = plane.reflect(eye)
        val (yaw, pitch) = reflectedTurn(plane.facing, playersCamera.yRot(), playersCamera.xRot())
        val reach = minOf(REACH_CHUNKS, minecraft.options.getEffectiveRenderDistance())
        reflectedCamera.placeAt(
            reflectedEye, yaw, pitch, reflectingTarget.width, reflectingTarget.height,
            fieldOfView = playersCamera.getFov(),
            cullingReach = (reach * BLOCKS_PER_CHUNK).toFloat(),
        )
        minecraft.player?.let(reflectedCamera::seesFromOutside)
        if (level.gameTime != probeTickedAt) {
            reflectedCamera.tickProbe()
            probeTickedAt = level.gameTime
        }

        isDrawing = true
        try {
            sharingTheChunkNews(level, minecraft) {
                painter.paint(
                    reflectingWorld, reflectedCamera, reflectingTarget, reach, delta,
                    advanceLight = {},
                    showsNames = false,
                    adjustProjection = { projection -> clipBehind(projection, plane, reflectedEye, reflectingWorld) },
                )
            }
        } finally {
            isDrawing = false
        }
        val state = reflectingWorld.renderState.cameraRenderState
        drawnViewProjection.set(state.projectionMatrix).mul(state.viewRotationMatrix)
        drawnEye = reflectedEye
        drawnPlane = plane
        report(System.nanoTime() - started)
    }

    /** Lets go of the renderer and its target, on leaving a level. */
    fun forget() {
        world?.close()
        world = null
        camera = null
        drawnPlane = null
        toldOfTheLoadedChunks = false
    }

    /**
     * Runs [render] with the reflection's renderer told which chunks have come and gone, and the player's left as
     * vanilla would leave it.
     *
     * **The chunk cache keeps one set of chunk-loading news**, and an extraction takes it and flips to a fresh one
     * (`flipUpdateTrackingSets`). The player's extraction has just taken this frame's, and its renderer reads it only
     * later, when the world is drawn; a renderer told nothing of chunks draws no ground at all. So the reflection's is
     * handed a copy of the same news, and every chunk already loaded the first time it looks, and afterwards the cache
     * is flipped back and the player's news, which the reflection's flip emptied, put back.
     */
    private fun <T> sharingTheChunkNews(level: ClientLevel, minecraft: Minecraft, render: () -> T): T {
        val cache = level.chunkSource
        val players = minecraft.gameRenderer.gameRenderState().levelRenderState.chunkLoadingRenderState
        val addedEmptySections = LongOpenHashSet(players.addedEmptySections)
        val removedEmptySections = LongOpenHashSet(players.removedEmptySections)
        val addedLoadedChunks = LongOpenHashSet(players.addedLoadedChunks)
        val removedLoadedChunks = LongOpenHashSet(players.removedLoadedChunks)
        cache.addedEmptySections().addAll(addedEmptySections)
        cache.removedEmptySections().addAll(removedEmptySections)
        cache.addedLoadedChunks().addAll(addedLoadedChunks)
        cache.removedLoadedChunks().addAll(removedLoadedChunks)
        if (!toldOfTheLoadedChunks) {
            tellOfTheLoadedChunks(level, minecraft)
            toldOfTheLoadedChunks = true
        }
        try {
            return render()
        } finally {
            val reflectionFlipped = cache.addedLoadedChunks() === players.addedLoadedChunks
            if (reflectionFlipped) cache.flipUpdateTrackingSets()
            players.addedEmptySections.addAll(addedEmptySections)
            players.removedEmptySections.addAll(removedEmptySections)
            players.addedLoadedChunks.addAll(addedLoadedChunks)
            players.removedLoadedChunks.addAll(removedLoadedChunks)
        }
    }

    /** Every chunk loaded within the render distance, and its empty sections, as news of their arriving. */
    private fun tellOfTheLoadedChunks(level: ClientLevel, minecraft: Minecraft) {
        val cache = level.chunkSource
        val centre = ChunkPos.containing(minecraft.gameRenderer.mainCamera().blockPosition())
        val radius = minecraft.options.getEffectiveRenderDistance()
        for (x in centre.x - radius..centre.x + radius) {
            for (z in centre.z - radius..centre.z + radius) {
                val chunk = cache.getChunk(x, z, false) ?: continue
                cache.addedLoadedChunks().add(ChunkPos.pack(x, z))
                for (index in chunk.sections.indices) {
                    if (!chunk.sections[index].hasOnlyAir()) continue
                    cache.addedEmptySections().add(SectionPos.asLong(x, level.getSectionYFromSectionIndex(index), z))
                }
            }
        }
    }

    private fun worldOver(level: ClientLevel, minecraft: Minecraft): PreviewWorld {
        val existing = world
        if (existing != null && existing.level === level) return existing
        forget()
        val window = minecraft.window
        return PreviewWorld.over(level, window.width, window.height).also { world = it }
    }

    private fun targetFor(minecraft: Minecraft): TextureTarget {
        val window = minecraft.window
        val height = (TARGET_WIDTH * window.height / window.width.coerceAtLeast(1)).coerceAtLeast(1)
        val existing = target
        if (existing != null && existing.width == TARGET_WIDTH && existing.height == height) return existing
        existing?.destroyBuffers()
        return TextureTarget("Ages astrite mirror", TARGET_WIDTH, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)
            .also { target = it }
    }

    /** The player's yaw and pitch reflected through glass facing [facing]: an entity's yaw is clockwise from south. */
    private fun reflectedTurn(facing: Direction, yaw: Float, pitch: Float): Pair<Float, Float> = when (facing.axis) {
        Direction.Axis.X -> -yaw to pitch
        Direction.Axis.Z -> (HALF_TURN - yaw) to pitch
        Direction.Axis.Y -> yaw to -pitch
    }

    /**
     * Moves the draw's near plane onto the glass, so nothing behind it is drawn (Lengyel's oblique near plane).
     *
     * The depth here runs backwards, near at one, so the near plane is where `z` meets `w`: the third row is set to the
     * fourth less the glass's plane, scaled so the far plane still passes through the frustum's furthest corner.
     */
    private fun clipBehind(projection: Matrix4f, plane: MirrorPlane, eye: Vec3, reflectingWorld: PreviewWorld) {
        val view = Matrix4f(reflectingWorld.renderState.cameraRenderState.viewRotationMatrix)
        val towardTheRoom = view.transformDirection(
            org.joml.Vector3f(plane.facing.stepX.toFloat(), plane.facing.stepY.toFloat(), plane.facing.stepZ.toFloat()),
        )
        val onTheGlass = plane.point.subtract(eye)
        val glassInView = view.transformPosition(
            org.joml.Vector3f(onTheGlass.x.toFloat(), onTheGlass.y.toFloat(), onTheGlass.z.toFloat()),
        )
        val glass = Vector4f(towardTheRoom.x, towardTheRoom.y, towardTheRoom.z, -towardTheRoom.dot(glassInView))
        if (glass.w >= 0f) return

        val inverse = Matrix4f(projection).invert()
        val zZeroToOne = RenderSystem.getDevice().deviceInfo.isZZeroToOne
        val farDepth = if (zZeroToOne) 0f else -1f
        val glassInClip = Vector4f(glass).mul(Matrix4f(inverse).transpose())
        val furthestCorner = inverse.transform(Vector4f(Math.signum(glassInClip.x), Math.signum(glassInClip.y), farDepth, 1f))
        val fourthRow = Vector4f(projection.m03(), projection.m13(), projection.m23(), projection.m33())
        val farReaches = if (zZeroToOne) 1f else 2f
        val scale = farReaches * fourthRow.dot(furthestCorner) / glass.dot(furthestCorner)
        if (abs(scale) < Float.MIN_VALUE || scale.isNaN()) return
        projection.m02(fourthRow.x - scale * glass.x)
        projection.m12(fourthRow.y - scale * glass.y)
        projection.m22(fourthRow.z - scale * glass.z)
        projection.m32(fourthRow.w - scale * glass.w)
    }

    private fun report(nanos: Long) {
        if (!Services.PLATFORM.isDevelopment) return
        passes++
        passNanos += nanos
        val now = System.nanoTime()
        if (now - reportedAt < REPORT_EVERY_NANOS) return
        Constants.LOG.info(
            "Astrite mirror: {} reflections in {}s, {} ms each on the CPU",
            passes, (now - reportedAt) / 1_000_000_000L, "%.2f".format(passNanos / passes / NANOS_PER_MILLI),
        )
        passes = 0
        passNanos = 0
        reportedAt = now
    }

    private const val HALF = 0.5f
    private const val HALF_TURN = 180f
}
