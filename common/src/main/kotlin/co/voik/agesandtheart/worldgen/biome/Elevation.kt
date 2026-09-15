package co.voik.agesandtheart.worldgen.biome

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * What **height alone** says about what grows — a treeline and a snowline, said in vanilla's own climate.
 *
 * [Grounding] answers three of vanilla's parameters off the shape's *plan*: how far inland a column is, how
 * steeply it falls, and whether a river runs down it. That is enough for a coast and nothing like enough for
 * a mountain, because the two facts that make a range legible are vertical. Without them a summit is grass,
 * a treeline is wherever the noise put one, and a two-hundred-block climb passes through no country at all.
 *
 * Two parameters carry it, and neither is invented:
 *
 * - **Temperature falls with altitude.** A real lapse rate is about 6.5 °C per kilometre, and the only thing
 *   to decide is what that is worth on an axis running −1 to 1. [lapsePerBlock] is that, and it is the whole
 *   of the treeline: vanilla's table already turns a cold, wet column into taiga and a colder one into
 *   snowy_slopes without being told anything about mountains.
 * - **Weirdness is vanilla's ridge-and-valley axis**, and it is the one place a shape *must* speak up.
 *   `OverworldBiomeBuilder` files jagged_peaks, frozen_peaks and stony_peaks only in a narrow slice of it
 *   — `|weirdness|` between 0.567 and 0.767 — with the slope biomes flanking that and the valley biomes at
 *   zero. Left to the noise, whether a column is a peak has nothing to do with whether it *is* one, and a
 *   range built out of this toolkit would never grow a single peak biome. A landform that knows its own
 *   relief simply says so.
 *
 * The reading is deliberately of **absolute height**, not height above the nearest channel, and that is what
 * makes it worth so little code: on a wedge-shaped range the two agree closely enough, and the resulting
 * banding — plain, forested foothill, slope, crest — is the alpine zonation itself.
 */
data class Elevation(
    /** The level nothing is chilled at and everything reads as valley — the range's own foreland. */
    val fromY: Int,
    /** And the level at which a column reads as fully a summit. Around the crest, not above the tallest. */
    val toY: Int,
    /** How much of vanilla's temperature axis a block of climb costs. */
    val lapsePerBlock: Double = DEFAULT_LAPSE_PER_BLOCK,
) {
    /** How far up this Age's relief a surface stands, nought at [fromY] and one at [toY]. */
    fun standingAt(surfaceY: Int): Float {
        val climb = (surfaceY - fromY).toFloat()
        val whole = (toY - fromY).toFloat()
        if (whole <= 0.0f) return 0.0f
        return (climb / whole).coerceIn(0.0f, 1.0f)
    }

    /**
     * [otherwise] with the altitude taken out of it. Ground below [fromY] is left alone rather than warmed:
     * a lapse rate describes air getting colder as you climb, and running it backwards would make a hollow
     * tropical.
     */
    fun chilled(surfaceY: Int, otherwise: Float): Float {
        val climb = (surfaceY - fromY).coerceAtLeast(0)
        return (otherwise - (climb * lapsePerBlock).toFloat()).coerceIn(-1.0f, 1.0f)
    }

    /**
     * Where this column sits on vanilla's ridge-and-valley axis, given how high it stands.
     *
     * The **sign is [otherwise]'s**, and that is not tidiness: vanilla reads it to pick between variants of
     * the same landform — a negative peak is jagged_peaks and a positive one frozen_peaks — so keeping the
     * noise's own sign is what leaves a crest with bare rock in some places and ice in others instead of one
     * biome the length of the range.
     */
    fun weirdnessFor(surfaceY: Int, otherwise: Float): Float {
        // **Starting clear of the valley band, not at zero.** Vanilla files its *rivers* in `|w| < 0.05`, so
        // mapping the low ground to nought tells the table that every basin in the Age is a river — and it
        // duly grew them, on 53% of the country's dry land. Where the water actually is, `Grounding` has
        // already said so and answered before this is ever reached.
        val standing = standingAt(surfaceY)
        val magnitude = CLEAR_OF_THE_VALLEY + standing * (MIDDLE_OF_THE_PEAK_BAND - CLEAR_OF_THE_VALLEY)
        return if (otherwise < 0.0f) -magnitude else magnitude
    }

    /**
     * How far inland this column reads as — **the axis a landlocked Age has to answer for itself.**
     *
     * `Grounding` normally reads continentalness off how far the ground stands over the waterline, which is
     * exactly right for a coast and meaningless for a range with no sea: its "waterline" is a datum under
     * the bedrock, so the deepest valleys come out a block or two above it and read as *shore*. Beaches in
     * an alpine valley, and ocean where the trunks run.
     *
     * A range is inland everywhere, so the axis carries the only inland distinction vanilla makes — the
     * plain is mid-inland and the mountains far — which is also the band the peak and slope biomes are
     * filed in.
     */
    fun continentalnessFor(surfaceY: Int): Float =
        MID_INLAND + (FAR_INLAND - MID_INLAND) * standingAt(surfaceY)

    companion object {
        /**
         * How much of the temperature axis a block costs. At sixteen metres to the block this is close to a
         * real lapse rate against vanilla's own span, and what it buys is concrete: a hundred and sixty
         * blocks of climb crosses two of vanilla's five temperature bands, so a valley of forest becomes
         * taiga becomes snowy slopes on the way up.
         */
        const val DEFAULT_LAPSE_PER_BLOCK = 0.005

        /**
         * The centre of the slice `OverworldBiomeBuilder` files its peak biomes in — `PEAK_START` 0.56667 to
         * `PEAK_END` 0.76667. Mapping full relief here rather than to 1.0 matters: past the band's top the
         * table comes back **down** through the slope biomes, so a summit sent to 1.0 would grow whatever
         * a hillside does.
         */
        private const val MIDDLE_OF_THE_PEAK_BAND = 0.6667f

        /**
         * Just outside vanilla's valley slice, which is exactly `-0.05..0.05`. The lowest ground an Age with
         * relief has is a plain, not a river, and the river band belongs to the columns that actually carry
         * water — which `Grounding` identifies from the shape and answers for before asking this.
         */
        private const val CLEAR_OF_THE_VALLEY = 0.0501f

        /**
         * Where a landlocked Age's plain and its mountains sit on vanilla's continentalness axis.
         * `OverworldBiomeBuilder` bands it mid-inland 0.03..0.3 and far inland 0.3..1.0, and these are
         * points inside those — comfortably clear of the coast band below, which is the whole point.
         */
        private const val MID_INLAND = 0.15f
        private const val FAR_INLAND = 0.62f

        val CODEC: Codec<Elevation> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("from_y").forGetter(Elevation::fromY),
                Codec.INT.fieldOf("to_y").forGetter(Elevation::toY),
                Codec.DOUBLE.optionalFieldOf("lapse_per_block", DEFAULT_LAPSE_PER_BLOCK)
                    .forGetter(Elevation::lapsePerBlock),
            ).apply(instance, ::Elevation)
        }
    }
}
