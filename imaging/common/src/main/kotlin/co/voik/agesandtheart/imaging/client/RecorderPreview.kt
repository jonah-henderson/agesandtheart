package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.AimTheRecorder
import co.voik.agesandtheart.imaging.ImageRecorderBlock
import co.voik.agesandtheart.imaging.ImageRecorderBlockEntity
import co.voik.agesandtheart.imaging.ImagingContent
import co.voik.agesandtheart.imaging.Sketches
import co.voik.agesandtheart.imaging.TurnTheRecorder
import co.voik.agesandtheart.imaging.ZoomTheRecorder
import co.voik.agesandtheart.client.sendToServer
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Marker
import net.minecraft.world.entity.Pose
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.pow

/**
 * Looking through a set-down image recorder: the view moved to its lens and pointed its way, at the field of view it
 * takes pictures at, through the viewfinder's look. The player stays where they stand, and since the view is no longer
 * theirs, vanilla moves them no further; sneaking stops. On the ground the strafe keys turn the recorder a sixteenth at
 * a time. On a tripod the mouse aims it as it would the player's own view, the player turned to the recorder's aim as
 * they start so the two move together, and the lens swings about the tripod's head as it turns.
 */
object RecorderPreview {

    /** Further than this from the recorder, and the view comes back. */
    private const val REACH = 8.0

    /** How far the view turns toward the recorder's way each tick, so a turn of a sixteenth takes three. */
    private const val TURNING_A_TICK = 7.5f

    /** How often a tripod's aim is told to the server while it moves, in ticks. */
    private const val TICKS_BETWEEN_AIMS = 2

    /** Less than this, in degrees, and an aim is not worth telling. */
    private const val AIM_WORTH_TELLING = 0.05f

    /**
     * What the view is, standing at the lens. Never added to the level. On a tripod it looks where [aimedBy] does, read
     * every frame rather than every tick, so the mouse aims it as smoothly as it turns the player's own view.
     */
    private class Lens(level: Level, private val eyeHeight: Float, private val aimedBy: LocalPlayer?) : Marker(EntityTypes.MARKER, level) {
        init {
            refreshDimensions()
        }

        override fun getDimensions(pose: Pose): EntityDimensions = EntityDimensions.fixed(0.0f, 0.0f).withEyeHeight(eyeHeight)

        override fun getViewYRot(partialTicks: Float): Float = aimedBy?.getViewYRot(partialTicks) ?: super.getViewYRot(partialTicks)

        override fun getViewXRot(partialTicks: Float): Float = aimedBy?.getViewXRot(partialTicks) ?: super.getViewXRot(partialTicks)
    }

    private var looking: BlockPos? = null
    private var lens: Lens? = null
    private var wasSneaking = true
    private var wasLeft = true
    private var wasRight = true
    private var isMounted = false
    private var lastToldYaw = 0.0f
    private var lastToldPitch = 0.0f
    private var ticksSinceToldTheAim = 0
    private var zoom = ImageRecorderBlockEntity.WIDEST
    private var isZoomUntold = false

    /** How far one notch of the wheel narrows the view, as the handheld viewfinder's does. */
    private const val NARROWING_A_NOTCH = 0.9f

    @JvmStatic
    fun isLooking(): Boolean = looking != null

