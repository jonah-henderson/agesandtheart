package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Cone
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Isle
import co.voik.agesandtheart.worldgen.field.Scatter
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.block.Blocks
import kotlin.math.max

/**
 * Islands in an endless sea: one where the writer arrives, and the rest a voyage away.
 *
 * **Deliberately not a continent**, which is the constraint the whole preset is built around — see [Isle]
 * for why an island being an *object* with a radius rather than a threshold on a noise field is what
 * settles that. What this file adds is the part a shape cannot enforce for itself: keeping them far enough
 * apart that two never merge into one landmass. [leastApart] is that guarantee, and `IslandsCheck` asserts
 * the arithmetic rather than trusting it.
 */
object IslandsField {

    /**
     * How big the islands are, as a writer says it — see `Terrain.EXTENT`.
     *
     * **Low against their width on purpose.** These are islands rather than sea mountains: a broad one
     * stands about fifty blocks over its own beach across a kilometre of ground, so walking it is a walk
     * rather than a climb, and the coast stays the thing you notice about it.
     */
    enum class Extent(val key: String, val shoreRadius: Double, val peakRise: Double) {
        /** A day's walk across, and the whole of it in sight from the middle. */
        MODEST("modest", shoreRadius = 200.0, peakRise = 28.0),

        /** Room for a country on it, and a coast you cannot see the far side of. */
        BROAD("broad", shoreRadius = 520.0, peakRise = 48.0),

        /** As far as this goes before it stops being an island and starts being somewhere. */
        VAST("vast", shoreRadius = 1100.0, peakRise = 70.0),
        ;

        companion object {
            fun named(key: String): Extent = entries.firstOrNull { it.key == key } ?: MODEST
        }
    }

    fun world(extent: String, salt: Long = 0L): TerrainField {
        val size = Extent.named(extent)
        return Isle(
            floorY = WORLD_FLOOR,
            seabedY = SEABED_Y,
            shoreY = SEA_LEVEL,
            peakRise = size.peakRise,
            shoreRadius = size.shoreRadius,
            radiusVariation = RADIUS_VARIATION,
            spacing = spacingFor(size),
            jitter = JITTER,
            seed = ISLAND_SEED xor salt,
        )
    }

    /**
     * The same idea **composed from the toolkit** rather than written as a node — Jonah's construction, and
     * the pair to [world] for judging which shape reads better.
     *
     * Three things do the work, none of them new:
     *
     * - `Scatter` puts up to two lobes in each **lobe-sized** cell, so neighbours overlap and merge;
     * - an **empty template** in the list is the per-instance chance — `Instanced` draws a template for
     *   every instance, so a slot that draws the empty one is simply skipped;
     * - a **patchy** density leaves whole regions with no lobes at all, which is the open ocean.
     *
     * And [Instanced.blend] eases the joins, so overlapping lobes come out as one irregular mass rather
     * than as cones sharing a wall.
     *
     * **The cell has to be the size of a lobe, not of an island.** `Scatter` spreads its instances evenly
     * across a cell rather than gathering them, so island-sized cells put every lobe hundreds of blocks
     * from the next and nothing ever merges — the first attempt at this produced an empty sea.
     *
     * **Kept as evidence rather than as a preset.** It is not wired to any Age; `Terrain.ISLANDS` uses
     * [world]. What it demonstrates is the three mechanisms above working, and what it settles is why they
     * are not enough on their own.
     *
     * **Instancing composes *objects*; it cannot compose *parts* of one.** Every copy of a template is
     * identical bar an affine pose — [Instanced] says as much — and for a scattered field of separate
     * things that is invisible, because nobody compares two pyramids. Here the copies *merge*, so the
     * composite is what you look at, and their sameness is the first thing you see: cones give a heap of
     * equal hills, and cones capped flat give a sheet of discs all at one height. Neither reads as an
     * island. [Isle] avoids it by drawing a different radius per cell from a hash — which is precisely the
     * per-instance reshaping instancing does not do.
     *
     * Two further differences worth having written down. The obvious one is the coastline: a template is
     * asked in its own local frame, so anything noisy inside it is identical on every copy. The deeper one
     * is that an island here stops being a *bounded object* — its extent is however far the density noise
     * stays high, so the size guarantee `IslandsCheck` makes about [world] cannot be made about this.
     * What bounds it is [PATCH_SCALE], softly.
     */
    fun clustered(extent: String, salt: Long = 0L): TerrainField {
        val size = Extent.named(extent)
        val lobeRadius = size.shoreRadius * LOBE_SHARE_OF_AN_ISLAND
        val lobe = Cone(
            baseX = 0,
            baseZ = 0,
            baseRadius = lobeRadius,
            baseY = SEABED_Y,
            tipY = SEA_LEVEL + size.peakRise.toInt(),
        )
        return Union(
            listOf(
                Slab(lowY = WORLD_FLOOR, highY = SEABED_Y),
                Instanced(
                    // Two draws in three place a lobe; the third is the chance, rolled per instance.
                    templates = listOf(lobe, lobe, Union(emptyList())),
                    placement = Scatter(
                        cellSize = lobeRadius * CELLS_PER_LOBE,
                        leastPerCell = 0,
                        mostPerCell = LOBES_PER_CELL,
                        density = Density.patchy(
                            probability = LOBE_CHANCE,
                            patchiness = PATCHINESS,
                            patchScale = PATCH_SCALE,
                            seed = ISLAND_SEED xor salt,
                        ),
                    ),
                    variation = Variation.NONE,
                    seed = ISLAND_SEED xor salt,
                    blend = size.peakRise * BLEND_SHARE_OF_A_RISE,
                ),
            ),
        )
    }

