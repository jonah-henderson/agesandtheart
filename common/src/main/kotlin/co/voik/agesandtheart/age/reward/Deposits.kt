package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.Holder
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.feature.BlockReplacement
import net.minecraft.world.level.levelgen.feature.OreFeature
import net.minecraft.world.level.levelgen.placement.BiomeFilter
import net.minecraft.world.level.levelgen.placement.CountPlacement
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement
import net.minecraft.world.level.levelgen.placement.InSquarePlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.placement.RarityFilter
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest
import kotlin.math.roundToInt

/**
 * What a dangerous Age has in the ground (design §7.7) — deretheni, as much of it as the Age's [Yield].
 *
 * **Each yield is laid as a vanilla ore is** (Jonah, 2026-10-22): traces as emerald, a little as diamond, some as
 * iron, a good deal between iron and copper, large amounts as copper — their vein sizes, counts and heights — so
 * a word the survey says means what a player already knows by sight. A collapsing Age lays the hoard instead.
 *
 * **Not a feature a writer can ask for.** The stone has a word, but it demands masterwork ink
 * (`#agesandtheart:requires_masterwork_ink`), so an Age made of the stuff is priced rather than free.
 *
 * **Laid over whatever the sentence already asked for**, rather than built into [Features]. What a book
 * asked to grow and what an Age owes its writer are different questions, and keeping them apart is what
 * lets either change without reading the other.
 */
object Deposits {

    /**
     * This Age's deposit as a decoration layer, or null where the Age earns nothing.
     *
     * **Its `PlacedFeature`s are built once for the whole Age, here, and shared by every biome.** `FeatureSorter`
     * indexes the sorted feature list by *identity* and `applyBiomeDecoration` looks each feature up in it
     * every chunk, so an equal-but-new object in the twentieth biome is a miss, and a miss is -1 into a
     * list in the middle of generation. [Features] pays for this lesson twice in its own comments; this is
     * the third place it applies.
     */
    fun layer(danger: Danger, rock: List<BlockState>): Decoration.Layer? {
        val veins = veinsOf(Yield.of(danger), danger)
        if (veins.isEmpty()) return null
        val targets = targetsIn(rock)
        return Decoration.layerOf(GenerationStep.Decoration.UNDERGROUND_ORES, veins.map { it.placed(targets) })
    }

    /** What a deposit of [yield] is laid as — see [VanillaVeins]. */
    private fun veinsOf(yield: Yield, danger: Danger): List<Vein> = when (yield) {
        Yield.NONE -> emptyList()
        Yield.TRACE -> VanillaVeins.EMERALD
        Yield.LITTLE -> VanillaVeins.DIAMOND
        Yield.SOME -> VanillaVeins.IRON
        Yield.MUCH -> VanillaVeins.IRON + VanillaVeins.COPPER_HALVED
        Yield.IMMENSE -> VanillaVeins.LARGE_AMOUNTS.map { it.times(pastTheTop(danger)) }
        Yield.MASSIVE -> listOf(hoard(danger))
    }

    /**
     * How far past the line large amounts begins at this Age scores — one there, and more as it goes on, an Age twice
     * as dangerous offered twice copper's veins — **up to [MOST_PAST_THE_TOP]**, where it would hold as much as the
     * least of a collapse's hoard. Danger alone never buys what only a collapse does (Jonah, 2026-10-22).
     */
    private fun pastTheTop(danger: Danger): Double =
        (danger.score / danger.lineFor(Yield.IMMENSE)).coerceIn(ONE.toDouble(), MOST_PAST_THE_TOP)

    /** How much deretheni a chunk of this Age holds in the ground ([Vein.inTheGround]) — what [leastHoard] is compared against. */
    internal fun heldAt(danger: Danger): Double = veinsOf(Yield.of(danger), danger).sumOf(Vein::inTheGround)

    /** How much the least of a collapse's hoard holds a chunk: one step's veins. */
    internal val leastHoard: Double get() = hoardOf(TERMINAL_VEINS).inTheGround

    /** The most [pastTheTop] goes to: large amounts as many times over as they fit in [leastHoard]. */
    private val MOST_PAST_THE_TOP: Double by lazy { leastHoard / VanillaVeins.LARGE_AMOUNTS.sumOf(Vein::inTheGround) }

