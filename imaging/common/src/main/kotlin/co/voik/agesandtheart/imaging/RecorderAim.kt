package co.voik.agesandtheart.imaging

import net.minecraft.world.phys.Vec3

/**
 * Where a set-down recorder points and where its parts stand: turned about [pivot], the middle of its base, by [yaw] and
 * [pitch] as an entity's are counted. One on the ground turns only about its base; one on a tripod tips about the
 * tripod's head as well. Measured off Jonah's model, in its pixels from the pivot, facing along the recorder.
 */
data class RecorderAim(val pivot: Vec3, val yaw: Float, val pitch: Float) {

    private val forward: Vec3 get() = Vec3.directionFromRotation(pitch, yaw)
    private val up: Vec3 get() = Vec3.directionFromRotation(pitch - QUARTER_TURN, yaw)

    private fun along(aheadPixels: Double, upPixels: Double): Vec3 =
        pivot.add(forward.scale(aheadPixels / PIXELS)).add(up.scale(upPixels / PIXELS))

    /** Where a picture is taken from: just past the lens's glass. */
    val lens: Vec3 get() = along(LENS_AHEAD, LENS_UP)

    /** The flash, at the front of the viewfinder on top. */
    val flash: Vec3 get() = along(FLASH_AHEAD, FLASH_UP)

    private companion object {
        const val PIXELS = 16.0
        const val QUARTER_TURN = 90.0f

        /** The lens's glass is six pixels ahead of the middle and two up; the eye stands half a pixel past it. */
        const val LENS_AHEAD = 6.5
        const val LENS_UP = 2.0

        /** The viewfinder's face is two pixels ahead of the middle, and it stands from six pixels up to ten. */
        const val FLASH_AHEAD = 2.5
        const val FLASH_UP = 9.0
    }
}