    /** Moves the view to the recorder at [pos]. Called by the block, on the client, when it is used sneaking. */
    fun start(pos: BlockPos) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val state = level.getBlockState(pos)
        if (!state.`is`(ImagingContent.IMAGE_RECORDER_BLOCK) || looking != null) return
        val aim = ImageRecorderBlock.aimOf(level, pos) ?: return
        isMounted = state.getValue(ImageRecorderBlock.MOUNTED)
        if (isMounted) turnThePlayerTo(player, aim.yaw, aim.pitch)
        lastToldYaw = aim.yaw
        lastToldPitch = aim.pitch
        ticksSinceToldTheAim = 0
        zoom = (level.getBlockEntity(pos) as? ImageRecorderBlockEntity)?.zoom ?: ImageRecorderBlockEntity.WIDEST
        isZoomUntold = false
        // The eye where the camera already holds it, the player's own, so the view does not slide as it settles.
        val view = Lens(level, player.eyeHeight, player.takeIf { isMounted })
        looking = pos
        lens = view
        place(view, pos, snap = true)
        minecraft.setCameraEntity(view)
        wasSneaking = true
        wasLeft = true
        wasRight = true
        val options = minecraft.options
        val howToTurnIt = if (isMounted) {
            Component.translatable("agesandtheart_imaging.recorder.aim")
        } else {
            Component.translatable("agesandtheart_imaging.recorder.turn", options.keyLeft.translatedKeyMessage, options.keyRight.translatedKeyMessage)
        }
        ViewfinderHint.show(
            Component.translatable("agesandtheart_imaging.recorder.stop", options.keyShift.translatedKeyMessage),
            howToTurnIt,
            Component.translatable("agesandtheart_imaging.viewfinder.zoom"),
        )
    }

    /** The player facing where the recorder points, so the mouse moves both from there. */
    private fun turnThePlayerTo(player: LocalPlayer, yaw: Float, pitch: Float) {
        player.yRot = yaw
        player.xRot = pitch
        player.yRotO = yaw
        player.xRotO = pitch
    }

    fun stop() {
        val minecraft = Minecraft.getInstance()
        looking?.let { pos -> minecraft.player?.let { if (isMounted) tellTheAim(pos, it, always = true) } }
        looking?.let(::tellTheZoom)
        looking = null
        lens = null
        minecraft.player?.let { minecraft.setCameraEntity(it) }
    }

    /** Disconnected: nothing to put back. */
    fun forget() {
        looking = null
        lens = null
    }

    fun tick(minecraft: Minecraft) {
        val pos = looking ?: return
        val view = lens ?: return
        val player = minecraft.player
        val level = minecraft.level
        val state = level?.getBlockState(pos)
        val isStillThere = state != null && state.`is`(ImagingContent.IMAGE_RECORDER_BLOCK)
        val isStillNear = player != null && player.isAlive && player.distanceToSqr(Vec3.atCenterOf(pos)) <= REACH * REACH
        val isStillTheView = minecraft.getCameraEntity() === view && view.level() === level
        if (state == null || !isStillThere || !isStillNear || !isStillTheView) {
            stop()
            return
        }
        val options = minecraft.options
        val isSneaking = options.keyShift.isDown
        val isLeft = options.keyLeft.isDown
        val isRight = options.keyRight.isDown
        val sneakPressed = isSneaking && !wasSneaking
        val leftPressed = isLeft && !wasLeft
        val rightPressed = isRight && !wasRight
        wasSneaking = isSneaking
        wasLeft = isLeft
        wasRight = isRight
        if (sneakPressed && minecraft.gui.screen() == null) {
            stop()
            return
        }
        tellTheZoom(pos)
        if (isMounted) {
            tellTheAim(pos, player, always = false)
        } else {
            val turnedBy = (if (rightPressed) 1 else 0) - (if (leftPressed) 1 else 0)
            if (turnedBy != 0 && minecraft.gui.screen() == null) sendToServer(TurnTheRecorder(pos, turnedBy))
        }
        place(view, pos, snap = false)
    }

    /** The field of view to draw the preview at: the one its pictures are taken at, its zoom included. */
    @JvmStatic
    fun fieldOfView(): Float = Sketches.ORDINARY_FIELD_OF_VIEW * zoom

    /** How far it is zoomed, as a share of its widest view. */
    fun zoom(): Float = zoom

    /** A positive [notches] is the wheel turned away, which narrows the view, as through the handheld viewfinder. */
    fun scrolled(notches: Double) {
        val pos = looking ?: return
        zoom = (zoom * NARROWING_A_NOTCH.pow(notches.toFloat())).coerceIn(ImageRecorderBlockEntity.NARROWEST, ImageRecorderBlockEntity.WIDEST)
        (Minecraft.getInstance().level?.getBlockEntity(pos) as? ImageRecorderBlockEntity)?.zoomTo(zoom)
        isZoomUntold = true
    }

    /** The zoom told to the server once a tick at most, while it changes. */
    private fun tellTheZoom(pos: BlockPos) {
        if (!isZoomUntold) return
        sendToServer(ZoomTheRecorder(pos, zoom))
        isZoomUntold = false
    }

    /**
     * A tripod's aim, the player's own view's, kept on this client's recorder at once so it is drawn turning, and told
     * to the server every few ticks while it moves and [always] as the player stops looking.
     */
    private fun tellTheAim(pos: BlockPos, player: LocalPlayer, always: Boolean) {
        val recorder = player.level().getBlockEntity(pos) as? ImageRecorderBlockEntity ?: return
        recorder.aim(player.yRot, player.xRot)
        ticksSinceToldTheAim++
        val hasMoved = abs(Mth.wrapDegrees(recorder.aimYaw - lastToldYaw)) > AIM_WORTH_TELLING ||
            abs(recorder.aimPitch - lastToldPitch) > AIM_WORTH_TELLING
        val isDue = ticksSinceToldTheAim >= TICKS_BETWEEN_AIMS
        if (!always && !(hasMoved && isDue)) return
        sendToServer(AimTheRecorder(pos, recorder.aimYaw, recorder.aimPitch))
        lastToldYaw = recorder.aimYaw
        lastToldPitch = recorder.aimPitch
        ticksSinceToldTheAim = 0
    }

    /**
     * [view] at the lens, which on a tripod swings about its head with the aim. On the ground it turns toward the
     * recorder's way, there at once where it [snap]s and a little nearer each tick otherwise; on a tripod it looks where
     * the player does ([Lens]).
     */
    private fun place(view: Lens, pos: BlockPos, snap: Boolean) {
        val aim = ImageRecorderBlock.aimOf(view.level(), pos) ?: return
        val lensAt = aim.lens
        val y = lensAt.y - view.eyeHeight
        view.xo = if (snap) lensAt.x else view.x
        view.yo = if (snap) y else view.y
        view.zo = if (snap) lensAt.z else view.z
        view.setPos(lensAt.x, y, lensAt.z)
        view.xRotO = aim.pitch
        view.xRot = aim.pitch
        val turnedTo = if (snap) aim.yaw else view.yRot + Mth.clamp(Mth.wrapDegrees(aim.yaw - view.yRot), -TURNING_A_TICK, TURNING_A_TICK)
        view.yRotO = if (snap) aim.yaw else view.yRot
        view.yRot = turnedTo
    }
}