    /**
     * How many veins of the hoard a chunk of a collapsing Age is offered.
     *
     * **Its score still counts**: forty a chunk for every half of the line deretheni begins at, and never fewer than forty —
     * "grab what you can before you cannot" is not a rate to be tuned against the ordinary economy, because the Age
     * it comes from cannot be farmed.
     */
    fun hoardVeinsPerChunk(danger: Danger): Int =
        (danger.score / danger.lineFor(Yield.TRACE) * STEPS_TO_THE_LINE).roundToInt().coerceAtLeast(ONE) * TERMINAL_VEINS

    /**
     * The hoard — **reaching down into the band the tear takes, and well above it**.
     *
     * That is the whole shape of §7.7's raid: the reward is absurd, it is at the bottom of a world that is
     * coming apart from the bottom, and every trip down is a race against the ground you are standing on.
     *
     * **Sited inside the tear's own band alone, it was almost entirely eaten** (Jonah, walked 2026-09-10).
     * Putting the hoard where the floor goes first was deliberate and it went too far: a reward you watch
     * being consumed is drama, one already consumed before you arrive is an empty band.
     *
     * **A tear is one layer now**, so there is no band to reach a multiple of: the hoard sits in the few
     * layers over it, close enough that the floor is visibly coming for it and shallow enough that what the
     * floor takes is one layer rather than a third of the seam — **starting where the bedrock floor has faded
     * out**, since a vein cannot take bedrock and the fade is most of the layers nearest the bottom.
     *
     * Uniform rather than triangular, so the part that survives reads as the same seam continuing down into
     * the part that does not.
     */
    private fun hoard(danger: Danger) = hoardOf(hoardVeinsPerChunk(danger))

    private fun hoardOf(veins: Int) = Vein(
        size = HOARD_VEIN_SIZE,
        discardedInAir = 0.0f,
        perChunk = veins,
        band = Band(
            Height(SurfacingStrategy.BEDROCK_FADE, fromTheBottom = true),
            Height(SurfacingStrategy.BEDROCK_FADE + REACHES_OVER_THE_BEDROCK, fromTheBottom = true),
            thickestMidway = false,
        ),
    )

    /**
     * What the vein replaces: vanilla's two ore hosts, and **whatever this Age is actually made of**. The
     * stone is the same in all of them.
     *
     * An ore rule matches the rock it was written for, so an Age of blackstone or of somebody's modded
     * granite would grow no deposit at all — the same trap `FeatureShape.targetsReaching` exists for, and
     * the reason the rock is threaded down here rather than assumed.
     */
    private fun targetsIn(rock: List<BlockState>): List<BlockReplacement> {
        val stone = AgeContent.PITCHSTONE_BLOCK.defaultBlockState()
        val vanillas = listOf(
            BlockReplacement.replace(TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES), stone),
            BlockReplacement.replace(TagMatchTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES), stone),
        )
        val ours = rock.distinct()
            .filterNot { it.`is`(BlockTags.STONE_ORE_REPLACEABLES) || it.`is`(BlockTags.DEEPSLATE_ORE_REPLACEABLES) }
            .map { BlockReplacement.replace(BlockMatchTest(it.block), stone) }
        return vanillas + ours
    }

    /** What a collapsing Age's veins are each worth: ten of vanilla's diamond. */
    private const val HOARD_VEIN_SIZE = 40

    /** And how many of them a chunk is offered for each step of danger. */
    private const val TERMINAL_VEINS = 40

    /** How many steps of danger lie below the line deretheni begins at. */
    private const val STEPS_TO_THE_LINE = 2.0
    private const val ONE = 1

    /** How many layers the hoard is laid through, from where the bedrock floor has faded out. */
    private const val REACHES_OVER_THE_BEDROCK = 6
}

/**
 * One ore placement, made of deretheni: how big a vein, how much of it an opening to the air loses, how many a chunk
 * is offered — or one in how many chunks, for the rarest — and where in the height of the world.
 */
