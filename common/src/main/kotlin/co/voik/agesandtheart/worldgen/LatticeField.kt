package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Lattice
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.roundToInt

/**
 * The `lattice` underground: square-cut tunnels crossing on a regular grid along all three axes — see
 * [Lattice] for the shape itself. This is where it is sized and seeded.
 *
 * **Every size is the same lattice drawn larger**: the spacing is a fixed multiple of the passage, so a
 * colossal lattice has the same long runs of tunnel between crossings as a minuscule one. The one
 * exception is the vertical spacing, which is held to what fits two storeys in the band — a lattice of one
 * storey has no shafts.
 */
object LatticeField {

    /** The passages between [lowY] and [highY], as the space to take out of the rock. */
    fun passages(lowY: Int, highY: Int, salt: Long = 0L, scale: Double = SizeScale.ORDINARY): Lattice {
        val band = highY - lowY + 1
        val width = (WIDTH * scale).roundToInt().coerceAtLeast(NARROWEST)
        val height = (HEIGHT * scale).roundToInt().coerceAtLeast(NARROWEST)
        val spacingXZ = width * SPACING_PER_WIDTH
        val spacingY = storeySpacing(band, height)
        val random = XoroshiroRandomSource(LATTICE_SEED xor salt)
        return Lattice(
            lowY = lowY,
            highY = highY,
            width = width,
            height = height,
            spacingXZ = spacingXZ,
            spacingY = spacingY,
            offsetX = random.nextInt(spacingXZ),
            offsetY = lowY + random.nextInt(slackOver(band, height, spacingY) + 1),
            offsetZ = random.nextInt(spacingXZ),
        )
    }

    /**
     * [SPACING_PER_HEIGHT] storeys' worth, or less where that would leave the band only one. Never so
     * little that the rock between storeys is thinner than a storey.
     */
    private fun storeySpacing(band: Int, height: Int): Int {
        val proportionate = height * SPACING_PER_HEIGHT
        val mostThatFitsTwo = band - height
        val twoCanFitWithRockBetween = mostThatFitsTwo > 2 * height
        return if (twoCanFitWithRockBetween) minOf(proportionate, mostThatFitsTwo) else proportionate
    }

    /**
     * How far the lowest storey can rise off the floor and still leave room for as many storeys as the band
     * could ever hold. The storeys are seeded within this rather than across a whole spacing, which would
     * often lose one — and in a colossal lattice, losing one of two leaves no shafts at all.
     */
    private fun slackOver(band: Int, height: Int, spacingY: Int): Int {
        if (band < height) return 0
        val storeys = (band - height) / spacingY + 1
        return band - ((storeys - 1) * spacingY + height)
    }

    // Unsaid: a passage 6 wide and 5 tall, every 48 across and every 30 up. Colossal is 24 by 20 every
    // 192 across, and every 59 up in a hills band.
    private const val WIDTH = 6.0
    private const val HEIGHT = 5.0
    private const val SPACING_PER_WIDTH = 8
    private const val SPACING_PER_HEIGHT = 6

    // So `minuscule` still leaves something a player can crawl through rather than a one-block crack.
    private const val NARROWEST = 2

    private const val LATTICE_SEED = 0x1A77_1CE5L
}
