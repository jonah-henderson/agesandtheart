package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.client.panel.PanelCamera
import co.voik.agesandtheart.client.panel.PreviewPainter
import co.voik.agesandtheart.client.panel.PreviewWorld
import co.voik.agesandtheart.client.sendToServer
import co.voik.agesandtheart.imaging.Imaging
import co.voik.agesandtheart.imaging.PhotographChunk
import co.voik.agesandtheart.imaging.PhotographPiece
import co.voik.agesandtheart.imaging.PhotographRing
import co.voik.agesandtheart.imaging.PhotographWanted
import co.voik.agesandtheart.imaging.PicturePiece
import co.voik.agesandtheart.imaging.PictureSizes
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.renderpearl.api.GpuFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.core.BlockPos
import net.minecraft.util.Util
import java.util.UUID

/**
 * Drawing a set-down recorder's photograph for the server ([co.voik.agesandtheart.imaging.AutomaticShots]): the chunks
 * it streams are stood up as a level of their own, as a linking panel's are, drawn from the recorder's lens onto a
 * picture-sized target, lit by the flash as a handheld shot is, and read back once every section in view has been
 * meshed and faded in. The picture goes up as [PhotographPiece]s, and the level is let go.
 *
 * Drawn after the frame's post effects, where a handheld picture is taken, a few frames apart while the chunks come so
 * the meshing keeps pace without a world drawn twice every frame.
 */
object Darkroom {

    private class Exposure(val wanted: PhotographWanted, val world: PreviewWorld, val camera: PanelCamera) {
        val startedAt: Long = System.nanoTime()
        var lastArrivalAt: Long = startedAt
        var arrived = 0
        var framesSinceDrawn = 0
        var isRebuilt = false
        var settledSince: Long? = null
        var isDeveloping = false
    }

    private val WIDTH = PictureSizes.Size.FULL.width
    private val HEIGHT = PictureSizes.Size.FULL.height

    /** Every few frames while the chunks come and are meshed, so the player's own frame rate barely notices. */
    private const val FRAMES_BETWEEN_DRAWS = 3

    /** How long the stream may say nothing, or the whole exposure take, before what has come is taken as all. */
    private const val GONE_QUIET_NANOS = 8_000_000_000L
    private const val LONGEST_NANOS = 40_000_000_000L
    private const val FORGOTTEN_NANOS = 60_000_000_000L

    /** Past a section's fade-in, so nothing in the picture is still half fog. */
    private const val SETTLING_MILLIS = 250L
    private const val NANOS_A_MILLI = 1_000_000L

    private const val FLASHED = 1.0f

    private var exposure: Exposure? = null
    private val painter by lazy { PreviewPainter() }
    private var target: TextureTarget? = null

    fun wanted(wanted: PhotographWanted) {
        abandon()
        val description = PreviewWorld.Description(
            dimension = wanted.dimension,
            dimensionType = wanted.dimensionType,
            biomeZoomSeed = wanted.biomeZoomSeed,
            seaLevel = wanted.seaLevel,
            gameTime = wanted.gameTime,
            rain = wanted.rain,
            thunder = wanted.thunder,
            centre = PhotographRing.centreOf(wanted.eye),
            heldRadiusChunks = PhotographRing.HELD_RADIUS_CHUNKS,
            width = WIDTH,
            height = HEIGHT,
            isFlat = wanted.isFlat,
        )
        val world = PreviewWorld.standUp(description) ?: return
        val camera = PanelCamera(world.level, BlockPos.containing(wanted.eye))
        camera.placeAt(wanted.eye, wanted.yaw, wanted.pitch, WIDTH, HEIGHT)
        camera.tickProbe()
        exposure = Exposure(wanted, world, camera)
        painter.startOver()
        Imaging.LOG.info("Drawing a recorder's photograph from {} chunks of {}", wanted.chunksComing, wanted.dimension.identifier())
    }

