package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.age.aspect.Span
import net.minecraft.core.Holder
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.levelgen.feature.LakeFeature
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider
import net.minecraft.world.level.block.Block

import net.minecraft.world.level.levelgen.feature.configurations.BlockStateConfiguration
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration
import net.minecraft.world.level.levelgen.feature.configurations.VegetationPatchConfiguration
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.placement.PlacementModifier
import kotlin.math.roundToInt
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest

/**
 * What an Age's features are *like*, as opposed to how many of them there are ([FeatureDensity]).
 *
 * Three parameters, chosen from a survey of every placement modifier and configuration 26.1.2 ships (design
 * §7.2): how big one ore vein is, how thickly a vegetation patch fills, and how deep in the column a thing
 * sits.
 * Each is a single number in vanilla's own data, and each is reached by **rebuilding the record that holds
 * it** — a public constructor and no codec surgery, which is why these three and not the other thirty.
 *
 * **They apply to everything the Age places**, named or not: a dial is about how this world makes things,
 * where a rung is about one of them. A feature the parameter does not fit — a lake, for a vein size — comes
 * back untouched rather than approximated.
 */
object FeatureShape {

    /** Whether any of these were turned at all, so an Age nobody steered rebuilds nothing. */
    fun asksForNothing(size: Double?, thickness: Double?, height: Double?): Boolean =
        size == null && thickness == null && height == null

