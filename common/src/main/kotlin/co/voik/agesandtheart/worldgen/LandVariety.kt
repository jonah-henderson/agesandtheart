package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Exaggerated
import co.voik.agesandtheart.worldgen.field.Gullied
import co.voik.agesandtheart.worldgen.field.Landmarks
import co.voik.agesandtheart.worldgen.field.Scree
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.TerrainTable
import co.voik.agesandtheart.worldgen.field.Terraced
import co.voik.agesandtheart.worldgen.field.Undercut
import co.voik.agesandtheart.worldgen.field.Warped
import co.voik.agesandtheart.worldgen.field.Wear
import kotlin.math.min

/**
 * How a landform changes as it is crossed: its height above the waterline drifting over a kilometre or two and rising
 * far higher at the odd landmark, its slopes terraced in patches and cut by gullies, its faces undercut and footed with
 * scree, and its outlines bent at a few hundred blocks, so no two stretches of one landform are quite alike.
 *
 * [strength] is 0 for none, 1 for an Age that said nothing of it, and 2 at the most; [landScale] is the landform's
 * own size factor, which the wavelengths and the bend follow so a colossal landform drifts at its own scale.
 *
 * [erosion] is how worn the land is on average, on the same scale, and [strength] how far that swings from place to
 * place ([Wear]): none at all where the land does not vary, and from untouched to twice the average at the most.
 * Where the Age has a [TerrainTable], the drift follows its uplift and the wear its age.
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
        erosion: Double,
        landScale: Double,
        salt: Long,
        /** The Age's terrain table, which the drift and the wear follow where there is one. */
        table: TerrainTable? = null,
    ): TerrainField {
        val varies = strength > 0.0
        val stretched = stretchesAbove?.takeIf { varies }?.let { pivotY ->
            Exaggerated(
                field,
                pivotY,
                salt xor STRETCH_SALT,
                STRETCH_FIRST_OCTAVE,
                STRETCH_AMPLITUDES,
                STRETCH_SCALE * landScale,
                STRETCH_SWING * strength,
                VerticalWindow.HIGHEST_BLOCK_Y - ROOM_TO_GROW,
                landmarksFor(strength, landScale, salt),
                table,
            )
        } ?: field
        val worn = wornAbove?.takeIf { erosion > 0.0 }?.let { floorY ->
            worn(stretched, floorY, terraces, wearOf(strength, erosion, landScale, salt, table), salt)
        } ?: stretched
        if (!bends || !varies) return worn
        return Warped(
            worn,
            salt xor BEND_SALT,
            BEND_FIRST_OCTAVE,
            BEND_AMPLITUDES,
            BEND_SCALE * landScale,
            BEND_BLOCKS * min(strength, MOST_BEND) * landScale,
        )
    }

    /**
     * Where a landform's landmarks stand and how tall: the drift's own, and what a landform whose landmark is not a
     * taller self (a great pyramid, a hollow in the tangle, a gorge opening wide) places by.
     */
    fun landmarksFor(strength: Double, landScale: Double, salt: Long): Landmarks = Landmarks(
        salt xor LANDMARK_SALT,
        LANDMARK_RADIUS * landScale * RADII_PER_LANDMARK_CELL,
        LANDMARK_RADIUS * landScale,
        LANDMARK_BOOST,
        LANDMARK_CHANCE * strength.coerceAtLeast(0.0),
    )

    /** How worn each place is, which anything a landform builds by wear reads as well as the weather's own wear. */
    fun wearOf(strength: Double, erosion: Double, landScale: Double, salt: Long, table: TerrainTable? = null): Wear =
        Wear(salt xor WEAR_SALT, erosion, WEAR_SPREAD * strength, WEAR_STRETCH * landScale, table)

    /** [field] terraced, gullied, footed with scree and undercut, each as far as [wear] says at each place. */
    private fun worn(field: TerrainField, floorY: Int, terraces: Boolean, wear: Wear, salt: Long): TerrainField {
        val terraced = if (terraces) Terraced(field, salt xor TERRACE_SALT, TERRACE_STEP, TERRACED_SHARE, floorY, wear) else field
        val gullied = Gullied(terraced, salt xor GULLY_SALT, GULLY_DEPTH, GULLY_CELL, floorY, wear)
        val screed = Scree(gullied, salt xor SCREE_SALT, SCREE_SHARE, SCREE_DEEPEST, floorY, wear)
        return Undercut(screed, salt xor UNDERCUT_SALT, UNDERCUT_DEPTH, UNDERCUT_HEIGHT, floorY, wear)
    }

    // About 770 blocks to a swing before the landform's own size, and its half beneath it.
    private const val STRETCH_FIRST_OCTAVE = -7
    private val STRETCH_AMPLITUDES = listOf(1.0, 0.5)
    private const val STRETCH_SCALE = 6.0
    private const val STRETCH_SWING = 0.4

    // A landmark in about half of the cells at the unsaid variety and in every one at the most, three times as tall as
    // the land around it at its middle and easing back over 250 blocks before the landform's size, which is 500 at an
    // ordinary one's, in cells six times that across.
    private const val LANDMARK_RADIUS = 250.0
    private const val RADII_PER_LANDMARK_CELL = 6.0
    private const val LANDMARK_BOOST = 2.0
    private const val LANDMARK_CHANCE = 0.5
    private const val LANDMARK_SALT = 0x1A4D_3A2CL

    // A meander of about a thousand blocks and a wobble of 256 over it, before the landform's own size. Their
    // gradients together come to about two thirds; past [MOST_BEND] they would near one, where a warp folds.
    private const val BEND_FIRST_OCTAVE = -10
    private val BEND_AMPLITUDES = listOf(1.0, 0.0, 0.2)
    private const val BEND_SCALE = 1.0
    private const val BEND_BLOCKS = 60.0
    private const val MOST_BEND = 1.25

    // A worn or unworn stretch runs over a kilometre or so, and at the unsaid variety its wear swings from under half the
    // average to over half again. Each amount below is the ordinary wear's, and the most wear is twice it.
    private const val WEAR_SPREAD = 0.6
    private const val WEAR_STRETCH = 1200.0
    private const val WEAR_SALT = 0x3EA2_0FL

    // Gullies about twenty blocks apart, five deep where the ground is steep, with rills half that across them.
    private const val GULLY_DEPTH = 5.0
    private const val GULLY_CELL = 40.0
    private const val GULLY_SALT = 0x6011_1EDL

    // Seven-block steps over a quarter of the land.
    private const val TERRACE_STEP = 7.0
    private const val TERRACED_SHARE = 0.25
    private const val TERRACE_SALT = 0x7E44_ACEL

    // Scree climbing a third of a face, up to ten blocks deep.
    private const val SCREE_SHARE = 0.3
    private const val SCREE_DEEPEST = 10.0
    private const val SCREE_SALT = 0x5C2E_E0L

    // Notches up to three blocks into a face and four high, varying along it.
    private const val UNDERCUT_DEPTH = 3.0
    private const val UNDERCUT_HEIGHT = 4.0
    private const val UNDERCUT_SALT = 0x0DE2_C07L

    /** Headroom a stretch leaves under the top of the world, for what grows on the ground it raised. */
    private const val ROOM_TO_GROW = 24

    private const val STRETCH_SALT = 0x5712_E7C4L
    private const val BEND_SALT = 0xBE_4D_0A_11L
}
