package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.client.panel.PanelCamera
import co.voik.agesandtheart.client.panel.PreviewPainter
import co.voik.agesandtheart.client.panel.PreviewWorld
import co.voik.agesandtheart.client.sendToServer
import co.voik.agesandtheart.imaging.Imaging
import co.voik.agesandtheart.imaging.PhotographChunk
import co.voik.agesandtheart.imaging.PhotographEntities
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
import net.minecraft.client.player.RemotePlayer
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntitySpawnRequest
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.core.BlockPos
import net.minecraft.util.Util
import java.util.UUID

/**
 * Drawing a set-down recorder's photograph for the server ([co.voik.agesandtheart.imaging.AutomaticShots]): the chunks
 * it streams are stood up as a level of their own, as a linking panel's are, drawn from the recorder's lens onto a
 * picture-sized target with what stood before the lens as the shutter went ([PhotographEntities]), lit by the flash as
 * a handheld shot is, and read back once every section in view has been meshed and faded in. The picture goes up as [PhotographPiece]s, and the level is let go.
 *
 * The chunks are taken in a few a frame as they come, and the photograph is drawn only once they all have, every few
 * frames until its sections are meshed, after the frame's post effects, where a handheld picture is taken.
 */
object Darkroom {

    private class Exposure(val wanted: PhotographWanted, val world: PreviewWorld, val camera: PanelCamera) {
        val startedAt: Long = System.nanoTime()
        var lastArrivalAt: Long = startedAt
        var arrived = 0

        /** Chunks come and not yet taken in, a few each frame. */
        val waiting = ArrayDeque<PhotographChunk>()
        var hasItsEntities = false
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
    private const val GONE_QUIET_NANOS = 15_000_000_000L
    private const val LONGEST_NANOS = 100_000_000_000L
    private const val FORGOTTEN_NANOS = 120_000_000_000L

    /** Past a section's fade-in, so nothing in the picture is still half fog. */
    private const val SETTLING_MILLIS = 250L
    private const val NANOS_A_MILLI = 1_000_000L

    private const val FLASHED = 1.0f

    /** How many chunks are taken into the photograph's level each frame: a few hundred in a few seconds. */
    private const val CHUNKS_A_FRAME = 8

    /** How vanilla's packet listener asks for an entity it is told of: as if loaded, with no checks. */
    private val SPAWNED_FROM_A_PACKET = EntitySpawnRequest(EntitySpawnReason.LOAD, true)

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
            heldRadiusChunks = PhotographRing.heldRadius(wanted.shownRadiusChunks),
            width = WIDTH,
            height = HEIGHT,
            isFlat = wanted.isFlat,
        )
        val world = PreviewWorld.standUp(description) ?: return
        val camera = PanelCamera(world.level, BlockPos.containing(wanted.eye))
        camera.placeAt(wanted.eye, wanted.yaw, wanted.pitch, WIDTH, HEIGHT, wanted.fieldOfView)
        camera.tickProbe()
        exposure = Exposure(wanted, world, camera)
        painter.startOver()
        Imaging.LOG.info("Drawing a recorder's photograph from {} chunks of {}", wanted.chunksComing, wanted.dimension.identifier())
    }

    /** A chunk for the photograph under way, kept to be taken in a few a frame ([takeSomeIn]). */
    fun chunk(chunk: PhotographChunk) {
        val current = exposure ?: return
        if (current.wanted.id != chunk.id || current.isDeveloping) return
        current.waiting.addLast(chunk)
        current.lastArrivalAt = System.nanoTime()
    }

    /**
     * A few waiting chunks into the photograph's level, their light run once for them all: each is unpacked and lit on
     * the render thread, and hundreds in one frame is a hitch the player sees.
     */
    private fun takeSomeIn(current: Exposure) {
        var tookAny = false
        repeat(CHUNKS_A_FRAME) {
            val chunk = current.waiting.removeFirstOrNull() ?: return@repeat
            if (current.world.accept(chunk.x, chunk.z, chunk.chunk, chunk.light, runsTheLight = false)) current.arrived++
            tookAny = true
        }
        if (tookAny) current.world.runTheLight()
    }

    /**
     * What stood before the lens, into the photograph's level as `ClientPacketListener` brings an entity into view: a
     * player by the profile this client already has for them, anything else by its type.
     */
    fun entities(snapshot: PhotographEntities) {
        val current = exposure ?: return
        if (current.wanted.id != snapshot.id || current.isDeveloping) return
        val level = current.world.level
        val connection = Minecraft.getInstance().connection
        for (arrival in snapshot.arrivals) {
            val entity = if (arrival.type == EntityTypes.PLAYER) {
                connection?.getPlayerInfo(arrival.uuid)?.let { RemotePlayer(level, it.profile) }
            } else {
                arrival.type.create(level, SPAWNED_FROM_A_PACKET)
            }
            entity ?: continue
            entity.recreateFromPacket(arrival)
            // Where it was a tick ago is where it is: culling sweeps an entity's box back toward its last tick's place,
            // and nothing ticks this level to set one, which left all but players culled against the world's origin.
            entity.xOld = entity.x
            entity.yOld = entity.y
            entity.zOld = entity.z
            level.addEntity(entity)
        }
        for (data in snapshot.data) level.getEntity(data.id())?.entityData?.assignValues(data.packedItems())
        for (worn in snapshot.equipment) {
            val wearer = level.getEntity(worn.entity) as? LivingEntity ?: continue
            worn.slots.forEach { wearer.setItemSlot(it.first, it.second) }
        }
        current.hasItsEntities = true
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
        // The server sketches a photograph that has not come in two minutes, so a level still standing then is let go.
        if (!current.isDeveloping && now - current.startedAt > FORGOTTEN_NANOS) {
            abandon()
            return
        }
        if (current.isDeveloping) return
        takeSomeIn(current)
        val isWhole = current.arrived >= current.wanted.chunksComing && current.hasItsEntities
        val hasGivenUpWaiting = now - current.lastArrivalAt > GONE_QUIET_NANOS || now - current.startedAt > LONGEST_NANOS
        val hasAllItWillGet = current.arrived > 0 && current.waiting.isEmpty() && (isWhole || hasGivenUpWaiting)
        // Nothing is drawn until everything has come and been lit, so each section is meshed once, from its final light.
        if (!hasAllItWillGet) return
        if (!current.isRebuilt) {
            current.world.rebuildAround(PhotographRing.centreOf(current.wanted.eye), PhotographRing.streamedRadius(current.wanted.shownRadiusChunks))
            current.isRebuilt = true
        }
        current.framesSinceDrawn++
        if (current.framesSinceDrawn < FRAMES_BETWEEN_DRAWS) return
        current.framesSinceDrawn = 0

        val minecraft = Minecraft.getInstance()
        val surface = target ?: TextureTarget("Recorder photograph", WIDTH, HEIGHT, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT).also { target = it }
        flashed(minecraft) { painter.paint(current.world, current.camera, surface, current.wanted.shownRadiusChunks, minecraft.deltaTracker, showsNames = false) }

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
