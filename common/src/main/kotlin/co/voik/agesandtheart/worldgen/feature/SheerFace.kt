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
     * How likely a thing is to grow on a sheer face at [y] — **nothing at all below [NOTHING_BELOW]**,
     * then from [ON_LOW_GROUND] to certain.
     *
     * **The floor is the whole mechanic, and it used to be at sea level.** A quarter at y=63 meant a
     * noise cave breaking the surface of a plain grew crystals round its lip — technically a sheer face,
     * and no climb at all: you walked up to a hole in the ground and took them (Jonah, 2026-09-09,
     * walked). Rime is meant to be the mildest of the early materials' methods, and "climb to a sheer
     * face" is still a method; standing beside a pit is not.
     *
     * Ninety-six is chosen against vanilla's terrain rather than ours: plains and forest sit in the
     * sixties and seventies, windswept hills reach a hundred and ten, and the peaks go past a hundred and
     * forty. So it clears ordinary country and the caves that break it, and leaves the crystals somewhere
     * you can see from below and have to go up to.
     */
    fun likelihoodAt(y: Int): Double {
        if (y < NOTHING_BELOW) return NEVER
        if (y >= HIGH_GROUND_Y) return CERTAIN
        val climbed = (y - NOTHING_BELOW).toDouble() / (HIGH_GROUND_Y - NOTHING_BELOW)
        return ON_LOW_GROUND + climbed * (CERTAIN - ON_LOW_GROUND)
    }

    /**
     * Below this, none at all — high enough to clear vanilla's ordinary country and the noise caves that
     * open in it, low enough that any real highland has some.
     */
    const val NOTHING_BELOW = 96

    /** About as high as an ordinary mountain reaches, and well under what a cliff landform does. */
    private const val HIGH_GROUND_Y = 160

    private const val NEVER = 0.0
    private const val ON_LOW_GROUND = 0.25
    private const val CERTAIN = 1.0
}
