package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.consequence.Collapse
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.Holder
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration
import net.minecraft.world.level.levelgen.placement.BiomeFilter
import net.minecraft.world.level.levelgen.placement.CountPlacement
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement
import net.minecraft.world.level.levelgen.placement.InSquarePlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest
import kotlin.math.roundToInt

/**
 * What a dangerous Age has in the ground (design §7.7) — deretheni, at a density its danger scales.
 *
 * **Not a feature a writer can ask for**, and that is the fence the whole reward rests on: the ore blocks
 * carry `#agesandtheart:forbidden`, so no word is ever derived for them, and the only way to an Age full of
 * this is to write an Age that earns it. You cause the conditions; you cannot name the outcome.
 *
 * **Laid over whatever the sentence already asked for**, rather than built into [Features]. What a book
 * asked to grow and what an Age owes its writer are different questions, and keeping them apart is what
 * lets either change without reading the other.
 */
object Deposits {

    /**
     * [base]'s settings with this Age's deposit added, or [base] itself where the Age earns nothing.
     *
     * **One `PlacedFeature` for the whole Age, built here and shared by every biome.** `FeatureSorter`
     * indexes the sorted feature list by *identity* and `applyBiomeDecoration` looks each feature up in it
     * every chunk, so an equal-but-new object in the twentieth biome is a miss, and a miss is -1 into a
     * list in the middle of generation. [Features] pays for this lesson twice in its own comments; this is
     * the third place it applies.
     */
    fun layer(danger: Danger, rock: List<BlockState>): Decoration.Layer? {
        val veins = veinsPerChunk(danger)
        if (veins <= NOTHING) return null
        return Decoration.layerOf(
            GenerationStep.Decoration.UNDERGROUND_ORES,
            listOf(depositIn(rock, veins, danger.isTerminal)),
        )
    }

    /**
     * How many veins of deretheni a chunk of this Age is offered, or [NOTHING] where it earns none.
     *
     * Public because the geologic survey says *how much* (§7.7), and a survey that worked the amount out
     * for itself would be a second statement of the yield, free to drift from the ground it describes.
     */
    fun veinsPerChunk(danger: Danger): Int {
        if (!danger.paysOut) return NOTHING
        val multiple = if (danger.isTerminal) TERMINAL_MULTIPLE else ORDINARY_MULTIPLE
        return veinsFor(danger.score) * multiple
    }

    /**
     * How many veins a chunk is offered at this score.
     *
     * **No ceiling** (§7.7): an Age that scores over one gets more than [VEINS_AT_FULL_DANGER], because a
     * player who works out how to survive a world that should not be survivable has earned what is in it.
     * The floor is one — an Age over the threshold that rounded to nothing would read as the reward being
     * broken rather than as the Age being marginal.
     */
    private fun veinsFor(score: Double): Int =
        (score * VEINS_AT_FULL_DANGER).roundToInt().coerceAtLeast(ONE_VEIN)

    /**
     * The deposit itself — an ore vein, sited deep.
     *
     * **Deep is the whole of the siting, and that is deliberate.** §7.7 wants the material somewhere
     * plausibly dangerous without guaranteeing it, and explicitly declines to pay for siting each vein
     * against the hazard that earned it: the score says *how much*, and Minecraft's own emergent difficulty
     * is trusted to make the trip cost something. Deep and dark is the half of that which is mechanical;
     * near the lava and near the wound are the half that is not, and neither is worth a placement modifier.
     */
    private fun depositIn(rock: List<BlockState>, veins: Int, raid: Boolean): Holder<PlacedFeature> {
        val size = VEIN_SIZE * if (raid) TERMINAL_MULTIPLE else ORDINARY_MULTIPLE
        val configured = ConfiguredFeature(Feature.ORE, OreConfiguration(targetsIn(rock), size))
        return Holder.direct(
            PlacedFeature(
                Holder.direct(configured),
                listOf(
                    CountPlacement.of(veins),
                    InSquarePlacement.spread(),
                    if (raid) inTheFloorItself() else deepInTheGround(),
                    BiomeFilter.biome(),
                ),
            ),
        )
    }

