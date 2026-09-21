package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.worldgen.field.fieldNoise
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.Noise

/**
 * The plan of one crack: which way it runs, how it wanders, and how wide it is along its length.
 *
 * **Off the field toolkit's own noise**, which is the answer to "should this be worldgen somewhere". A
 * fissure is a landform, and the mod already has the thing that makes landforms look like they happened
 * rather than were placed — so the wander and the width are `fieldNoise` samples, not a taper written out
 * in arithmetic. The first version was a symmetric parabola on one of two axes and read, correctly, as a
 * hole somebody had dug.
 *
 * Three things do the work. It runs at **any angle**, not one of two. Its centreline **wanders**, so the
 * two sides are never mirror images. And its width **varies along the run** and pinches to nothing at both
 * ends, which is what makes it read as torn open rather than bored out.
 */
internal class Crack(private val alongX: Double, private val alongZ: Double, private val noise: Noise) {

    fun reaches(offsetX: Int, offsetZ: Int): Boolean = reaches(offsetX, offsetZ, 0.0)

    /**
     * The same crack [wider] blocks fatter and longer — **which is what lets a tear grow without becoming a
     * circle**.
     *
     * A radius around a point reads as a hole somebody bored; a crack widened along its own axis still
     * reads as something torn. Both the half-length and the width take the widening, so a tear that has
     * been open a while is a longer, fatter crack rather than a blob with a crack inside it.
     */
    fun reaches(offsetX: Int, offsetZ: Int, wider: Double): Boolean {
        val (offCentre, width) = measure(offsetX.toDouble(), offsetZ.toDouble(), wider) ?: return false
        return offCentre <= width
    }

    /**
     * How central a column is: 1 on the crack's own centreline, falling to 0 at its edge, and below 0
     * outside it — what a cave-in cuts its floor to, so a trough is deepest down the middle.
     *
     * Offsets are fractional because a cave-in reads the plan at whatever scale its own size asks for, so
     * the same crack serves a swathe half its length and one twice it.
     */
    fun centralityAt(offsetX: Double, offsetZ: Double): Double {
        val (offCentre, width) = measure(offsetX, offsetZ, 0.0) ?: return OUTSIDE
        return if (width > 0.0) 1.0 - offCentre / width else OUTSIDE
    }

    /** How far a column stands off the crack's wandering centre, and how wide the crack is there; null past its ends. */
    private fun measure(offsetX: Double, offsetZ: Double, wider: Double): Pair<Double, Double>? {
        // Into the crack's own frame: how far along its run, and how far off its centre.
        val along = offsetX * alongX + offsetZ * alongZ
        val across = -offsetX * alongZ + offsetZ * alongX
        val length = HALF_LENGTH + wider
        if (along < -length || along > length) return null

        val reach = along / length
        // Pinched at both ends, so it tapers to a point rather than stopping square.
        val taper = 1.0 - reach * reach
        val wander = noise.get(along * WANDER_SCALE, 0.0, 0.0).toDouble() * MOST_WANDER * taper
        val widening = noise.get(0.0, 0.0, along * WIDTH_SCALE).toDouble() * WIDTH_VARIES
        val width = (NARROWEST + widening + wider) * taper
        return kotlin.math.abs(across - wander) to width
    }

    companion object {
        /** What [centralityAt] says of a column the crack does not reach at all. */
        private const val OUTSIDE = -1.0

        /** Long and thin: a crack across the ground rather than a pit in it. */
        const val HALF_LENGTH = 17.0

        /** How far the centreline may stray, which is what stops the two sides mirroring. */
        const val MOST_WANDER = 5.0

        // Wide enough that the middle is never a single column: walked at 1.4 and the crack pinched to
        // one block for long stretches, which reads as a seam in the ground rather than a way into it.
        private const val NARROWEST = 2.6
        private const val WIDTH_VARIES = 1.6

        /** Slow enough that the wander reads as a curve rather than as static. */
        private const val WANDER_SCALE = 0.06
        private const val WIDTH_SCALE = 0.22

        private const val OCTAVE = -3
        private val AMPLITUDES = listOf(1.0, 0.5)

        fun of(shape: Long): Crack {
            val turn = XoroshiroRandomSource(shape).nextDouble() * Math.PI
            return Crack(kotlin.math.cos(turn), kotlin.math.sin(turn), fieldNoise(shape, OCTAVE, AMPLITUDES))
        }
    }
}