    fun chunk(chunk: PhotographChunk) {
        val current = exposure ?: return
        if (current.wanted.id != chunk.id || current.isDeveloping) return
        if (current.world.accept(chunk.x, chunk.z, chunk.chunk, chunk.light)) {
            current.arrived++
            current.lastArrivalAt = System.nanoTime()
        }
    }

    /** Once a tick: the level's light and weather where the lens is, as a camera with an entity has them ticked. */
    fun tick() {
        exposure?.camera?.tickProbe()
    }

    /** Called once a frame, after its post effects and before its GUI. */
    @JvmStatic
    fun afterThePostEffects() {
        val current = exposure ?: return
        val now = System.nanoTime()
        // The server sketches a photograph that has not come in a minute, so a level still standing then is let go.
        if (!current.isDeveloping && now - current.startedAt > FORGOTTEN_NANOS) {
            abandon()
            return
        }
        if (current.isDeveloping || current.arrived == 0) return
        val isWhole = current.arrived >= current.wanted.chunksComing
        val hasGivenUpWaiting = now - current.lastArrivalAt > GONE_QUIET_NANOS || now - current.startedAt > LONGEST_NANOS
        val hasAllItWillGet = isWhole || hasGivenUpWaiting
        if (hasAllItWillGet && !current.isRebuilt) {
            current.world.rebuildAround(PhotographRing.centreOf(current.wanted.eye), PhotographRing.RADIUS_CHUNKS)
            current.isRebuilt = true
        }
        current.framesSinceDrawn++
        if (current.framesSinceDrawn < FRAMES_BETWEEN_DRAWS) return
        current.framesSinceDrawn = 0

        val minecraft = Minecraft.getInstance()
        val surface = target ?: TextureTarget("Recorder photograph", WIDTH, HEIGHT, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT).also { target = it }
        flashed(minecraft) { painter.paint(current.world, current.camera, surface, PhotographRing.SHOWN_RADIUS_CHUNKS, minecraft.deltaTracker) }

        val isMeshed = hasAllItWillGet && current.world.renderer.hasRenderedAllSections()
        if (!isMeshed) {
            current.settledSince = null
            return
        }
        val settledSince = current.settledSince ?: now.also { current.settledSince = it }
        val fadeMillis = Util.toMillis(minecraft.options.chunkSectionFadeInTime().get())
        if (now - settledSince < (fadeMillis + SETTLING_MILLIS) * NANOS_A_MILLI) return
        develop(current, surface)
    }

    /**
     * The plate's flash, as a handheld shot is lit: the player's own night vision is what Ephemeris lights an off-screen
     * level's lightmap with, so it is lent full for the draw and given back.
     */
    private fun flashed(minecraft: Minecraft, draw: () -> Unit) {
        val players = minecraft.gameRenderer.gameRenderState().lightmapRenderState
        val before = players.nightVisionEffectIntensity
        players.nightVisionEffectIntensity = FLASHED
        try {
            draw()
        } finally {
            players.nightVisionEffectIntensity = before
        }
    }

    private fun develop(current: Exposure, surface: TextureTarget) {
        current.isDeveloping = true
        val id = current.wanted.id
        Screenshot.takeScreenshot(surface) { picture ->
            Util.backgroundExecutor().execute { sendUp(id, picture) }
            if (exposure === current) abandon()
        }
    }

    private fun sendUp(id: UUID, picture: NativeImage) {
        val jpeg = picture.use(Capture::encoded)
        val pieces = jpeg.asList().chunked(PicturePiece.MOST_BYTES).map { it.toByteArray() }
        Minecraft.getInstance().execute {
            pieces.forEachIndexed { index, bytes -> sendToServer(PhotographPiece(id, index, pieces.size, bytes)) }
        }
        Imaging.LOG.info("A recorder's photograph went up: {} bytes in {} pieces", jpeg.size, pieces.size)
    }

    /** Lets go of the exposure under way. Its target is kept, since a picture may still be being read back from it. */
    private fun abandon() {
        exposure?.world?.close()
        exposure = null
    }

    /** Disconnected: nothing will be asked for again, and the target goes too. */
    fun forget() {
        abandon()
        target?.destroyBuffers()
        target = null
    }
}