private data class Vein(
    val size: Int,
    val discardedInAir: Float,
    val perChunk: Int,
    val band: Band,
    val oneChunkIn: Int = EVERY_CHUNK,
) {
    /**
     * How many blocks of the stone a chunk is offered in the ground by this vein, on average — its size, as often as
     * it comes, by the share of its band in the ground. Measured there because that is where a player digs: iron's
     * mountain veins and most of emerald's land in the open air of an Age without mountains.
     */
    val inTheGround: Double get() = size.toDouble() * perChunk / oneChunkIn * band.shareInTheGround

    /** The same vein offered [factor] times as often a chunk. */
    fun times(factor: Double): Vein = copy(perChunk = (perChunk * factor).roundToInt())

    fun placed(targets: List<BlockReplacement>): Holder<PlacedFeature> {
        val often = if (oneChunkIn > EVERY_CHUNK) RarityFilter.onAverageOnceEvery(oneChunkIn) else CountPlacement.of(perChunk)
        return Holder.direct(
            PlacedFeature(
                Holder.direct(OreFeature(targets, size, discardedInAir)),
                listOf(often, InSquarePlacement.spread(), band.placement, BiomeFilter.biome()),
            ),
        )
    }

    private companion object {
        const val EVERY_CHUNK = 1
    }
}

/** A height in the world: [y] blocks, counted up from the bottom of the world where [fromTheBottom]. */
private data class Height(val y: Int, val fromTheBottom: Boolean = false) {
    val anchor: VerticalAnchor get() = if (fromTheBottom) VerticalAnchor.aboveBottom(y) else VerticalAnchor.absolute(y)

    /** The same height in the overworld. */
    val inTheOverworld: Int get() = if (fromTheBottom) OVERWORLD_BOTTOM + y else y
}

/** Where a vein is laid: from [low] to [high], evenly, or thickest midway and thinning to nothing at both ends. */
private data class Band(val low: Height, val high: Height, val thickestMidway: Boolean) {
    val placement: HeightRangePlacement
        get() = if (thickestMidway) HeightRangePlacement.triangle(low.anchor, high.anchor) else HeightRangePlacement.uniform(low.anchor, high.anchor)

    /** How much of what is laid here falls in the overworld's ground, from its bottom up to its sea. */
    val shareInTheGround: Double get() = shareBelow(GROUND_TOP) - shareBelow(OVERWORLD_BOTTOM)

    /** How much of what is laid here falls under [y] in the overworld. */
    private fun shareBelow(y: Int): Double {
        val bottom = low.inTheOverworld.toDouble()
        val top = high.inTheOverworld.toDouble()
        val across = ((y - bottom) / (top - bottom)).coerceIn(0.0, 1.0)
        if (!thickestMidway) return across
        return if (across <= HALF) 2 * across * across else 1 - 2 * (1 - across) * (1 - across)
    }
}

/** The overworld's floor and its sea, which bound the ground a deposit is measured in. */
private const val OVERWORLD_BOTTOM = -64
private const val GROUND_TOP = 64
private const val HALF = 0.5

/**
 * Vanilla's own ore placements, copied from its placed and configured features: the sizes, the chance a block in
 * the open is dropped, the counts and the heights. Kept as they are written there rather than named one by one,
 * since the point of each is that it is vanilla's.
 */
private object VanillaVeins {
    private val DIAMOND_BAND = Band(Height(-80, fromTheBottom = true), Height(80, fromTheBottom = true), thickestMidway = true)
    private val COPPER_BAND = Band(Height(-16), Height(112), thickestMidway = true)

    val EMERALD = listOf(Vein(3, 0.0f, 100, Band(Height(-16), Height(480), thickestMidway = true)))

    val DIAMOND = listOf(
        Vein(4, 0.5f, 7, DIAMOND_BAND),
        Vein(8, 0.5f, 2, Band(Height(-64), Height(-4), thickestMidway = false)),
        Vein(12, 0.7f, 1, DIAMOND_BAND, oneChunkIn = 9),
        Vein(8, 1.0f, 4, DIAMOND_BAND),
    )

    val IRON = listOf(
        Vein(9, 0.0f, 90, Band(Height(80), Height(384), thickestMidway = true)),
        Vein(9, 0.0f, 10, Band(Height(-24), Height(56), thickestMidway = true)),
        Vein(4, 0.0f, 10, Band(Height(0, fromTheBottom = true), Height(72), thickestMidway = false)),
    )

    val COPPER = listOf(Vein(10, 0.0f, 16, COPPER_BAND))

    /** Copper at half its count, laid with iron for what lies between them. */
    val COPPER_HALVED = listOf(Vein(10, 0.0f, 8, COPPER_BAND))

    /** The dripstone caves' copper, which is what makes copper the commonest ore a player sees. */
    val COPPER_LARGE = listOf(Vein(20, 0.0f, 16, COPPER_BAND))

    /** What large amounts are laid as: copper, with the dripstone caves' veins among it. */
    val LARGE_AMOUNTS = COPPER + COPPER_LARGE
}
