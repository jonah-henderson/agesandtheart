package co.voik.agesandtheart.worldgen.feature

/**
 * What counts as a sheer face, and how readily a thing grows on one at a given height.
 *
 * **Measured rather than guessed** (`/age cliffs`, 2026-09-05, and `decisions.md`). Over three vanilla
 * seeds a drop of ten blocks to the lowest neighbour stands at 0.30%–0.86% of columns — about one or two
 * in a chunk — where a drop of four is 2%–3% and is a step rather than a cliff. Ten is where a face stops
 * being terrain and starts being an obstacle, and that is the number.
 *
 * **The height bias has to be imposed, because vanilla does not keep it.** Sheer columns cluster at the
 * *modal* terrain height and fall away above it: one seed had 4.3% of its y 62..65 band sheer and none at
 * all of 66..69. So altitude selects for cliffs only where a landform makes it — our `cliffs` runs from
 * 0.26% low down to 40% high up, and `canyon` runs the other way entirely, its great faces being down in
 * the gorge.
 *
 * **Tuned so vanilla's own mountains pay** (Jonah, 2026-09-05), rather than so a cliff landform pays. That
 * is the way round that leaves room: an ordinary snowy Age yields a little, and a writer who works out that
 * `cliffs` or `canyon` is what the crystals want can write an Age that is rich in them. Tuning it the other
 * way would have made the ordinary case empty and the clever case ordinary.
 */
object SheerFace {

    /**
     * How far the ground must fall away beside a block for it to be a cliff rather than a step.
     *
     * The same number in two places by construction: the survey counts faces at this height and the
     * feature refuses anything under it.
     */
    const val SHEER_BLOCKS = 10

    /** How much open air a face wants above it, so a crevice does not read as a cliffside. */
    const val OPEN_ABOVE = 4

    /**
     * How likely a thing is to grow on a sheer face at [y], from [ON_LOW_GROUND] to certain.
     *
     * **A floor rather than a ramp from nothing**, because vanilla's cliffs are at its *modal* height and a
     * ramp that started there would make an ordinary Age empty. A quarter at sea level and everything by
     * the height ordinary mountains reach.
     */
    fun likelihoodAt(y: Int): Double {
        if (y <= LOW_GROUND_Y) return ON_LOW_GROUND
        if (y >= HIGH_GROUND_Y) return CERTAIN
        val climbed = (y - LOW_GROUND_Y).toDouble() / (HIGH_GROUND_Y - LOW_GROUND_Y)
        return ON_LOW_GROUND + climbed * (CERTAIN - ON_LOW_GROUND)
    }

    /** Sea level, where vanilla keeps most of its cliffs. */
    private const val LOW_GROUND_Y = 63

    /** About as high as an ordinary mountain reaches, and well under what a cliff landform does. */
    private const val HIGH_GROUND_Y = 160

    private const val ON_LOW_GROUND = 0.25
    private const val CERTAIN = 1.0
}
