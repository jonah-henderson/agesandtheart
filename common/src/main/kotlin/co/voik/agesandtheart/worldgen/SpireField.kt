package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Cone
import co.voik.agesandtheart.worldgen.field.Ellipsoid
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import co.voik.agesandtheart.worldgen.field.Weathered
import kotlin.math.roundToInt

/**
 * The Spire archipelago: **blocky masses pared back by erosion** rather than assembled from ellipsoids
 * and cones.
 *
 * Each island is a cluster of overlapping [Ellipsoid] lobes, cut to profile by [NoiseHeightmap]s — one
 * standing for a rolling peaked top, one hanging for a ragged underside — both stretched along the wind
 * axis so the relief combs into parallel ridges.
 *
 * The last pass is [co.voik.agesandtheart.worldgen.field.Weathered], which undercuts, the one thing
 * neither noise nor CSG reaches. The field does the *gross* shape, because `getBaseHeight` answers from
 * the field and decoration would otherwise place in mid-air.
 */
object SpireField {

    /**
     * The archipelago, weathered — **part of the shape, not something a word adds.**
     *
     * The islands are lobed masses of noise until the wind pares them back to ribs and talons, and an
     * unweathered one does not read as a plainer island, it reads as a blob. Every other landform here
     * says this in its own field; the Spire said it through a `weathered` *carving* for as long as
     * carvers were taken to mean "take rock away", and that was a misconception rather than a design.
     */
    fun world(salt: Long = 0L): TerrainField = Weathered.spire(bareWorld(salt))

    /**
     * The masses before the wind reaches them — the previewer's other half, and nothing else's.
     *
     * They are **two populations, not one**: a sparse scatter of big carved islands, and a denser shoal of
     * small noise blobs between them. Separate layers rather than two templates in one [Instanced], which
     * cannot express it: it picks uniformly across `templates × sizes`, so the small ones could not be
     * commoner, and it carries one [Variation], so they could not have wider vertical freedom.
     */
    fun bareWorld(salt: Long = 0L): TerrainField = Union(listOf(bigIslands(salt), smallIslands(salt)))

    /**
     * The small islands: simple envelopes cut out of one continuous field of 3D noise.
     *
     * **The noise is hoisted above the instancer, and has to be.** A template is queried in its own local
     * coordinates, so a [Noise3D] used *as* a template gives every copy an identical form;
     * `Intersect(Instanced(envelopes), noise)` reads world coordinates, so each blob is cut from a
     * different region of one field and neighbours agree where they overlap.
     *
     * It is also what makes this affordable: [Intersect] asks its cheapest child first, so the noise band
     * is walked only where an envelope already stands.
     */
    private fun smallIslands(salt: Long): TerrainField {
        // Three shapes rather than one, because scaling alone only makes the same island bigger.
        val envelopes = listOf(
            SMALL_HALF_WIDTH to SMALL_RADIUS_Y * LENS_FLATTEN,
            SMALL_HALF_WIDTH * LUMP_SHARE to SMALL_RADIUS_Y,
            SMALL_HALF_WIDTH * SHARD_SHARE to SMALL_RADIUS_Y * SHARD_STRETCH,
        ).map { (radiusXZ, radiusY) ->
            Ellipsoid(centerX = 0, centerZ = 0, centerY = DECK_Y, radiusXZ = radiusXZ, radiusY = radiusY)
        }

        return Intersect(
            listOf(
                Instanced(
                    templates = envelopes,
                    placement = Grid(SMALL_SPACING, SMALL_JITTER, Density.uniform(SMALL_DENSITY)),
                    variation = SMALL_VARIATION,
                    seed = SHOAL_SEED xor salt,
                ),
                Noise3D(
                    seed = BLOB_SEED xor salt,
                    firstOctave = -5,
                    // The third octave is the fine one, which roughens an edge that would otherwise follow
                    // the envelope's arc smoothly.
                    amplitudes = listOf(1.0, 0.5, 0.25),
                    scaleX = BLOB_SCALE,
                    // Squashed, so a blob breaks into stacked flattish masses rather than vertical shafts.
                    scaleY = BLOB_SCALE * BLOB_SQUASH,
                    scaleZ = BLOB_SCALE,
                    character = NoiseCharacter.PLAIN,
                    threshold = BLOB_THRESHOLD,
                    lowY = SMALL_BAND_LOW,
                    highY = SMALL_BAND_HIGH,
                ),
            ),
        )
    }

