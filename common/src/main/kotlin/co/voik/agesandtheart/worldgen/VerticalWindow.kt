package co.voik.agesandtheart.worldgen

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * The band of world **an Age with a field of its own** generates into — and it must agree with that Age's
 * dimension type, because the two are read by different halves of the game and neither checks the other.
 * The generator fills `minY..<topY` while the level admits blocks by its dimension type's JSON, and a
 * mismatch is not an error anywhere: it is blocks silently dropped, which reads in game as a world with no
 * bottom to it.
 *
 * An Age wearing a template's rock is not built from this at all: its band is that world's, and so is the
 * type it wears (`AgeGeneration.typeFor`). Handing the nether's 0..128 rock our −64..384 band left the roof
 * at 127 with two hundred blocks of nothing over it.
 *
 * **One band, and every dimension type carries it.** A landform is written against the floor and ceiling it
 * will actually get, so a second band is not a setting an Age turns up — it is a second set of numbers every
 * field would have to be true for. The Spire is the one that tried: its archipelago wanted the top of a
 * taller world, it took a band of its own to get there, and the band then rode in on the *sky* aspect, which
 * sheared the floor off every canyon that happened to draw it.
 *
 * **384 blocks, and multiples of sixteen throughout** — chunk sections are 16 blocks tall and vanilla
 * requires both numbers to divide by it.
 */
data class VerticalWindow(val minY: Int, val height: Int) {
    /** One past the topmost block, so it reads straight into a `minY..<topY` loop. */
    val topY: Int get() = minY + height

    companion object {
        const val MIN_Y = -64
        const val HEIGHT = 384

        /** One past the topmost block, as [topY] is. */
        const val TOP_Y = MIN_Y + HEIGHT

        /** The topmost block itself. */
        const val HIGHEST_BLOCK_Y = TOP_Y - 1

        /** Matches all three `agesandtheart:age…` dimension types, which differ only in light and roof. */
        val DEFAULT = VerticalWindow(minY = MIN_Y, height = HEIGHT)

        val CODEC: Codec<VerticalWindow> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("min_y").forGetter(VerticalWindow::minY),
                Codec.INT.fieldOf("height").forGetter(VerticalWindow::height),
            ).apply(instance, ::VerticalWindow)
        }
    }
}
