package co.voik.agesandtheart.worldgen

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * The band of world an Age generates into — **and it must agree with that Age's dimension type**, because the
 * two are read by different halves of the game and neither checks the other.
 *
 * This used to be a pair of constants shared by every field Age, with a comment saying they matched
 * `agesandtheart:age`. That was fine until the Spire needed to sit higher: its islands' tops were measured at
 * a median of y=157 against an upper cloud deck at 265, so the deck that the renderer's own doc says exists to
 * *"hide the peaks"* was hiding one column in a hundred. Closing that gap wants about eighty blocks of lift, and
 * the shared ceiling at y=319 had seventeen — the central spires already reach 302.
 *
 * Raising the ceiling for everyone is the wrong trade: [AgeChunkGenerator.fillFromNoise] walks every column
 * over this whole band, so a taller world costs every Age proportionally, and an Age of caverns wants the depth
 * below zero that the Spire never touches. Making the band per-Age costs nothing and lets each one spend its
 * 384 blocks where its world actually is.
 *
 * **384 in both cases, and multiples of sixteen throughout** — chunk sections are 16 blocks tall and vanilla
 * requires both numbers to divide by it.
 */
data class VerticalWindow(val minY: Int, val height: Int) {
    /** One past the topmost block, so it reads straight into a `minY..<topY` loop. */
    val topY: Int get() = minY + height

    companion object {
        /** What every Age had before this was a choice — matches `agesandtheart:age` and `age_plain`. */
        val DEFAULT = VerticalWindow(minY = -64, height = 384)

        /**
         * The Spire's band, matching `agesandtheart:age_spire`.
         *
         * Shifted up a whole 64 rather than made taller: the archipelago's lowest rock sits at y=63, so
         * everything below zero was empty in every Spire ever generated. The same volume, spent where the
         * islands are.
         */
        val LIFTED = VerticalWindow(minY = 0, height = 384)

        val CODEC: Codec<VerticalWindow> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("min_y").forGetter(VerticalWindow::minY),
                Codec.INT.fieldOf("height").forGetter(VerticalWindow::height),
            ).apply(instance, ::VerticalWindow)
        }
    }
}