    /**
     * Whether vanilla's ores can reach an Age made of [rock] at all.
     *
     * **They cannot, for most rock.** An ore feature replaces what its `RuleTest` matches, and vanilla's
     * two are the tags `stone_ore_replaceables` — stone, granite, diorite, andesite — and
     * `deepslate_ore_replaceables` — deepslate, tuff. An Age of blackstone, basalt or copper therefore
     * grows no ore whatsoever, and says nothing about it, which is §3.3's silent drop in the one place a
     * writer would least expect to find it.
     */
    fun oresCanReach(rock: List<BlockState>): Boolean = rock.all { block ->
        block.`is`(BlockTags.STONE_ORE_REPLACEABLES) || block.`is`(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
    }

    /**
     * **A spring that tried** — [SpilledSpring], standing where the spring would have stood.
     *
     * It keeps the spring's own [placement], so it comes as often as a spring and is sited where one is:
     * the feature's own test for a wall to come out of and an opening to come out into is what decides
     * whether anything is laid at all, exactly as a spring's rock and hole counts decide for it.
     */
    private fun spilled(block: Block, placement: List<PlacementModifier>): Holder<PlacedFeature> {
        val made = ConfiguredFeature(SpilledSpring, BlockStateConfiguration(block.defaultBlockState()))
        return Holder.direct(PlacedFeature(Holder.direct(made), placement))
    }

    /**
     * [pattern] made of [substance] instead of whatever it was made of — how a writer asks for a thing the
     * game does not have (world model §2).
     *
     * The shape, the placement, the rarity and the step are all the pattern's; only the substance changes.
     * A spring keeps the rock it wants around it and the holes it punches, and simply runs with something
     * else; a vein keeps its size and the stone it cuts into, and is made of something else.
     *
     * **Silent where the pattern is neither** — a configuration this does not know how to re-make comes
     * back untouched rather than half-made. The corpus decides which patterns are worth minting from, and a
     * word naming an unmintable one is a content bug rather than a play outcome.
     */
    fun mintedFrom(pattern: Holder<PlacedFeature>, substance: String): Holder<PlacedFeature> {
        val block = Identifier.tryParse(substance)
            ?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
            ?: return pattern
        val placed = pattern.value()
        val feature = placed.feature().value()
        val rebuilt = when (val configuration = feature.config()) {
            is SpringConfiguration -> {
                // **A spring runs with a fluid, and a solid holds none.** `fluidState` of a block that is
                // not one is `Fluids.EMPTY`, so `gold block springs` would rebuild a spring that places
                // nothing at all. What it gets instead is [spilled] — the shape of a spring that tried.
                val running = block.defaultBlockState().fluidState
                if (running.isEmpty) return spilled(block, placed.placement())
                SpringConfiguration(
                    running,
                    configuration.requiresBlockBelow,
                    configuration.rockCount,
                    configuration.holeCount,
                    configuration.validBlocks,
                )
            }
            // **A lake is a bowl, so anything may fill it** — no fluid test beside the spring's, because
            // `LakeFeature` places the fill as plain blocks and only asks whether it is water to decide
            // about freezing it. The barrier it lines the bowl with is the pattern's and stays.
            is LakeFeature.Configuration -> LakeFeature.Configuration(
                BlockStateProvider.simple(block.defaultBlockState()),
                configuration.barrier(),
            )
            is OreConfiguration -> OreConfiguration(
                configuration.targetStates.map { OreConfiguration.target(it.target, block.defaultBlockState()) },
                configuration.size,
                configuration.discardChanceOnAirExposure,
            )
            else -> return pattern
        }
        @Suppress("UNCHECKED_CAST")
        val made = ConfiguredFeature(feature.feature() as Feature<FeatureConfiguration>, rebuilt)
        return Holder.direct(PlacedFeature(Holder.direct(made), placed.placement()))
    }

    fun reshaped(
        feature: Holder<PlacedFeature>,
        size: Double?,
        thickness: Double?,
        height: Double?,
        rock: List<BlockState>,
    ): Holder<PlacedFeature> {
        val placed = feature.value()
        val configured = withConfiguration(placed.feature(), size, thickness, rock)
        val placement = withHeight(placed.placement(), height)
        if (configured === placed.feature() && placement === placed.placement()) return feature
        return Holder.direct(PlacedFeature(configured, placement))
    }

    /**
     * The configured feature with its own shape rebuilt — an ore's vein size, a patch's fill.
     *
     * Both are one number in a record with a public constructor, so this needs neither a codec round trip
     * nor a widener. Anything else is returned as it stands: thirty-odd configuration types exist and
     * guessing at one we did not survey would be worse than declining.
     */
    private fun withConfiguration(
        configured: Holder<ConfiguredFeature<*, *>>,
        size: Double?,
        thickness: Double?,
        rock: List<BlockState>,
    ): Holder<ConfiguredFeature<*, *>> {
        val feature = configured.value()
        val rebuilt = when (val configuration = feature.config()) {
            is OreConfiguration -> {
                val targets = targetsReaching(configuration, rock)
                val veins = size?.let { scaled(configuration.size, it, MOST_OF_A_VEIN) }
                if (targets == null && veins == null) {
                    null
                } else {
                    OreConfiguration(
                        targets ?: configuration.targetStates,
                        veins ?: configuration.size,
                        configuration.discardChanceOnAirExposure,
                    )
                }
            }

            is VegetationPatchConfiguration -> thickness?.let {
                VegetationPatchConfiguration(
                    configuration.replaceable,
                    configuration.groundState,
                    configuration.vegetationFeature,
                    configuration.surface,
                    configuration.depth,
                    configuration.extraBottomBlockChance,
                    configuration.verticalRange,
                    scaledChance(configuration.vegetationChance, it),
                    configuration.xzRadius,
                    configuration.extraEdgeColumnChance,
                )
            }

            else -> null
        } ?: return configured
        // The generics are the record's own: a `ConfiguredFeature<FC, F>` pairs a configuration with the
        // feature that reads it, and rebuilding one loses the pairing the compiler was tracking. The
        // configuration came out of this very feature, so the pair is sound.
        @Suppress("UNCHECKED_CAST")
        val paired = ConfiguredFeature(feature.feature() as Feature<FeatureConfiguration>, rebuilt)
        return Holder.direct(paired)
    }

    /**
     * This ore's targets with the Age's own [rock] added, or null where it could already reach it.
     *
     * **Asked of the `RuleTest` itself rather than of a tag**, so a modded ore with a target of its own
     * answers for itself and nothing here has to know what it matches. A rock that passes no target at all
     * gets one of its own, yielding whatever the ore's *first* target yields — vanilla lists the stone
     * variant first and the deepslate one second, so an Age of blackstone gets diamond ore rather than
     * deepslate diamond ore, which is the right one for a rock that is not deepslate.
     */
    private fun targetsReaching(
        configuration: OreConfiguration,
        rock: List<BlockState>,
    ): List<OreConfiguration.TargetBlockState>? {
        val probe = XoroshiroRandomSource(A_FIXED_PROBE)
        val unreached = rock.filterNot { block ->
            configuration.targetStates.any { it.target.test(block, probe) }
        }
        if (unreached.isEmpty()) return null
        val ore = configuration.targetStates.firstOrNull()?.state ?: return null
        return configuration.targetStates +
            unreached.distinct().map { OreConfiguration.target(BlockMatchTest(it.block), ore) }
    }

    /**
     * The placement with its height band moved — **replacing an existing one, never adding a new one**.
     *
     * A modifier list is a stream transform, so a height range appended to a feature that has none simply
     * overrides whatever put it there: a tree that follows the heightmap would be torn off the ground and
     * buried. So a feature with no band of its own is one this parameter has nothing to say about.
     *
     * The replacement is a uniform band, which loses the triangular distribution vanilla gives its ores.
     * That is the trade: a writer can say *where* a thing sits, and cannot say what shape the seam has.
     */
    private fun withHeight(placement: List<PlacementModifier>, height: Double?): List<PlacementModifier> {
        if (height == null || placement.none { it is HeightRangePlacement }) return placement
        val middle = DEEPEST + Span.NATURAL.fractionOf(height) * (HIGHEST - DEEPEST)
        val band = HeightRangePlacement.of(
            UniformHeight.of(
                VerticalAnchor.absolute((middle - HALF_A_BAND).roundToInt()),
                VerticalAnchor.absolute((middle + HALF_A_BAND).roundToInt()),
            ),
        )
        return placement.map { if (it is HeightRangePlacement) band else it }
    }

    /** [ordinary] moved by [dial], read across the axis every span shares, and kept somewhere sane. */
    private fun scaled(ordinary: Int, dial: Double, most: Int): Int {
        val factor = FAINTEST + Span.NATURAL.fractionOf(dial) * (RICHEST - FAINTEST)
        return (ordinary * factor).roundToInt().coerceIn(1, most)
    }

    /** A parameter at the bottom of its axis leaves a quarter of what there was; at the top, four times. */
    private const val FAINTEST = 0.25
    private const val RICHEST = 4.0

    /** Vanilla's largest vein is 20-odd blocks, so this is generous rather than a real bound. */
    private const val MOST_OF_A_VEIN = 64

    /** A chance stays a chance: a parameter may fill a patch or thin it, never take it past certain. */
    private fun scaledChance(ordinary: Float, dial: Double): Float {
        val factor = FAINTEST + Span.NATURAL.fractionOf(dial) * (RICHEST - FAINTEST)
        return (ordinary * factor).toFloat().coerceIn(0.05f, 1.0f)
    }

    /**
     * A `RuleTest` takes a source of randomness, and a probabilistic one would answer differently each
     * time it were asked. Asking with a fixed one can only ever say "cannot reach" where it sometimes
     * could, which adds a target that was not needed and changes nothing.
     */
    private const val A_FIXED_PROBE = 0x0DE_5EEDL

    /** The band a steered feature sits in — the world's floor to well above the surface. */
    private const val DEEPEST = -56.0
    private const val HIGHEST = 200.0
    private const val HALF_A_BAND = 32.0
}
