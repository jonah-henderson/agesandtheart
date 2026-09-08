package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.worldgen.feature.CraterScale
import co.voik.agesandtheart.worldgen.feature.ImpactCrater
import net.minecraft.core.Holder
import net.minecraft.util.RandomSource
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement
import net.minecraft.world.level.levelgen.placement.InSquarePlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.placement.RarityFilter
import kotlin.math.roundToInt

/**
 * The craters a meteoric Age was already wearing when its writer arrived (design §5.2).
 *
 * **Astrite requires confronting meteors, the way temperstone requires volcanoes** (Jonah, 2026-09-07).
 * So this rides the phenomenon and nothing else: the `craterlands` landform makes bowl-shaped country and
 * gets none of these, because what a crater means here is that something hit the place. A page that lets a
 * writer ask for the craters without the storms is a deliberate later exception, not a gap.
 *
 * **And not every meteoric Age has them.** An Age that has been struck for centuries reads differently
 * from one where the storms have only just begun, and both should exist — so it is a roll off the seed
 * rather than a rule, which keeps it a property of the world rather than of the recipe.
 */
object Craters {

    /**
     * Whether this composition asks for the storms that leave craters.
     *
     * Read off the claim like [co.voik.agesandtheart.age.aspect.Volcanoes.askedFor], so a recipe can be
     * asked before it has ever been built.
     */
    fun askedFor(composition: AgeComposition): Boolean = claimIn(composition) != null

    /**
     * [base]'s settings with old craters laid through them, or [base] itself for an Age that has none.
     *
     * One `PlacedFeature` for the whole Age and the settings remembered per biome, for the reason
     * [Deposits] spells out: the sorted feature list is indexed by identity, and an equal-but-new object
     * is a lookup miss in the middle of generation.
     */
    fun layer(composition: AgeComposition, seed: Long): Decoration.Layer? {
        val claim = claimIn(composition) ?: return null
        if (!pockmarked(seed)) return null
        // Before the ores and everything that grows, so a crater is decorated rather than cutting through
        // decoration — a bowl carved after the trees would leave them standing in the air over it.
        return Decoration.layerOf(
            GenerationStep.Decoration.LOCAL_MODIFICATIONS,
            listOf(small(claim.density), large(claim.density)),
        )
    }

    /**
     * The ordinary ones: pocks in the country, most of them empty.
     *
     * A reach of five to eleven is exactly what fits from anywhere in a chunk, so these are never pinned
     * to a chunk's middle and so never line up with each other.
     */
    private fun small(density: Double): Holder<PlacedFeature> =
        scattered(CraterScale(SMALL_LEAST, SMALL_MOST, SMALL_KEEPS), rarityFor(SMALL_EVERY, density))

    /**
     * And the rare big one, which is a landmark.
     *
     * Its reach runs to the ceiling the chunk pyramid allows, so the largest are pinned to a chunk's
     * middle by [ImpactCrater] — the only place in the pack where a feature's size decides where it may
     * go rather than the other way round.
     */
    private fun large(density: Double): Holder<PlacedFeature> =
        scattered(CraterScale(LARGE_LEAST, LARGE_MOST, LARGE_KEEPS), rarityFor(LARGE_EVERY, density))

    private fun scattered(scale: CraterScale, rarity: Int): Holder<PlacedFeature> =
        Holder.direct(
            PlacedFeature(
                Holder.direct(ConfiguredFeature(ImpactCrater, scale)),
                listOf(
                    RarityFilter.onAverageOnceEvery(rarity),
                    InSquarePlacement.spread(),
                    HeightmapPlacement.onHeightmap(Heightmap.Types.OCEAN_FLOOR_WG),
                ),
            ),
        )

    /**
     * How often one is tried, in chunks — **fewer chunks apart the harder the Age is hit**.
     *
     * The rung that buys more storms bought more of them for longer before anybody arrived, so the same
     * number reads the landscape and the weather. Divided rather than subtracted, like the wait between
     * storms, so the ends of the range stay proportionate however the middle is tuned.
     */
    private fun rarityFor(ordinary: Int, density: Double): Int =
        (ordinary / (density / Rung.ORDINARY).coerceAtLeast(A_TRICKLE)).roundToInt().coerceAtLeast(ONE)

    /**
     * Whether this Age wears them at all, drawn once off its seed.
     *
     * Its own source rather than the world's, so asking the question cannot move any other draw — an Age
     * with craters and the same Age without must differ in the craters and nothing else.
     */
    private fun pockmarked(seed: Long): Boolean =
        RandomSource.create(seed xor STRUCK_BEFORE).nextFloat() < OFTEN_ENOUGH

    private fun claimIn(composition: AgeComposition) =
        Phenomena.claimFor(composition.optionsFor(Aspect.PHENOMENA, 0), Phenomenon.METEORS)


    /** How many meteoric Ages wear their history. Most, so one that does not is the surprise. */
    private const val OFTEN_ENOUGH = 0.8f

    /** A salt of its own, so this draw is not one the terrain could have made. */
    private const val STRUCK_BEFORE = 0x43_52_41_54_45_52L

    private const val SMALL_LEAST = 5
    private const val SMALL_MOST = 11
    private const val SMALL_KEEPS = 1
    private const val SMALL_EVERY = 28

    private const val LARGE_LEAST = 12
    private const val LARGE_MOST = 16
    private const val LARGE_KEEPS = 5
    private const val LARGE_EVERY = 420

    private const val A_TRICKLE = 0.1
    private const val ONE = 1
}