    /** The big carved islands — the original archipelago, unchanged in shape and only laid out differently. */
    private fun bigIslands(salt: Long): TerrainField {
        // Rolling top: the noisy surface sits above the island's deck, so rock fills upward to it.
        val peaks = NoiseHeightmap(
            seed = PEAK_SEED xor salt,
            firstOctave = -6,
            amplitudes = listOf(1.0, 0.6, 0.3),
            scaleX = WIND_STRETCH,
            scaleZ = 1.5,
            // Higher than the typical crown, so a column the wind spares stands far above its neighbours.
            baseY = DECK_Y + TYPICAL_CROWN,
            relief = PEAK_HEIGHT / 2.0,
            flatY = DECK_Y,
        )
        // Ragged underside: the noisy surface sits *below* the deck, so the same primitive hangs.
        val spikes = NoiseHeightmap(
            seed = SPIKE_SEED xor salt,
            firstOctave = -5,
            amplitudes = listOf(1.0, 0.7),
            scaleX = WIND_STRETCH,
            scaleZ = 1.5,
            baseY = DECK_Y - SPIKE_LENGTH / 2,
            relief = SPIKE_LENGTH / 2.0,
            flatY = DECK_Y,
        )
        // The envelope the mass is squeezed into: overlapping lobes rather than one lens, since a single
        // ellipsoid reads as a circle the moment you see it. Thickness does the important work — erosion
        // knows nothing of where islands are, so a thick middle is what lets spires stand.
        val envelope = Union(
            LOBES.map { lobe ->
                Ellipsoid(
                    centerX = lobe.offsetX,
                    centerZ = lobe.offsetZ,
                    centerY = (PEAK_CEILING + SPIKE_FLOOR) / 2,
                    radiusXZ = ISLAND_HALF_WIDTH * lobe.spread,
                    radiusY = (PEAK_CEILING - SPIKE_FLOOR) / 2.0 * lobe.spread,
                )
            },
        )
        // Rare, thin, far taller than the crowns: the *potential* for a standout needle. It belongs to the
        // field rather than the wind, because erosion judges a whole column at once and so can keep or
        // remove one but never shorten it — height variation among spires is the field's to provide.
        val talons = NoiseHeightmap(
            seed = TALON_SEED xor salt,
            firstOctave = -2,
            amplitudes = listOf(1.0, 0.4),
            scaleX = TALON_SCALE,
            scaleZ = TALON_SCALE,
            baseY = DECK_Y + TALON_TYPICAL,
            relief = TALL_REACH / 2.0,
            flatY = DECK_Y,
        )
        // The same trick inverted, for the hanging needles beneath.
        val roots = NoiseHeightmap(
            seed = ROOT_SEED xor salt,
            firstOctave = -2,
            amplitudes = listOf(1.0, 0.4),
            scaleX = TALON_SCALE,
            scaleZ = TALON_SCALE,
            baseY = DECK_Y - TALON_TYPICAL,
            relief = TALL_REACH / 2.0,
            flatY = DECK_Y,
        )
        // The connective body: without it the two noisy surfaces meet wherever they happen to and an island
        // can thin to a single block, which is useless to stand on. A slab sets a floor under the thickness.
        val deck = Slab(lowY = DECK_Y - DECK_HALF_THICKNESS, highY = DECK_Y + DECK_HALF_THICKNESS)

        // Extra material heaped over the middle, so the grandest spires are inland. Upward only, so the
        // undersides are unchanged — this raises how much rock is available to carve, nothing more.
        val rise = Cone(
            baseX = 0,
            baseZ = 0,
            baseRadius = ISLAND_HALF_WIDTH * CENTRAL_SHARE,
            baseY = DECK_Y,
            tipY = DECK_Y + CENTRAL_RISE,
        )
        val island = Intersect(listOf(envelope, Union(listOf(deck, peaks, spikes, talons, roots, rise))))

        return Instanced(
            templates = listOf(island),
            placement = Grid(spacing = ISLAND_SPACING, jitter = ISLAND_JITTER, density = Density.uniform(ISLAND_DENSITY)),
            variation = ISLAND_VARIATION,
            seed = ARCHIPELAGO_SEED xor salt,
        )
    }

    /**
     * Where an island's body sits, centring a typical island between the two cloud decks the Spire's sky
     * draws — and pinned by two hard limits rather than chosen by taste. The deck has to leave room over it
     * for the tallest spire and under it for the deepest root, and at full size and full drop
     * ([ISLAND_VARIATION]) a root could reach 130 blocks down.
     *
     * Measured through the weathering at this height (`./gradlew :common:preview --args=spire`): rock
     * stands between y=71 and y=310, so the spires clear the world's ceiling of 319 by nine blocks and
     * nothing comes near [SEA_LEVEL]. Column tops run to a median of 165 and a ninetieth percentile of 183,
     * which is what puts them just under [co.voik.agesandtheart.sky.SpireSky.UPPER_DECK_HEIGHT] with only
     * three per cent breaking through.
     */
    internal const val DECK_Y = 156
    private const val PEAK_HEIGHT = 148
    private const val TYPICAL_CROWN = 45

