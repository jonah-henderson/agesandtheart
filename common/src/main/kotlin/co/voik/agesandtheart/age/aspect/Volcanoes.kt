package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.reward.Decoration
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.feature.FeatureDensity
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration
import net.minecraft.world.level.levelgen.placement.CountPlacement
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement
import net.minecraft.world.level.levelgen.placement.InSquarePlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.placement.RarityFilter
import net.minecraft.world.level.levelgen.placement.SurfaceRelativeThresholdFilter
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest

/**
 * Whether an Age has volcanoes in it (design §7.1.2).
 *
 * **One fact in the recipe, read by four things.** The terrain raises the cones from it, the caldera
 * feature lays the big vent in each crater, this lays the smaller ones through the rest of the rock, and
 * the danger evaluator scores it — so a writer who asks for volcanoes gets all of them, and none can
 * disagree about whether the Age has any.
 *
 * It rides the features pool rather than being an aspect of its own: what a volcano *is* to a recipe is a
 * thing that gets placed, and the pool already carries a claim's confinement and rungs.
 */
object Volcanoes {

    /** The placed feature a writer names, and the id everything else keys on. */
    val ID: Identifier = "volcano".location()

    /**
     * The claim asking for volcanoes, or null for an Age that asks for none.
     *
     * Read off the claim rather than the placed feature registry, so it answers the same before an Age is
     * opened as after — which is what lets the desk survey and `/age danger score` ask it of a recipe that
     * has never been built.
     */
    private fun claimIn(composition: AgeComposition): Claim? =
        composition.optionsFor(Aspect.FEATURES, 0).claimsOn(Features.PLACES)
            .firstOrNull { claim -> Identifier.tryParse(claim.value) == ID }

    /** Whether this composition asks for volcanoes — the terrain's question, which has no amount in it. */
    fun askedFor(composition: AgeComposition): Boolean = claimIn(composition) != null

    /**
     * The Age's rock, seeded with lava tubes away from the calderas (Jonah, 2026-09-06).
     *
     * **A caldera is where the most of them are, not the only place they are.** The clusters here are
     * buried, so they do nothing at all until something opens the rock over them — a vent with stone
     * overhead is plugged by the same rule that lets a player silence one deliberately. What that buys is
     * a deposit worth going to look for: mine into one and it starts welling lava into the space you just
     * made, and a big enough one starts throwing at you for it. Undersea and deep underground come free,
     * an ore pass replacing stone wherever the Age put stone.
     *
     * Two sizes, because the interesting question is whether the one you found will throw. The seams are
     * under [co.voik.agesandtheart.content.LavaTubes] sixteen-block threshold and only seep; the nests
     * straddle it.
     *
     * **Both read the claim's own amount**, which is the same number [Features] applies to the `volcano`
     * placed feature — so a quantifier moves the buried clusters and the vents together rather than half
     * of what a writer asked for. Naming the feature at all is worth more than [Rung.ORDINARY], so an Age
     * that merely says `volcano` already gets a multiple of the counts below.
     */
    fun layer(composition: AgeComposition): Decoration.Layer? {
        val asked = claimIn(composition) ?: return null
        return Decoration.layerOf(
            GenerationStep.Decoration.UNDERGROUND_ORES,
            listOf(
                FeatureDensity.applied(clustersOf(SEAM_SIZE, SEAMS_PER_CHUNK), asked.density),
                FeatureDensity.applied(clustersOf(NEST_SIZE, ONE, NEST_RARITY), asked.density),
            ),
        )
    }

    /**
     * One ore pass laying tubes into whatever the Age calls stone.
     *
     * The whole height of the world, like the raw-temperstone blobs: an ore feature is a no-op wherever it
     * finds no stone, so the range costs a handful of misses rather than a rule about where rock is.
     *
     * **Except that it stays under the surface, which is the whole of what keeps a cluster safe to find.**
     * A cluster is enclosed by the rock it is buried in, so opening one floods the space you opened; one
     * that surfaced on a hillside had nothing round it at all and welled a disc of lava standing proud of
     * the land. A cave breaks nothing here — a heightmap is the top of the terrain, so a cluster under it
     * can still be in a cavern wall, which is where they are meant to be found.
     */
    private fun clustersOf(size: Int, perChunk: Int, rarity: Int = EVERY_CHUNK): Holder<PlacedFeature> {
        val tube = AgeContent.LAVA_TUBE_BLOCK.defaultBlockState()
        val targets = listOf(
            OreConfiguration.target(TagMatchTest(BlockTags.BASE_STONE_OVERWORLD), tube),
            OreConfiguration.target(TagMatchTest(BlockTags.BASE_STONE_NETHER), tube),
        )
        val spread = buildList {
            if (rarity > EVERY_CHUNK) add(RarityFilter.onAverageOnceEvery(rarity))
            add(CountPlacement.of(perChunk))
            add(InSquarePlacement.spread())
            add(HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.top()))
            add(SurfaceRelativeThresholdFilter.of(Heightmap.Types.OCEAN_FLOOR_WG, ANY_DEPTH, -BURIED_UNDER))
        }
        return Holder.direct(
            PlacedFeature(Holder.direct(ConfiguredFeature(Feature.ORE, OreConfiguration(targets, size))), spread),
        )
    }


    /**
     * Common, and big enough that opening one floods what you opened — a seam is a hazard you walk into
     * rather than a warm patch you notice (Jonah, 2026-09-09).
     *
     * Still under [co.voik.agesandtheart.content.LavaTubes]' sixteen-block throwing threshold, so a seam
     * never shells you; what it buys at this size is reach, which is the vent's own mass squared, so
     * twelve wells about thirteen blocks of pool where seven wells four.
     */
    private const val SEAM_SIZE = 12
    private const val SEAMS_PER_CHUNK = 7

    /** Large enough to straddle the throwing threshold, so some of them are a real find. */
    private const val NEST_SIZE = 30
    private const val NEST_RARITY = 4

    /** Enough rock over the deepest blob of a cluster that nothing of it reaches open sky. */
    private const val BURIED_UNDER = 8

    /** No floor: a cluster may be as far under the surface as the world goes. */
    private const val ANY_DEPTH = -4096

    private const val EVERY_CHUNK = 1
    private const val ONE = 1
}
