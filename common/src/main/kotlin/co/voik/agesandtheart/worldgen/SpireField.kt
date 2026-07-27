package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.AmbientMedium
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Cone
import co.voik.agesandtheart.worldgen.field.Ellipsoid
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import net.minecraft.core.HolderSet
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver
import kotlin.math.roundToInt

/**
 * The Spire archipelago, rebuilt: **blocky masses pared back by erosion** rather than assembled from
 * ellipsoids and cones.
 *
 * Each island is a cluster of overlapping [Ellipsoid] lobes — thick at the middle, thinning to a lumpy,
 * not-quite-circular rim. Four
 * [NoiseHeightmap]s then cut it to a profile: one standing, giving a rolling peaked top; one hanging,
 * giving a ragged underside of downward spikes. Both are clipped to the box's footprint, so an island
 * keeps sheer rectangular flanks where it was cut — character the ellipsoid version could never have.
 *
 * Both noise fields are stretched along the wind axis, so the relief combs into long parallel ridges
 * rather than isotropic lumps — the streamlined signature of wind-carved rock.
 *
 * The last pass belongs to [co.voik.agesandtheart.worldgen.carver.ErosionCarver], which asks of each
 * block whether the wind there beats the rock's resistance, hollowing the undersides while sparing the
 * caps. That is the part neither noise nor CSG reaches: they cannot undercut.
 *
 * The division of labour is deliberate. The field does the *gross* shape, because `getBaseHeight`
 * answers from the field and decoration would otherwise place in mid-air; the carver only takes a
 * modest fraction back out.
 */
object SpireField {

    /**
     * The archipelago is **two populations, not one** (2026-07-27): a sparse scatter of the big carved
     * islands above, and a much denser shoal of small noise blobs threaded between them.
     *
     * They are separate layers rather than two templates in one [Instanced] for two reasons the class
     * cannot get around: it picks uniformly across `templates × sizes`, so one layer could not make the
     * small ones commoner than the big ones; and it carries **one** [Variation], so one layer could not
     * give the small ones the wide vertical freedom that is the whole point of them while keeping the big
     * ones near their deck. Two layers unioned costs nothing — a [Union] is what an island already is.
     */
    fun world(): TerrainField = Union(listOf(bigIslands(), smallIslands()))