    // How far the rare needles may reach past the deck, up and down alike, and where they usually sit.
    // Keeping the usual well short of [PEAK_HEIGHT] is what makes a tall one exceptional rather than normal.
    private const val TALL_REACH = 144
    private const val TALON_TYPICAL = 21
    // Fine, so a needle is a few blocks across rather than a hill.
    private const val TALON_SCALE = 0.75
    // **Not half of [PEAK_HEIGHT].** Erosion keeps or removes a whole column but never shortens one, so how
    // far the undersides reach is the field's alone and this is measured rather than derived — the ragged
    // half of an island is shorter than its crown, but not by the half a symmetric figure would give it.
    private const val SPIKE_LENGTH = 80
    internal const val PEAK_CEILING = DECK_Y + PEAK_HEIGHT
    internal const val SPIKE_FLOOR = DECK_Y - SPIKE_LENGTH

    private const val ISLAND_HALF_WIDTH = 84

    /** One swelling of an island's envelope: how far off centre it sits, and how big a share it takes. */
    private class Lobe(val offsetX: Int, val offsetZ: Int, val spread: Double)

    // Hand-placed rather than scattered: an island is one reused template, so the lumps only need to be
    // *a* pleasing irregular outline, not a different one each time.
    private val LOBES = listOf(
        Lobe(0, 0, 1.00),
        Lobe(-41, 23, 0.80),
        Lobe(35, -32, 0.74),
        Lobe(17, 41, 0.68),
        Lobe(-29, -38, 0.64),
        Lobe(47, 20, 0.56),
        Lobe(-12, 50, 0.50),
    )
    /**
     * How the big islands are laid out. Spacing is set so a neighbour is visible from the starter island
     * at a default 12-chunk view, and **jitter is pushed most of the way to half the spacing, with islands
     * merging wanted rather than prevented** — a union is what an island is made of anyway.
     *
     * What jitter cannot buy is *clumping*: one per cell still holds, so there are no empty quarters and
     * no knots of five. `Scatter` is the next thing to try if this still reads as laid out.
     */
    private const val ISLAND_SPACING = 250.0
    private const val ISLAND_JITTER = 105.0

    // Kept below 1.0 so the archipelago has holes in it rather than being a grid with the corners knocked
    // off. Low enough to matter, high enough that a missing cell is not a missing region.
    private const val ISLAND_DENSITY = 0.85

    /**
     * The small islands: how wide, how thick, and how thickly sown.
     *
     * Spaced far tighter than the big ones and drawn much smaller, so they read as the *material* the
     * archipelago is suspended in rather than as more islands. Density is well under one because these
     * are the layer that would look like a lattice fastest if every cell were filled.
     */
    private const val SMALL_HALF_WIDTH = 17.0
    private const val SMALL_RADIUS_Y = 9.0
    private const val SMALL_SPACING = 78.0
    private const val SMALL_JITTER = 36.0
    private const val SMALL_DENSITY = 0.75

    // The three aspects, as shares of the figures above: a flattened lens, a rounder lump, a narrow shard.
    private const val LENS_FLATTEN = 0.6
    private const val LUMP_SHARE = 0.8
    private const val SHARD_SHARE = 0.55
    private const val SHARD_STRETCH = 1.4

    /**
     * Feature size within a blob, and **not in blocks**: the scale divides world coordinates *before* the
     * octave frequency applies, so a feature is `2^-firstOctave × scale` blocks — about 22 here. Written
     * as 26 it gives some 830 blocks, wider than an island, so every blob sees one near-constant value and
     * is kept or deleted **whole**. Several features must fit across an island for the noise to *shape* it
     * rather than merely select it, so this is tied to [SMALL_HALF_WIDTH] and must move with it.
     *
     * [BLOB_SQUASH] is below one so vertical detail is finer than horizontal, stratifying the lumps into
     * flattish stacked masses rather than columns.
     */
    private const val BLOB_SCALE = 0.32
    private const val BLOB_SQUASH = 0.6

    /**
     * How much of the envelope survives — the dial deciding whether these read as islands or ellipsoids.
     * A bit under half: higher shatters a blob into floating scraps, lower and the noise goes back to
     * merely selecting.
     *
     * `:common:noiseprofile` tabulates fill against threshold **for non-negative values only**, and plain
     * noise is symmetric about zero, so a negative threshold `-t` keeps `1 - fill(t)`. Read the table, then
     * remember which side of zero you are on.
     */
    private const val BLOB_THRESHOLD = 0.05

