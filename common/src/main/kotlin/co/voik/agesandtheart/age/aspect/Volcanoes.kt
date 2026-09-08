package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.reward.Decoration
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration
import net.minecraft.world.level.levelgen.placement.CountPlacement
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement
import net.minecraft.world.level.levelgen.placement.InSquarePlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.placement.RarityFilter
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
     * Whether this composition asks for volcanoes.
     *
     * Read off the claim rather than the placed feature registry, so it answers the same before an Age is
     * opened as after — which is what lets the desk survey and `/age danger score` ask it of a recipe that
     * has never been built.
     */
    fun askedFor(composition: AgeComposition): Boolean =
        composition.optionsFor(Aspect.FEATURES, 0).claimsOn(Features.PLACES)
            .any { claim -> Identifier.tryParse(claim.value) == ID }

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
     */
    fun layer(composition: AgeComposition): Decoration.Layer? {
        if (!askedFor(composition)) return null
        return Decoration.layerOf(
            GenerationStep.Decoration.UNDERGROUND_ORES,
            listOf(clustersOf(SEAM_SIZE, SEAMS_PER_CHUNK), clustersOf(NEST_SIZE, ONE, NEST_RARITY)),
        )
    }

    /**
     * One ore pass laying tubes into whatever the Age calls stone.
     *
     * The whole height of the world, like the raw-temperstone blobs: an ore feature is a no-op wherever it
     * finds no stone, so the range costs a handful of misses rather than a rule about where rock is.
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
        }
        return Holder.direct(
            PlacedFeature(Holder.direct(ConfiguredFeature(Feature.ORE, OreConfiguration(targets, size))), spread),
        )
    }


    /** Small and common: a warm seam in the rock that seeps if you open it, and never throws. */
    private const val SEAM_SIZE = 7
    private const val SEAMS_PER_CHUNK = 5

    /** Rare and large enough to straddle the throwing threshold, so some of them are a real find. */
    private const val NEST_SIZE = 30
    private const val NEST_RARITY = 6

    private const val EVERY_CHUNK = 1
    private const val ONE = 1
}
