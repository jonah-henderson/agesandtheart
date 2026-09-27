package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.worldgen.SizeScale
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.levelgen.feature.LakeFeature
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider
import net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider
import net.minecraft.util.random.WeightedList
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature
import net.minecraft.world.level.levelgen.feature.BlockPileFeature
import net.minecraft.world.level.levelgen.feature.IcebergFeature
import net.minecraft.world.level.levelgen.placement.CountPlacement
import net.minecraft.world.level.levelgen.placement.OffsetPlacement
import net.minecraft.util.valueproviders.IntProvider
import net.minecraft.util.valueproviders.TrapezoidInt
import net.minecraft.world.level.block.Block

import net.minecraft.world.level.levelgen.feature.AbstractOreFeature
import net.minecraft.world.level.levelgen.feature.BlockReplacement
import net.minecraft.world.level.levelgen.feature.OreFeature
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature
import net.minecraft.world.level.levelgen.feature.SpringFeature
import net.minecraft.world.level.levelgen.feature.VegetationPatchFeature
import net.minecraft.world.level.levelgen.feature.WaterloggedVegetationPatchFeature
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.placement.PlacementModifier
import kotlin.math.roundToInt
import kotlin.math.sqrt
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
    private fun spilled(block: Block, placement: List<PlacementModifier>): Holder<PlacedFeature> =
        Holder.direct(PlacedFeature(Holder.direct(SpilledSpring(block.defaultBlockState())), placement))

    /**
     * [pattern] made of [substances] instead of whatever it was made of — how a writer asks for a thing the
     * game does not have (world model §2).
     *
     * A formation, a patch and a pile mingle several (`mud and sand pits`, `poppy and dandelion patches`), as
     * a landmass's rock does. Every other pattern is made of the first: a spring runs with one fluid, and a
     * lake, a vein or an iceberg mixed block by block is noise.
     *
     * The shape, the placement, the rarity and the step are all the pattern's; only the substance changes.
     * A spring keeps the rock it wants around it and the holes it punches, and simply runs with something
     * else; a vein keeps its size and the stone it cuts into, and is made of something else.
     *
     * **Silent where the pattern is neither** — a configuration this does not know how to re-make comes
     * back untouched rather than half-made. The corpus decides which patterns are worth minting from, and a
     * word naming an unmintable one is a content bug rather than a play outcome.
     */
    @Suppress("DEPRECATION")
    fun mintedFrom(pattern: Holder<PlacedFeature>, substances: List<String>): Holder<PlacedFeature> {
        val blocks = substances.mapNotNull { substance ->
            Identifier.tryParse(substance)?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
        }
        val block = blocks.firstOrNull() ?: return pattern
        val placed = pattern.value()
        val rebuilt = when (val feature = placed.feature().value()) {
            is SpringFeature -> {
                // **A spring runs with a fluid, and a solid holds none.** `fluidState` of a block that is
                // not one is `Fluids.EMPTY`, so `gold block springs` would rebuild a spring that places
                // nothing at all. What it gets instead is [spilled] — the shape of a spring that tried.
                val running = block.defaultBlockState().fluidState
                if (running.isEmpty) return spilled(block, placed.placement())
                SpringFeature(
                    running,
                    feature.requiresBlockBelow(),
                    feature.rockCount(),
                    feature.holeCount(),
                    feature.validBlocks(),
                )
            }
            // **A lake is a bowl, so anything may fill it** — no fluid test beside the spring's, because
            // `LakeFeature` places the fill as plain blocks and only asks whether it is water to decide
            // about freezing it. The barrier it lines the bowl with is the pattern's and stays.
            // The three predicates are the pattern's own, as the barrier is: only the fluid is ours.
            // Vanilla has deprecated the whole feature, but a datapack may still carry one and this
            // only re-points its fill — so the deprecation is the pack's to answer, not ours.
            is LakeFeature -> LakeFeature(
                BlockStateProvider.holderOf(block.defaultBlockState()),
                feature.barrier(),
                feature.canPlaceFeature(),
                feature.canReplaceWithAirOrFluid(),
                feature.canReplaceWithBarrier(),
            )
            // Scattered before plain: both read the same targets and `ScatteredOreFeature` extends the
            // same base rather than `OreFeature`, so matching the plain one first would quietly turn every
            // scattered ore into a blob.
            is ScatteredOreFeature -> ScatteredOreFeature(
                feature.targetStates().map { BlockReplacement.replace(it.target(), block.defaultBlockState()) },
                feature.size(),
                feature.discardChanceOnAirExposure(),
            )
            is OreFeature -> OreFeature(
                feature.targetStates().map { BlockReplacement.replace(it.target(), block.defaultBlockState()) },
                feature.size(),
                feature.discardChanceOnAirExposure(),
            )
            // A formation is a shape and a substance and nothing else, so this is the whole of minting one —
            // bar the seed, which it places from: without it `mud pits` and `sand pits` land on the same spots.
            is Formation -> feature.copy(
                substances = blocks.map { it.defaultBlockState() },
                seed = feature.seed xor substances.hashCode().toLong(),
            )
            // The ore is what a writer names; the filler rock it is strung through stays the pattern's, and so
            // does where it runs, bar the seed for the same reason a formation's moves.
            is OreVein -> feature.copy(
                ore = block.defaultBlockState(),
                rawOre = OreVein.rawBlockOf(block).defaultBlockState(),
                seed = feature.seed xor substances.hashCode().toLong(),
            )
            // A patch is a block tried at many spots, and anything may be tried: what cannot stand where it
            // lands is not placed, exactly as a flower on sand is not.
            is SimpleBlockFeature -> SimpleBlockFeature(mingled(blocks), feature.scheduleTick())
            is BlockPileFeature -> BlockPileFeature(mingled(blocks))
            is Heap -> feature.copy(stateProvider = mingled(blocks))
            is IcebergFeature -> IcebergFeature(block.defaultBlockState())
            else -> return pattern
        }
        return Holder.direct(PlacedFeature(Holder.direct(rebuilt), placed.placement()))
    }

    /** [blocks] as one provider, drawn evenly where there are several. */
    private fun mingled(blocks: List<Block>): Holder<BlockStateProvider> {
        val states = blocks.map { it.defaultBlockState() }
        val single = states.singleOrNull()
            ?: return Holder.direct(WeightedStateProvider(WeightedList.of(*states.toTypedArray())))
        return BlockStateProvider.holderOf(single)
    }

    /**
     * A formation told the one biome it may stand in — see [Formation.onlyIn] — or null for any feature that
     * is not a formation, which stays in its biome's feature list as every other feature does.
     */
    fun confinedTo(feature: Holder<PlacedFeature>, biome: Identifier): Holder<PlacedFeature>? {
        val placed = feature.value()
        val formation = placed.feature().value() as? Formation ?: return null
        return Holder.direct(PlacedFeature(Holder.direct(formation.copy(onlyIn = biome)), placed.placement()))
    }

    /** Whether this places a [Formation] — the one kind of feature [confinedTo] can confine. */
    fun isAFormation(feature: Holder<PlacedFeature>): Boolean = feature.value().feature().value() is Formation

    fun reshaped(
        feature: Holder<PlacedFeature>,
        size: Double?,
        thickness: Double?,
        height: Double?,
        rock: List<BlockState>,
    ): Holder<PlacedFeature> {
        val placed = feature.value()
        val shaped = withShape(placed.feature(), size, thickness, height, rock)
        val isAPatch = placed.feature().value() is SimpleBlockFeature
        val spread = if (isAPatch) withSpread(placed.placement(), size) else placed.placement()
        val placement = withHeight(spread, height)
        if (shaped === placed.feature() && placement === placed.placement()) return feature
        return Holder.direct(PlacedFeature(shaped, placement))
    }

    /**
     * **A patch's size is its placement**: the tries clustered around one spot, each a single block. A
     * bigger patch spreads them over a wider square and makes more of them, by the area, so it is as dense
     * as an ordinary one and not a thin scatter. The tries are the count placed directly before the offset;
     * a placement without that pair is left as it stands.
     */
    private fun withSpread(placement: List<PlacementModifier>, size: Double?): List<PlacementModifier> {
        if (size == null) return placement
        val offsetAt = placement.indexOfFirst { it is OffsetPlacement }
        val tries = placement.getOrNull(offsetAt - 1) as? CountPlacement ?: return placement
        val offset = placement[offsetAt] as OffsetPlacement
        val factor = sizeFactor(size)
        val wanted = (tries.count().maxInclusive() * factor * factor).roundToInt().coerceIn(1, MOST_TRIES)
        val spread = OffsetPlacement(widened(offset.x(), factor), offset.y(), widened(offset.z(), factor))
        return placement.take(offsetAt - 1) + triesOf(wanted) + spread + placement.drop(offsetAt + 1)
    }

    /** A horizontal spread widened by [factor]; a spread of another shape is left alone. */
    private fun widened(spread: IntProvider, factor: Double): IntProvider {
        val trapezoid = spread as? TrapezoidInt ?: return spread
        val reach = (trapezoid.maxInclusive() * factor).roundToInt().coerceIn(0, MOST_PATCH_REACH)
        return TrapezoidInt.of(-reach, reach, trapezoid.plateau())
    }

    /** [wanted] tries as counts a placement can hold, each of which is capped at 256. */
    private fun triesOf(wanted: Int): List<PlacementModifier> {
        val outer = (wanted + MOST_PER_COUNT - 1) / MOST_PER_COUNT
        return if (outer <= 1) listOf(CountPlacement.of(wanted))
        else listOf(CountPlacement.of(outer), CountPlacement.of(wanted / outer))
    }

    /** `CountPlacement`'s own ceiling. */
    private const val MOST_PER_COUNT = 256

    /** Sixteen times vanilla's tries, which is what a colossal patch asks for. */
    private const val MOST_TRIES = 1024

    /** A patch reaches no farther than the chunk next door, where worldgen may still write. */
    private const val MOST_PATCH_REACH = 24

    /**
     * The feature with its own shape rebuilt — an ore's vein size, a patch's fill.
     *
     * Both are one number in a class with a public constructor. Anything else is returned as it stands:
     * thirty-odd feature types exist and guessing at one we did not survey would be worse than declining.
     * [height] reaches only a vein here — everything else carries it in its placement ([withHeight]).
     */
    private fun withShape(
        held: Holder<Feature>,
        size: Double?,
        thickness: Double?,
        height: Double?,
        rock: List<BlockState>,
    ): Holder<Feature> {
        val rebuilt = when (val feature = held.value()) {
            // Scattered before plain, for the reason [mintedFrom] gives.
            is ScatteredOreFeature -> {
                val targets = targetsReaching(feature, rock)
                val veins = size?.let { scaled(feature.size(), it, MOST_OF_A_VEIN) }
                if (targets == null && veins == null) {
                    null
                } else {
                    ScatteredOreFeature(
                        targets ?: feature.targetStates(),
                        veins ?: feature.size(),
                        feature.discardChanceOnAirExposure(),
                    )
                }
            }

            is OreFeature -> {
                val targets = targetsReaching(feature, rock)
                val veins = size?.let { scaled(feature.size(), it, MOST_OF_A_VEIN) }
                if (targets == null && veins == null) {
                    null
                } else {
                    OreFeature(
                        targets ?: feature.targetStates(),
                        veins ?: feature.size(),
                        feature.discardChanceOnAirExposure(),
                    )
                }
            }

            // A vein cuts the Age's own rock as an ore does, and a bigger one is its noise stretched — by the
            // root of the factor, since stretching makes a vein longer and thicker at once. Sitting above the
            // middle of the column, it takes vanilla's copper band and filler in place of its iron ones.
            is OreVein -> {
                val unreached = rock.map { it.block }.filterNot { it.defaultBlockState().`is`(feature.cuts) }.distinct()
                val sitsShallow = height != null && height > MID_COLUMN
                if (unreached.isEmpty() && size == null && !sitsShallow) {
                    null
                } else {
                    val banded = if (sitsShallow) feature.inTheCopperBand() else feature
                    banded.copy(
                        alsoCuts = (feature.alsoCuts + unreached).distinct(),
                        stretch = size?.let { feature.stretch * sqrt(sizeFactor(it)) } ?: feature.stretch,
                    )
                }
            }

            // Waterlogged before plain: it extends the plain one, and rebuilding it as the base class
            // would dry out every waterlogged patch in the Age.
            is WaterloggedVegetationPatchFeature -> thickness?.let {
                WaterloggedVegetationPatchFeature(
                    feature.replaceable,
                    feature.groundState,
                    feature.vegetationFeature,
                    feature.surface,
                    feature.depth,
                    feature.extraBottomBlockChance,
                    feature.verticalRange,
                    scaledChance(feature.vegetationChance, it),
                    feature.xzRadius,
                    feature.extraEdgeColumnChance,
                )
            }

            is VegetationPatchFeature -> thickness?.let {
                VegetationPatchFeature(
                    feature.replaceable,
                    feature.groundState,
                    feature.vegetationFeature,
                    feature.surface,
                    feature.depth,
                    feature.extraBottomBlockChance,
                    feature.verticalRange,
                    scaledChance(feature.vegetationChance, it),
                    feature.xzRadius,
                    feature.extraEdgeColumnChance,
                )
            }

            // **Resized rather than rebuilt**, which is what a field tree buys: the description grows, so a
            // colossal obelisk has more courses of blocks rather than a stretched staircase. The pose is
            // resized with it, its lifts being absolute blocks.
            is Formation -> size?.let {
                val factor = sizeFactor(it)
                // **The layout is spread with the shapes, but by the root of the factor.** Spreading it
                // linearly keeps the *fraction of ground covered* constant, which sounds right and makes a
                // colossal formation doubly hard to meet: bigger, and no more of them per mile walked. A
                // writer asking for colossal wants bigger, not scarcer — so the spacing grows with the
                // square root, and a colossal ring sits about twice as far from its neighbour rather than
                // four times. Walked at 4x: one every ~800 blocks became one every ~400.
                feature.copy(
                    shapes = feature.shapes.map { shape -> shape.resized(factor, STANDING_ON_THE_GROUND) },
                    variation = feature.variation.resized(factor),
                    placement = feature.placement.resized(sqrt(factor)),
                )
            }

            is Heap -> size?.let { feature.copy(scale = feature.scale * sizeFactor(it)) }

            else -> null
        } ?: return held
        return Holder.direct(rebuilt)
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
        feature: AbstractOreFeature,
        rock: List<BlockState>,
    ): List<BlockReplacement>? {
        val probe = XoroshiroRandomSource(A_FIXED_PROBE)
        // 26.3's `RuleTest` takes the position too. None of the tests that could answer differently read
        // it — they turn on the state or on the random — so this asks about nowhere in particular.
        val unreached = rock.filterNot { block ->
            feature.targetStates().any { it.target().test(block, BlockPos.ZERO, probe) }
        }
        if (unreached.isEmpty()) return null
        val ore = feature.targetStates().firstOrNull()?.state() ?: return null
        return feature.targetStates() +
            unreached.distinct().map { BlockReplacement.replace(BlockMatchTest(it.block), ore) }
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
    private fun scaled(ordinary: Int, dial: Double, most: Int): Int =
        (ordinary * sizeFactor(dial)).roundToInt().coerceIn(1, most)

    /** What a dial at [dial] multiplies a size by — a quarter at the bottom of the axis, four at the top. */
    private fun sizeFactor(dial: Double): Double = SizeScale.factorAt(dial)

    /** A formation is authored with its base at the origin, and the ground is where it is put. */
    private const val STANDING_ON_THE_GROUND = 0

    /** `Features.HEIGHT`'s "mid-column": above it a thing sits shallow, at or below it deep. */
    private const val MID_COLUMN = 0.0

    /** Vanilla's largest vein is 20-odd blocks, so this is generous rather than a real bound. */
    private const val MOST_OF_A_VEIN = 64

    /** A chance stays a chance: a parameter may fill a patch or thin it, never take it past certain. */
    private fun scaledChance(ordinary: Float, dial: Double): Float =
        (ordinary * sizeFactor(dial)).toFloat().coerceIn(0.05f, 1.0f)

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
