package co.voik.agesandtheart.worldgen

/**
 * A landform's `size` dial read so that the unsaid size stands about as tall as vanilla's terrain, while colossal
 * stays as big as it was and minuscule as small: the power [SizeScale] raises its base to, a half where nothing
 * was said and still one and minus one at the ends, in two straight runs either side of the middle.
 *
 * | word | factor |
 * |---|---|
 * | minuscule | 0.25 |
 * | small | 0.71 |
 * | unsaid | 2 |
 * | large | 2.83 |
 * | colossal | 4 |
 */
object LandSize {

    fun powerFor(said: Double?): Double {
        val dial = said ?: UNSAID
        val runToTheEnd = if (dial >= UNSAID) TOP - ORDINARY_POWER else ORDINARY_POWER - BOTTOM
        return ORDINARY_POWER + dial * runToTheEnd
    }

    /** Vanilla's scale, read off its mountains against ours: twice what the landforms were tuned to. */
    private const val ORDINARY_POWER = 0.5
    private const val UNSAID = 0.0
    private const val TOP = 1.0
    private const val BOTTOM = -1.0
}