    fun generator(biomeSource: BiomeSource): AgeChunkGenerator =
        AgeChunkGenerator(
            biomeSource,
            world(extent = Extent.BROAD.key),
            SeaFill.of(Blocks.WATER.defaultBlockState(), level = SEA_LEVEL),
            Palette.BARE_ROCK,
        )

    /**
     * How far apart to lay islands of this size.
     *
     * The floor is what makes the sea a voyage; the multiple of the radius is what stops two of the biggest
     * ones touching. Both are needed: a fixed spacing large enough for a `vast` island would put a `modest`
     * one an absurd distance from its neighbour, and a multiple alone would put small ones in sight of each
     * other.
     */
    fun spacingFor(extent: Extent): Double = max(LEAST_SPACING, extent.shoreRadius * LEAST_APART)

    /**
     * The furthest an island of this size can reach from its centre — its radius at its largest draw, with
     * the coast wandering as far out as it goes. What [spacingFor] has to beat twice over.
     */
    fun widestReach(extent: Extent): Double =
        extent.shoreRadius * (1.0 + RADIUS_VARIATION) * (1.0 + Isle.DEFAULT_COAST_ROUGHNESS)

    /**
     * How far the *shape* reaches, shelf and all — further than [widestReach], which is about land.
     *
     * The two are different questions. Land touching is what would make a continent, and that is what the
     * spacing has to beat; shelves touching is two islands sharing shallows, which is fine and rather
     * good. This one exists so a check looking for open seabed knows where to start.
     */
    fun shelfReach(extent: Extent): Double =
        widestReach(extent) + (SEA_LEVEL - SEABED_Y) / Isle.DEFAULT_SHELF_SLOPE

    /**
     * The closest two neighbouring islands' centres can come, both jittered towards each other.
     *
     * `cellHash` runs −0.5..0.5, so a jitter of *j* moves a centre by half of `j * spacing` either way and
     * a pair can close by `j * spacing` in total — not twice that.
     */
    fun leastApart(extent: Extent): Double = spacingFor(extent) * (1.0 - JITTER)

    private const val WORLD_FLOOR = -64

    /** Deep enough that the sea between islands reads as ocean rather than as a flooded plain. */
    const val SEABED_Y = 28

    /** The convention every shape wanting a sea keeps to. */
    const val SEA_LEVEL = 63

    /** What share of its radius one island differs from the next by. */
    private const val RADIUS_VARIATION = 0.35

    /**
     * How far an island stands off its lattice point. **Small against the spacing on purpose** — jitter is
     * what stops the archipelago reading as a grid, and it is also the one thing that can bring two of them
     * close enough to merge.
     */
    private const val JITTER = 0.2

    /** The least an island may be from its neighbour, whatever its size. A voyage, not a swim. */
    private const val LEAST_SPACING = 5200.0

    /** And in radii, so the biggest ones cannot touch however the draw falls. */
    private const val LEAST_APART = 8.0

    /** How big one lobe is against the island it belongs to. Under half, so a cluster is plainly several. */
    private const val LOBE_SHARE_OF_AN_ISLAND = 0.55

    /** How big a scatter cell is against a lobe. Near one, so neighbouring cells' lobes overlap. */
    private const val CELLS_PER_LOBE = 0.8

    /** The most lobes one cell draws. */
    private const val LOBES_PER_CELL = 2

    /** How often a drawn slot is kept, before the empty template takes its own third. */
    private const val LOBE_CHANCE = 0.55

    /**
     * How hard the density swings. **Past what a probability can hold on its own** — the clamp is doing
     * the work, so a crowded region draws every slot it is offered and an empty one draws none, rather
     * than both being a middling sprinkle.
     */
    private const val PATCHINESS = 0.75

    /** How wide one crowded or empty region runs. **This is what bounds an island's size here.** */
    private const val PATCH_SCALE = 130.0

    /**
     * How far a join between two lobes is eased, against how tall a lobe stands.
     *
     * **Against the rise, not the radius** — a blend is a *vertical* distance, and easing by a fraction of
     * the horizontal radius put a hundred and fourteen blocks of it into cones a hundred and thirty tall.
     * The bulge is a quarter of the width, so every overlap came out as a flat lens sitting thirty blocks
     * proud of both lobes.
     */
    private const val BLEND_SHARE_OF_A_RISE = 0.12

    private const val ISLAND_SEED = 0x15_1A_2DL
}
