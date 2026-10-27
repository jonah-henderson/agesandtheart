package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Exaggerated
import co.voik.agesandtheart.worldgen.field.Gullied
import co.voik.agesandtheart.worldgen.field.Scree
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Terraced
import co.voik.agesandtheart.worldgen.field.Undercut
import co.voik.agesandtheart.worldgen.field.Warped
import kotlin.math.min

/**
 * How a landform changes as it is crossed: its height above the waterline drifting over a kilometre or two, its slopes
 * terraced in patches and cut by gullies, its faces undercut and footed with scree, and its outlines bent at a few
 * hundred blocks, so no two stretches of one landform are quite alike.
 *
 * [strength] is 0 for none, 1 for an Age that said nothing of it, and 2 at the most; [landScale] is the landform's
 * own size factor, which the wavelengths and the bend follow so a colossal landform drifts at its own scale.
 */
object LandVariety {

    fun over(
        field: TerrainField,
        stretchesAbove: Int?,
        /** The lowest a gully may cut or scree lie, or null where this field is not worn — water, and the made shapes. */
        wornAbove: Int?,
        /** Whether worn ground is terraced in patches. */
        terraces: Boolean,
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
        val worn = wornAbove?.let { floorY ->
            val terraced = if (terraces) Terraced(stretched, salt xor TERRACE_SALT, TERRACE_STEP, TERRACED_SHARE * strength, floorY) else stretched
            val gullied = Gullied(terraced, salt xor GULLY_SALT, GULLY_DEPTH * min(strength, MOST_GULLIED), GULLY_CELL, floorY)
            val screed = Scree(gullied, salt xor SCREE_SALT, SCREE_SHARE, SCREE_DEEPEST * min(strength, MOST_SCREE), floorY)
            Undercut(screed, salt xor UNDERCUT_SALT, UNDERCUT_DEPTH * min(strength, MOST_UNDERCUT), UNDERCUT_HEIGHT, floorY)
        } ?: stretched
        if (!bends) return worn
        return Warped(
            worn,
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

    // Gullies about twenty blocks apart, five deep where the ground is steep, with rills half that across them. Past
    // [MOST_GULLIED] a gully cuts deeper than it is wide.
    private const val GULLY_DEPTH = 5.0
    private const val GULLY_CELL = 40.0
    private const val MOST_GULLIED = 1.6
    private const val GULLY_SALT = 0x6011_1EDL

    // Seven-block steps over a quarter of the land, and up to half of it at the most.
    private const val TERRACE_STEP = 7.0
    private const val TERRACED_SHARE = 0.25
    private const val TERRACE_SALT = 0x7E44_ACEL

    // Scree climbing a third of a face, up to ten blocks deep. Past [MOST_SCREE] it fills a gorge.
    private const val SCREE_SHARE = 0.3
    private const val SCREE_DEEPEST = 10.0
    private const val MOST_SCREE = 1.6
    private const val SCREE_SALT = 0x5C2E_E0L

    // Notches up to three blocks into a face and four high, varying along it; past [MOST_UNDERCUT] a cap overhangs
    // further than it stands thick.
    private const val UNDERCUT_DEPTH = 3.0
    private const val UNDERCUT_HEIGHT = 4.0
    private const val MOST_UNDERCUT = 1.7
    private const val UNDERCUT_SALT = 0x0DE2_C07L

    /** Headroom a stretch leaves under the top of the world, for what grows on the ground it raised. */
    private const val ROOM_TO_GROW = 24

    private const val STRETCH_SALT = 0x5712_E7C4L
    private const val BEND_SALT = 0xBE_4D_0A_11L
}
