package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Exaggerated
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Warped
import kotlin.math.min

/**
 * How a landform changes as it is crossed: its height above the waterline drifting over a kilometre or two, and its
 * outlines bent at a few hundred blocks, so no two stretches of one landform are quite alike.
 *
 * [strength] is 0 for none, 1 for an Age that said nothing of it, and 2 at the most; [landScale] is the landform's
 * own size factor, which the wavelengths and the bend follow so a colossal landform drifts at its own scale.
 */
object LandVariety {

    fun over(
        field: TerrainField,
        stretchesAbove: Int?,
        bends: Boolean,
        strength: Double,
        landScale: Double,
        salt: Long,
    ): TerrainField {
        if (strength <= 0.0) return field
        val stretched = stretchesAbove?.let { pivotY ->
            Exaggerated(
                field,
                pivotY,
                salt xor STRETCH_SALT,
                STRETCH_FIRST_OCTAVE,
                STRETCH_AMPLITUDES,
                STRETCH_SCALE * landScale,
                STRETCH_SWING * strength,
                VerticalWindow.HIGHEST_BLOCK_Y - ROOM_TO_GROW,
            )
        } ?: field
        if (!bends) return stretched
        return Warped(
            stretched,
            salt xor BEND_SALT,
            BEND_FIRST_OCTAVE,
            BEND_AMPLITUDES,
            BEND_SCALE * landScale,
            BEND_BLOCKS * min(strength, MOST_BEND) * landScale,
        )
    }

    // About 770 blocks to a swing before the landform's own size, and its half beneath it.
    private const val STRETCH_FIRST_OCTAVE = -7
    private val STRETCH_AMPLITUDES = listOf(1.0, 0.5)
    private const val STRETCH_SCALE = 6.0
    private const val STRETCH_SWING = 0.4

    // A meander of about a thousand blocks and a wobble of 256 over it, before the landform's own size. Their
    // gradients together come to about two thirds; past [MOST_BEND] they would near one, where a warp folds.
    private const val BEND_FIRST_OCTAVE = -10
    private val BEND_AMPLITUDES = listOf(1.0, 0.0, 0.2)
    private const val BEND_SCALE = 1.0
    private const val BEND_BLOCKS = 60.0
    private const val MOST_BEND = 1.25

    /** Headroom a stretch leaves under the top of the world, for what grows on the ground it raised. */
    private const val ROOM_TO_GROW = 24

    private const val STRETCH_SALT = 0x5712_E7C4L
    private const val BEND_SALT = 0xBE_4D_0A_11L
}