    /** Where an ordinary Age keeps it: spread through the deep half, thickest well under the sea. */
    private fun deepInTheGround(): HeightRangePlacement = HeightRangePlacement.triangle(
        VerticalAnchor.aboveBottom(OFF_THE_FLOOR),
        VerticalAnchor.absolute(DEEPEST_ORDINARY_GROUND),
    )

    /**
     * Where a doomed Age keeps it — **inside the band the tear takes**, so the hoard is the first thing the
     * floor swallows (Jonah, 2026-09-05).
     *
     * That is the whole shape of §7.7's raid: the reward is absurd, it is at the bottom of a world that is
     * coming apart from the bottom, and every trip down is a race against the ground you are standing on.
     * Uniform rather than triangular, this being a band two dozen blocks thick rather than a distribution.
     *
     * Reads [Collapse.DEEP] rather than restating it: if the tear's reach ever moves, the hoard has to move
     * with it or the whole point is lost quietly.
     */
    private fun inTheFloorItself(): HeightRangePlacement = HeightRangePlacement.uniform(
        VerticalAnchor.aboveBottom(JUST_OFF_THE_FLOOR),
        VerticalAnchor.aboveBottom(Collapse.DEEP),
    )

    /**
     * What the vein replaces: vanilla's two ore hosts, and **whatever this Age is actually made of**.
     *
     * An ore rule matches the rock it was written for, so an Age of blackstone or of somebody's modded
     * granite would grow no deposit at all — the same trap `FeatureShape.targetsReaching` exists for, and
     * the reason the rock is threaded down here rather than assumed.
     */
    private fun targetsIn(rock: List<BlockState>): List<OreConfiguration.TargetBlockState> {
        val stone = AgeContent.PITCHSTONE_ORE_BLOCK.defaultBlockState()
        val deepslate = AgeContent.DEEPSLATE_PITCHSTONE_ORE_BLOCK.defaultBlockState()
        val vanillas = listOf(
            OreConfiguration.target(TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES), stone),
            OreConfiguration.target(TagMatchTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES), deepslate),
        )
        val ours = rock.distinct()
            .filterNot { it.`is`(BlockTags.STONE_ORE_REPLACEABLES) || it.`is`(BlockTags.DEEPSLATE_ORE_REPLACEABLES) }
            .map { OreConfiguration.target(BlockMatchTest(it.block), stone) }
        return vanillas + ours
    }

    /** [settings] with [deposit] among its ores, everything else untouched. */

    /**
     * Veins per chunk at a score of one — a whole Age of the worst of everything.
     *
     * Between vanilla's diamond (one attempt a chunk) and its copper (sixteen): finding this should be a
     * trip somebody made on purpose, not a thing that happens on the way somewhere else, and §7.7 wants a
     * deposit that runs out where you are standing.
     */
    private const val VEINS_AT_FULL_DANGER = 6

    private const val ONE_VEIN = 1

    /** What an Age that earns no deposit is offered. */
    private const val NOTHING = 0

    /**
     * What a terminal Age multiplies both the count and the vein size by (§7.7).
     *
     * Absurd on purpose and unbalanced by design — "grab what you can before you cannot" is not a rate to
     * be tuned against the ordinary economy, because the Age it comes from cannot be farmed. A first
     * figure, and expected to move.
     */
    private const val TERMINAL_MULTIPLE = 10

    private const val ORDINARY_MULTIPLE = 1

    /** Clear of the layer `Collapse` keeps underfoot, so the hoard is in the tear rather than under it. */
    private const val JUST_OFF_THE_FLOOR = 2

    /** Vanilla's own diamond vein, which is the scarcity this is aiming at. */
    private const val VEIN_SIZE = 4

    /** Clear of the bedrock floor, so a vein is never half-eaten by it. */
    private const val OFF_THE_FLOOR = 8

    /** About twenty under an ordinary sea: deep enough to be a trip, shallow enough to exist in a cavern Age. */
    private const val DEEPEST_ORDINARY_GROUND = 40
}
