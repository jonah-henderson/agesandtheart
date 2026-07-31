package co.voik.agesandtheart.sky

import co.voik.agesandtheart.math.Rgba

/**
 * The Spire's own sky, as a [SkySpec] so the general machinery draws it — the one handcrafted Age, and the
 * only sky nothing resolves to: no word chooses these numbers, a preset pins them.
 */
object SpireSky {

    /** Named once: the star reveal below and `TerrainPreview` both read it rather than keeping a copy. */
    const val UPPER_DECK_HEIGHT = 265.0

    /**
     * Level with the islands' waist, hiding the hanging spires. Rides with the terrain: lift the
     * archipelago and this must move by the same amount or it becomes a floor under everything.
     */
    const val LOWER_DECK_HEIGHT = 217.0

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
