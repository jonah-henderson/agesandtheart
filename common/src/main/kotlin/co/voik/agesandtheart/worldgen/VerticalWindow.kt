package co.voik.agesandtheart.worldgen

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * The band of world an Age generates into — **and it must agree with that Age's dimension type**, because
 * the two are read by different halves of the game and neither checks the other.
 *
 * Per Age rather than one shared constant, because [AgeChunkGenerator.fillFromNoise] walks every column
 * over the whole band: a taller world costs every Age proportionally, and an Age of caverns wants depth
 * below zero that the Spire never touches. Each spends its 384 blocks where its world actually is.
 *
 * **384 in both cases, and multiples of sixteen throughout** — chunk sections are 16 blocks tall and
 * vanilla requires both numbers to divide by it.
 */
data class VerticalWindow(val minY: Int, val height: Int) {
    /** One past the topmost block, so it reads straight into a `minY..<topY` loop. */
    val topY: Int get() = minY + height

    companion object {
        /** What every Age had before this was a choice — matches `agesandtheart:age`. */
        val DEFAULT = VerticalWindow(minY = -64, height = 384)

        /**
         * The Spire's band, matching `agesandtheart:age_spire`. Shifted up 64 rather than made taller: the
         * archipelago's lowest rock sits at y=63, so everything below zero was always empty.
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