    /**
     * How the small islands differ from one another — and the reason they are their own layer. Sizes reach
     * far lower than the big islands' and lifts range across most of the gap between the cloud decks,
     * which puts one at eye level and the next far above or below. The big islands cannot have this: they
     * are massive enough that hanging them away from their own deck puts them through a cloud layer.
     */
    private val SMALL_VARIATION = Variation(
        yawSteps = 1,
        minScale = 0.45,
        maxScale = 1.15,
        scaleSteps = 6,
        pivotY = DECK_Y,
        minLift = -40,
        maxLift = 60,
        liftSteps = 10,
    )

    // Derived rather than written down, because a band that failed to cover the envelopes would quietly
    // flatten every blob that strayed outside it: outside the band Noise3D is empty, and empty in an
    // Intersect means the island simply is not there. Tallest template, largest size, furthest lift.
    private val SMALL_BAND_REACH = (SMALL_RADIUS_Y * SHARD_STRETCH * SMALL_VARIATION.maxScale).roundToInt()
    private val SMALL_BAND_LOW = DECK_Y + SMALL_VARIATION.minLift - SMALL_BAND_REACH
    private val SMALL_BAND_HIGH = DECK_Y + SMALL_VARIATION.maxLift + SMALL_BAND_REACH

    /**
     * How islands differ from one another: how big, and how high they hang. Both serve one picture — an
     * archipelago sitting *between* the cloud decks, with the occasional island hung low enough that its
     * roots trail beneath the lower one.
     *
     * **Set against the *carved* shape rather than the field's**, which is the trap: erosion trims maybe
     * thirty blocks off an underside and almost nothing off a crown, so centring the raw field leaves
     * every island too high. `./gradlew :common:preview --args=spire` renders through the same weathering.
     *
     * No yaw: a lumpy mass reads the same turned, so rotating copies would only cost the staircase
     * aliasing [Variation] warns about.
     */
    private val ISLAND_VARIATION = Variation(
        yawSteps = 1,
        // More steps than a geometric shape would want, and free per chunk: a column samples whichever
        // size its instance drew. Organic shapes take them well, since resizing a NoiseHeightmap scales
        // its *wavelength* — a larger island is a different island rather than a magnified one.
        minScale = 0.8,
        maxScale = 1.25,
        scaleSteps = 8,
        // Copies grow about their own deck, so a bigger island gets taller *and* deeper rather than
        // sinking, which keeps the family centred in the band between the decks.
        pivotY = DECK_Y,
        // Asymmetric on purpose: erosion hollows undersides and spares caps, so hanging copies *down* is
        // the only way to get roots beneath the lower cloud deck. Lifting up risks the world ceiling.
        minLift = -30,
        maxLift = 15,
        liftSteps = 6,
    )

    // Ridges comb down the X axis, matching the wind direction `Weathering` works along.
    private const val WIND_STRETCH = 6.0

    /**
     * The Spire's waterline, far under the 63 every landform standing *on* the ground shares. This one
     * hangs in open air and its sea is only the floor of the world it hangs over, so it sits at the bottom
     * of the band and leaves the whole of the rest to the archipelago — which is what lets the islands
     * float clear of the water rather than resting on it.
     */
    internal const val SEA_LEVEL = -1
    private const val PEAK_SEED = 0x51DE_1L
    private const val SPIKE_SEED = 0x5B1CEL
    // A floor under the connective body's thickness. A gameplay figure — room to stand and build — so it
    // is deliberately *not* scaled with the rest of the island.
    private const val DECK_HALF_THICKNESS = 4

    // How much of the island the central heap covers, and how far it lifts the middle. Only 15% narrower
    // than the envelope's radius, so its flank runs almost the whole way out and the island grades toward
    // its middle rather than stepping up to it; much narrower reads as a spike planted on a flat lens.
    //
    // The rise reaches past [PEAK_CEILING] and is clipped by the envelope, which is deliberate: a cone cut
    // near an ellipsoid's own top comes back domed rather than pointed, leaving the wind to make the points.
    private const val CENTRAL_SHARE = 0.85
    private const val CENTRAL_RISE = 200

    private const val TALON_SEED = 0x7A10_11L
    private const val ROOT_SEED = 0x200_75L
    private const val ARCHIPELAGO_SEED = 0xA2C41DL

    // The small islands' own layout and their own noise. Distinct from ARCHIPELAGO_SEED so the two
    // populations share no structure — a shoal that echoed the big islands' lattice would undo the point.
    private const val SHOAL_SEED = 0x5C04A1L
    private const val BLOB_SEED = 0xB10B5L
}
