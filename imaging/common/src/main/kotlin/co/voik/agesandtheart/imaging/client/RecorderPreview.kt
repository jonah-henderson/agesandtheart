package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.ImageRecorderBlock
import co.voik.agesandtheart.imaging.ImagingContent
import co.voik.agesandtheart.imaging.TurnTheRecorder
import co.voik.agesandtheart.client.sendToServer
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Marker
import net.minecraft.world.entity.Pose
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * Looking through a set-down image recorder: the view moved to its lens and pointed its way, at the field of view it
 * takes pictures at, through the viewfinder's look. The player stays where they stand, and since the view is no longer
 * theirs, vanilla moves them no further; the strafe keys turn the recorder a sixteenth at a time, and sneaking stops.
 */
object RecorderPreview {

    /** Further than this from the recorder, and the view comes back. */
    private const val REACH = 8.0

    /** How far the view turns toward the recorder's way each tick, so a turn of a sixteenth takes three. */
    private const val TURNING_A_TICK = 7.5f

    /** What the view is, standing at the lens. Never added to the level. */
    private class Lens(level: Level, private val eyeHeight: Float) : Marker(EntityTypes.MARKER, level) {
        init {
            refreshDimensions()
        }

        override fun getDimensions(pose: Pose): EntityDimensions = EntityDimensions.fixed(0.0f, 0.0f).withEyeHeight(eyeHeight)
    }

    private var looking: BlockPos? = null
    private var lens: Lens? = null
    private var wasSneaking = true
    private var wasLeft = true
    private var wasRight = true

    @JvmStatic
    fun isLooking(): Boolean = looking != null

    /** Moves the view to the recorder at [pos]. Called by the block, on the client, when it is used sneaking. */
    fun start(pos: BlockPos) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val state = level.getBlockState(pos)
        if (!state.`is`(ImagingContent.IMAGE_RECORDER_BLOCK) || looking != null) return
        // The eye where the camera already holds it, the player's own, so the view does not slide as it settles.
        val view = Lens(level, player.eyeHeight)
        looking = pos
        lens = view
        place(view, pos, ImageRecorderBlock.yawOf(state), snap = true)
        minecraft.setCameraEntity(view)
        wasSneaking = true
        wasLeft = true
        wasRight = true
        val options = minecraft.options
        ViewfinderHint.show(
            Component.translatable("agesandtheart_imaging.recorder.stop", options.keyShift.translatedKeyMessage),
            Component.translatable("agesandtheart_imaging.recorder.turn", options.keyLeft.translatedKeyMessage, options.keyRight.translatedKeyMessage),
        )
    }

    fun stop() {
        val minecraft = Minecraft.getInstance()
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
        val turnedBy = (if (rightPressed) 1 else 0) - (if (leftPressed) 1 else 0)
        if (turnedBy != 0 && minecraft.gui.screen() == null) sendToServer(TurnTheRecorder(pos, turnedBy))
        place(view, pos, ImageRecorderBlock.yawOf(state), snap = false)
    }

    /** [view] at the lens, turned toward [yaw]: there at once where it [snap]s, a little nearer each tick otherwise. */
    private fun place(view: Lens, pos: BlockPos, yaw: Float, snap: Boolean) {
        val level = view.level()
        val lensAt = ImageRecorderBlock.lensOf(level.getBlockState(pos), pos)
        val y = lensAt.y - view.eyeHeight
        view.setPos(lensAt.x, y, lensAt.z)
        view.xo = lensAt.x
        view.yo = y
        view.zo = lensAt.z
        view.xRotO = 0.0f
        view.xRot = 0.0f
        val turnedTo = if (snap) yaw else view.yRot + Mth.clamp(Mth.wrapDegrees(yaw - view.yRot), -TURNING_A_TICK, TURNING_A_TICK)
        view.yRotO = if (snap) yaw else view.yRot
        view.yRot = turnedTo
    }
}
