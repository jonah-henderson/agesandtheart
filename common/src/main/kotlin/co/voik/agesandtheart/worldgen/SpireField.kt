package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.AmbientMedium
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Cone
import co.voik.agesandtheart.worldgen.field.Ellipsoid
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
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

    fun world(): TerrainField {
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
    private const val ISLAND_SPACING = 330.0
    private const val ISLAND_JITTER = 60.0
    private const val ISLAND_DENSITY = 0.75

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
        minScale = 0.85,
        maxScale = 1.15,
        scaleSteps = 4,
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
}
