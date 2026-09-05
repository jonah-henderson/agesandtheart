package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.Holder
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
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
import java.util.concurrent.ConcurrentHashMap
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
    fun laidOver(
        base: (Holder<Biome>) -> BiomeGenerationSettings,
        danger: Danger,
        rock: List<BlockState>,
    ): (Holder<Biome>) -> BiomeGenerationSettings {
        if (!danger.paysOut) return base
        val veins = veinsFor(danger.score)
        if (veins <= 0) return base
        val deposit = depositIn(rock, veins)
        // Remembered per biome for the same reason `Features` remembers its own: this builds a new
        // `BiomeGenerationSettings` and the one handed back has to be the same object every time.
        val settled = ConcurrentHashMap<Holder<Biome>, BiomeGenerationSettings>()
        return { biome -> settled.computeIfAbsent(biome) { added(base(it), deposit) } }
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
    private fun depositIn(rock: List<BlockState>, veins: Int): Holder<PlacedFeature> {
        val configured = ConfiguredFeature(Feature.ORE, OreConfiguration(targetsIn(rock), VEIN_SIZE))
        return Holder.direct(
            PlacedFeature(
                Holder.direct(configured),
                listOf(
                    CountPlacement.of(veins),
                    InSquarePlacement.spread(),
                    HeightRangePlacement.triangle(
                        VerticalAnchor.aboveBottom(OFF_THE_FLOOR),
                        VerticalAnchor.absolute(DEEPEST_ORDINARY_GROUND),
                    ),
                    BiomeFilter.biome(),
                ),
            ),
        )
    }

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
    private fun added(
        settings: BiomeGenerationSettings,
        deposit: Holder<PlacedFeature>,
    ): BiomeGenerationSettings {
        val built = BiomeGenerationSettings.PlainBuilder()
        settings.carvers.forEach(built::addCarver)
        settings.features().forEachIndexed { step, atStep -> atStep.forEach { built.addFeature(step, it) } }
        built.addFeature(GenerationStep.Decoration.UNDERGROUND_ORES.ordinal, deposit)
        return built.build()
    }

    /**
     * Veins per chunk at a score of one — a whole Age of the worst of everything.
     *
     * Between vanilla's diamond (one attempt a chunk) and its copper (sixteen): finding this should be a
     * trip somebody made on purpose, not a thing that happens on the way somewhere else, and §7.7 wants a
     * deposit that runs out where you are standing.
     */
    private const val VEINS_AT_FULL_DANGER = 6

    private const val ONE_VEIN = 1

    /** Vanilla's own diamond vein, which is the scarcity this is aiming at. */
    private const val VEIN_SIZE = 4

    /** Clear of the bedrock floor, so a vein is never half-eaten by it. */
    private const val OFF_THE_FLOOR = 8

    /** About twenty under an ordinary sea: deep enough to be a trip, shallow enough to exist in a cavern Age. */
    private const val DEEPEST_ORDINARY_GROUND = 40
}