    /**
     * The small islands: simple envelopes cut out of one continuous field of three-dimensional noise.
     *
     * **The noise is hoisted above the instancer, and it has to be.** A template is queried in its own
     * local coordinates, so a [Noise3D] used *as* a template would give every copy the identical form —
     * `Intersect(Instanced(envelopes), noise)` instead reads world coordinates, so each blob is cut from a
     * different region of one field: all different, and agreeing with each other wherever two overlap.
     * [Noise3D]'s own documentation makes the argument; this is the first thing to use it.
     *
     * It is also why this is affordable. [Intersect] asks its cheapest child first and stops the moment
     * nothing is solid, and an [Ellipsoid] is analytic while the noise costs a sample per block of its
     * band — so the band is walked only on columns where an envelope already stands, and not at all in
     * the open sky between them.
     */
    private fun smallIslands(): TerrainField {
        // Three aspects rather than one, because scaling alone only ever makes the same island bigger: a
        // flat lens, a rounder lump, and a narrow shard that stands taller than it is wide.
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
                    seed = SHOAL_SEED,
                ),
                Noise3D(
                    seed = BLOB_SEED,
                    firstOctave = -5,
                    // A third octave the caves do not have: it is the fine one, and fine detail is what
                    // roughens an edge that would otherwise follow the envelope's arc smoothly.
                    amplitudes = listOf(1.0, 0.5, 0.25),
                    scaleX = BLOB_SCALE,
                    // Squashed, so a blob breaks up into stacked flattish masses rather than vertical
                    // shafts — these are meant to read as islands, not as columns.
                    scaleY = BLOB_SCALE * BLOB_SQUASH,
                    scaleZ = BLOB_SCALE,
                    // PLAIN, not BILLOWY: billowy picks out the noise's extremes and most of a normal
                    // field sits near the middle, so it would leave these as thin scraps. Plain noise
                    // thresholded a little below zero keeps most of the envelope and takes bites out of it.
                    character = NoiseCharacter.PLAIN,
                    threshold = BLOB_THRESHOLD,
                    lowY = SMALL_BAND_LOW,
                    highY = SMALL_BAND_HIGH,
                ),
            ),
        )
    }

    /** The big carved islands — the original archipelago, unchanged in shape and only laid out differently. */
    private fun bigIslands(): TerrainField {
        // Rolling top: the noisy surface sits above the island's deck, so rock fills upward to it.
        val peaks = NoiseHeightmap(
            seed = PEAK_SEED,
            firstOctave = -6,
            amplitudes = listOf(1.0, 0.6, 0.3),
            scaleX = WIND_STRETCH,
            scaleZ = 1.5,
            // Reaching much higher than the typical crown, so the rare column the wind spares stands far
            // above its neighbours instead of level with them.
            baseY = DECK_Y + TYPICAL_CROWN,
            relief = PEAK_HEIGHT / 2.0,
            flatY = DECK_Y,
        )
        // Ragged underside: the noisy surface sits *below* the deck, so the same primitive hangs.
        val spikes = NoiseHeightmap(
            seed = SPIKE_SEED,
            firstOctave = -5,
            amplitudes = listOf(1.0, 0.7),
            scaleX = WIND_STRETCH,
            scaleZ = 1.5,
            baseY = DECK_Y - SPIKE_LENGTH / 2,
            relief = SPIKE_LENGTH / 2.0,
            flatY = DECK_Y,
        )
        // The envelope the mass is squeezed into: several overlapping lobes rather than one lens.
        //
        // A single ellipsoid reads as a circle the moment you see it, and clipping it with the box traded
        // that for flat faces. Overlapping lobes give an outline that is round in character without being
        // recognisably round, and they need no new primitive — a union of ellipsoids is exactly the sort of
        // thing the toolkit is for. Dropping the box removes the flat faces at source.
        //
        // Thickness still does the important work: erosion knows nothing of where islands are, so a thick
        // middle is what lets spires stand and a thinning rim is what keeps the edges low.
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
        // Rare, thin, and far taller than the crowns: the *potential* for a standout needle.
        //
        // It has to live here rather than in the carver, and that is worth understanding. Erosion judges a
        // whole column at once, so it can keep a column or remove it but never shorten one — every survivor
        // stands at whatever height the field gave it. Height variation among spires is therefore the
        // field's to provide. A fine scale keeps these a few blocks across; the wind then removes almost
        // all of them, and the handful it spares tower over everything around.
        val talons = NoiseHeightmap(
            seed = TALON_SEED,
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
            seed = ROOT_SEED,
            firstOctave = -2,
            amplitudes = listOf(1.0, 0.4),
            scaleX = TALON_SCALE,
            scaleZ = TALON_SCALE,
            baseY = DECK_Y - TALON_TYPICAL,
            relief = TALL_REACH / 2.0,
            flatY = DECK_Y,
        )
        // The connective body. Without it the two noisy surfaces meet wherever they happen to, and an
        // island can thin to a single block between its top and its underside — fine to look at, useless to
        // stand on or to build into. A slab through the deck sets a floor under that thickness.
        val deck = Slab(lowY = DECK_Y - DECK_HALF_THICKNESS, highY = DECK_Y + DECK_HALF_THICKNESS)

        // Extra material heaped over the middle, so the grandest spires are inland rather than scattered
        // evenly. A cone because the wind, not the shape, is what makes spires — this only raises how much
        // rock is available to be carved there. Deliberately upward only: the undersides stay as they were.
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
            seed = ARCHIPELAGO_SEED,
        )
    }

    fun generator(
        biomeSource: BiomeSource,
        carvers: Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>> = emptyMap(),
    ): FieldChunkGenerator =
        FieldChunkGenerator(
            biomeSource,
            world(),
            AmbientMedium.sea(Blocks.WATER.defaultBlockState(), level = SEA_LEVEL),
            Palette.BARE_ROCK,
            carvers,
        )

    // Where an island's body sits. Chosen against the sky rather than the ground: it centres a typical
    // island in the band between the two cloud decks (see AgeCloudRenderer), so most of the archipelago
    // lives between them and only the large or low-hung copies cross either one.
    private const val DECK_Y = 190
    private const val PEAK_HEIGHT = 126
    private const val TYPICAL_CROWN = 45

    // How far the rare needles may reach past the deck, up and down alike, and where they usually sit.
    // Keeping the usual well short of [PEAK_HEIGHT] is what makes a tall one exceptional rather than normal.
    private const val TALL_REACH = 144
    private const val TALON_TYPICAL = 21
    // Fine, so a needle is a few blocks across rather than a hill.
    private const val TALON_SCALE = 0.75
    private const val SPIKE_LENGTH = 126
    private const val PEAK_CEILING = DECK_Y + PEAK_HEIGHT
    private const val SPIKE_FLOOR = DECK_Y - SPIKE_LENGTH

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
     * How the big islands are laid out.
     *
     * **History, because both moves matter and the second reverses a constraint the first invented.**
     * At the original 330/60/0.75 no second island was visible from the starter one — its near edge stood
     * 246 blocks off, past a default 12-chunk (192-block) view — so the picture this preset exists for
     * could not be seen at all without raising render distance. Spacing came down to 250, and jitter with
     * it, to keep worst-case neighbours from merging: `spacing - 2 * jitter >= 2 * ISLAND_HALF_WIDTH *
     * maxScale`, which is 210.
     *
     * **That constraint is now deliberately abandoned.** The tightened lattice read as *too regular*, and
     * the reason is that a grid randomises where an island sits but not how many there are, so a rhythm
     * survives however hard it is jittered. Jitter is therefore pushed most of the way to half the
     * spacing, and **islands merging into one larger irregular mass is wanted, not prevented** — a union
     * is what an island is made of anyway, so a merged pair is simply a bigger island.
     *
     * What jitter cannot buy is *clumping*: one per cell still holds, so there are no empty quarters and
     * no knots of five. `Scatter` exists for that and is the next thing to try here if this still reads
     * as laid out.
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
     * Feature size within a blob — and **not in blocks**, which is the trap here and cost a render to
     * find. The scale divides world coordinates *before* the noise's own octave frequency applies, so
     * what you get is `2^-firstOctave * scale` blocks a feature: at `firstOctave = -5` that is `32 *
     * scale`, making this about 22. `ErodedField` erodes a mass of almost exactly this size and lands on
     * 0.55 for the same reason.
     *
     * Written as 26 (thinking in blocks) it gives a wavelength of some 830 blocks — far wider than an
     * island — so every blob sees one near-constant value and is kept or deleted **whole**, which reads
     * as a handful of smooth intact ellipsoids and a lot of missing ones. Several features have to fit
     * across an island for the noise to shape it rather than merely select it.
     *
     * **So this is tied to [SMALL_HALF_WIDTH] and must move with it.** Halving the envelope without
     * halving this walks straight back into the same failure with fewer features to hide it.
     *
     * [BLOB_SQUASH] is below one so vertical detail is *finer* than horizontal: the lumps stratify into
     * flattish stacked masses rather than standing up as columns.
     */
    private const val BLOB_SCALE = 0.32
    private const val BLOB_SQUASH = 0.6

    /**
     * How much of the envelope survives — **the dial that decides whether these read as islands or as
     * ellipsoids**, and the one that was most wrong.
     *
     * `:common:noiseprofile` tabulates fill against threshold, but only for non-negative values, and
     * plain noise is symmetric about zero: a *negative* threshold `-t` keeps `1 - fill(t)`. So the first
     * draft's −0.18 was not "a little over half" as written but about **72%** — nearly three-quarters of
     * each envelope left intact, which is precisely why the ellipsoid outline kept showing through. Read
     * the table, then remember which side of zero you are on.
     *
     * Slightly positive now, for a bit under half. Higher shatters a blob into scraps floating near each
     * other; lower and the noise stops shaping and goes back to merely selecting.
     */
    private const val BLOB_THRESHOLD = 0.05

    /**
     * How the small islands differ from one another — and the reason they are their own layer.
     *
     * Sizes reach far lower than the big islands' 0.8 and lifts range across most of the gap between the
     * cloud decks (at 145 and 265, with the deck at [DECK_Y] = 190), which is what puts one at eye level
     * and the next one far above or below it. The big islands cannot have this: they are massive enough
     * that hanging them anywhere but near their own deck would put them through a cloud layer.
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
     * How islands differ from one another: how big, and how high they hang.
     *
     * Both dials exist to serve one picture — from a distance you should see an archipelago sitting
     * *between* the cloud decks, with the occasional island large enough or hung low enough that its
     * roots trail out beneath the lower one. That silhouette is the thing worth flying towards, and it
     * falls out of these two numbers rather than needing a special kind of island.
     *
     * The numbers are set against the *carved* shape rather than the field's, which is the trap here:
     * erosion trims perhaps thirty blocks off an island's underside and almost nothing off its crown, so
     * centring the raw field in the band leaves every island floating too high to reach the lower deck.
     * Measure with `./gradlew :common:preview --args=spire`, which renders through the same weathering
     * the world does, and tune against what that reports.
     *
     * No yaw: an island is a lumpy mass whose outline reads the same turned, so rotating copies would
     * cost the staircase aliasing [Variation] warns about and buy nothing.
     */
    private val ISLAND_VARIATION = Variation(
        yawSteps = 1,
        // Wider and finer than a geometric shape would want, and it costs nothing per chunk: a column
        // samples whichever single size its instance drew, so more sizes are paid for once at
        // construction and never again. Organic shapes take the extra steps especially well, because
        // resizing a NoiseHeightmap scales its *wavelength* — so a larger island is a genuinely
        // different island rather than a magnified one.
        minScale = 0.8,
        maxScale = 1.25,
        scaleSteps = 8,
        // Copies grow about their own deck, so a bigger island gets taller *and* deeper rather than
        // sinking — which is what keeps the whole family centred in the band between the decks.
        pivotY = DECK_Y,
        // Asymmetric on purpose. Erosion hollows undersides and spares caps, so an island's carved
        // shape reaches far further above its deck than below it; hanging copies *down* is therefore the
        // only way to get one whose roots trail beneath the lower cloud deck, and that silhouette is the
        // whole point. Lifting up mostly just risks the world ceiling, so it gets a shorter leash.
        minLift = -30,
        maxLift = 15,
        liftSteps = 6,
    )

    // Ridges comb down the X axis, matching the wind direction ErosionCarver works along.
    private const val WIND_STRETCH = 6.0

    private const val SEA_LEVEL = 63
    private const val PEAK_SEED = 0x51DE_1L
    private const val SPIKE_SEED = 0x5B1CEL
    // A floor under the connective body's thickness. A gameplay figure — room to stand and build — so it
    // is deliberately *not* scaled with the rest of the island.
    private const val DECK_HALF_THICKNESS = 4

    // How much of the island the central heap covers, and how far it lifts the middle.
    private const val CENTRAL_SHARE = 0.55
    private const val CENTRAL_RISE = 100

    private const val TALON_SEED = 0x7A10_11L
    private const val ROOT_SEED = 0x200_75L
    private const val ARCHIPELAGO_SEED = 0xA2C41DL

    // The small islands' own layout and their own noise. Distinct from ARCHIPELAGO_SEED so the two
    // populations share no structure — a shoal that echoed the big islands' lattice would undo the point.
    private const val SHOAL_SEED = 0x5C04A1L
    private const val BLOB_SEED = 0xB10B5L
}
