package co.voik.agesandtheart.sky

import co.voik.ephemeris.sky.CloudDeck
import co.voik.ephemeris.sky.Look
import co.voik.ephemeris.sky.SkySpec
import co.voik.ephemeris.sky.StarField
import co.voik.ephemeris.sky.StarReveal

import co.voik.ephemeris.Rgba
import co.voik.agesandtheart.worldgen.SpireField

/**
 * The Spire's own sky, as a [SkySpec] so the general machinery draws it — the one handcrafted Age, and the
 * only sky nothing resolves to: no word chooses these numbers, a preset pins them.
 */
object SpireSky {

    /**
     * High enough that the island tops sit just under it and only the tallest spires break through.
     *
     * Named once: the star reveal below and `TerrainPreview` both read it rather than keeping a copy.
     */
    const val UPPER_DECK_HEIGHT = SpireField.DECK_Y + 45.0

    /**
     * Level with the islands' waist, hiding the hanging spires.
     *
     * **Both decks are measured off [SpireField.DECK_Y] rather than written down**, because they ride with
     * the archipelago: move it and a deck left behind becomes a floor under everything.
     */
    const val LOWER_DECK_HEIGHT = SpireField.DECK_Y - 3.0

    /**
     * The storm-grey the Spire paints its air, which used to be a dimension type of its own.
     *
     * Held here as exact colours rather than drawn from `Colour`'s nine: nothing resolves to this sky, a
     * preset pins it, so these are the numbers a person chose and not a word's answer.
     *
     * [Look.ceiling] is a fraction of the cloud band rather than a height, so 0.656 is the 201 blocks the
     * dimension type used to declare.
     */
    val LOOK = Look(
        sky = Rgba(0.22f, 0.25f, 0.26f),
        fog = Rgba(0.30f, 0.33f, 0.34f),
        cloud = Rgba(0.30f, 0.33f, 0.34f, alpha = 0.80f),
        ceiling = SPIRE_CLOUD_CEILING,
        // **Locked at midnight** (Jonah, 2026-08-05). The Spire has never had a sun, so a day cycle over it
        // only ever meant its stars going out for half of it — which is what made the reveal look broken
        // when it was working exactly as written.
        starBrightness = MIDNIGHT,
    )

    private const val MIDNIGHT = 1.0f

    /** Where 201 blocks sits on `AgeAir`'s 96..256 cloud band. */
    private const val SPIRE_CLOUD_CEILING = 0.656f

    /** Mostly light grey with cool blue-grey darker spots, drifting faster than the deck below. */
    private val UPPER_DECK = CloudDeck(
        height = UPPER_DECK_HEIGHT,
        low = Rgba(0.16f, 0.17f, 0.22f),
        high = Rgba(0.47f, 0.50f, 0.51f),
        driftSpeed = 0.045f,
    )

    /**
     * Mostly near-black with lighter grey foam, offset far into the noise field: two decks sampling the
     * same region mirror each other and read as one thick layer.
     */
    private val LOWER_DECK = CloudDeck(
        height = LOWER_DECK_HEIGHT,
        low = Rgba(0.12f, 0.14f, 0.15f),
        high = Rgba(0.42f, 0.44f, 0.45f),
        driftSpeed = 0.015f,
        noiseOffsetX = 9000.0,
        noiseOffsetZ = 4000.0,
    )

    /** Stars appear only above the upper deck, across a band derived from it. */
    private val ABOVE_THE_UPPER_DECK = StarReveal(
        hiddenBelow = UPPER_DECK_HEIGHT - 2.0,
        fullyShownAbove = UPPER_DECK_HEIGHT + 20.0,
    )

    private const val STAR_SEED = 0xA6E57A25L

    /** Far fewer than an open sky's, because only the ones overhead are ever seen through the gap. */
    private const val STAR_COUNT = 180

    /** No bodies — the Spire has never had a sun or a moon. */
    val SPEC = SkySpec(
        bodies = emptyList(),
        stars = StarField(STAR_COUNT, STAR_SEED, ABOVE_THE_UPPER_DECK),
        decks = listOf(LOWER_DECK, UPPER_DECK),
    )
}
