package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.phenomena.Tide
import co.voik.agesandtheart.worldgen.feature.PaperTreeGrove
import co.voik.ephemeris.sky.SkySpec
import net.minecraft.core.Holder
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.placement.CountPlacement
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement
import net.minecraft.world.level.levelgen.placement.InSquarePlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.placement.RarityFilter

/**
 * Whether an Age holds the paper tree's window, and the grove it grows where it does (design §7.1.2).
 *
 * **Two halves, both skies no vanilla world has**: a **polar sun**, circling at a constant height and never
 * setting, for perpetual twilight; and a **tide**, the flood cycle a root lives by. And only in an Age a
 * player wrote, as every reward is. It generates wherever the window is met, so there is no sapling to
 * bootstrap: write the world, and if it is right the grove is there when you arrive.
 *
 * **Read off the recipe**, so generation can ask it: the sun off the sky the recipe describes, the tide off
 * whether any moon it describes pulls the sea — `polar sun` and `tidal moon`, the two things a writer has
 * to aim.
 */
object PaperTreeWindow {

    data class Reading(val writtenByAPlayer: Boolean, val polarSun: Boolean, val tidal: Boolean) {
        val isMet: Boolean get() = writtenByAPlayer && polarSun && tidal
    }

    /** The window in [recipe], whose sky is [sky] — `AgeGeneration.skySpec`, handed in from above. */
    fun read(recipe: AgeRecipe, sky: SkySpec): Reading {
        val composition = recipe.composition
        val tidal = composition != null && Tide.isTidal(Tide.pullsIn(composition, recipe.seed))
        return Reading(recipe.authored, isPolar(sky), tidal)
    }

    /**
     * Whether the sky's deciding sun is polar: **its whole circle within a few degrees of one height, and
     * that height twilight** — from a little under the horizon, where it glows, to a little over it.
     *
     * The deciding sun is the first, which is what `Daylight.PRIMARY_SUN` follows. A sky with no sun is not
     * polar: it has no twilight, only night.
     */
    fun isPolar(sky: SkySpec): Boolean {
        val sun = sky.bodies.firstOrNull { it.phase == null } ?: return false
        val path = sun.path
        val holdsItsHeight = path.highest - path.lowest <= MOST_SWING_DEGREES
        val isTwilight = path.lowest >= LOWEST_TWILIGHT_DEGREES && path.highest <= HIGHEST_TWILIGHT_DEGREES
        return holdsItsHeight && isTwilight
    }

    /** The grove, as a decoration layer, or null where the window is not met. */
    fun layer(recipe: AgeRecipe, sky: SkySpec): Decoration.Layer? {
        if (!read(recipe, sky).isMet) return null
        return Decoration.layerOf(GenerationStep.Decoration.VEGETAL_DECORATION, listOf(GROVE))
    }

    /**
     * **One placed feature for every Age**, for the identity reason `Deposits.layer` gives: `FeatureSorter`
     * indexes by identity, so an equal one built per biome is a miss in the middle of generation.
     *
     * Tries the floor of a few columns in a chunk now and then; only one at the waterline takes, so a coast
     * carries groves in stands and a cliff carries none. Narrow enough that the window is an achievement,
     * and for playtest to tune.
     */
    private val GROVE: Holder<PlacedFeature> = Holder.direct(
        PlacedFeature(
            Holder.direct(PaperTreeGrove),
            listOf(
                RarityFilter.onAverageOnceEvery(GROVE_RARITY),
                CountPlacement.of(TRIES_A_CHUNK),
                InSquarePlacement.spread(),
                HeightmapPlacement.onHeightmap(Heightmap.Types.OCEAN_FLOOR_WG),
            ),
        ),
    )

    /**
     * How far the sun may swing and still be holding its height, and the band it must hold it in: under the
     * horizon to where the horizon still glows, over it to where the light is not yet day. The demonstration
     * `polar` path climbs to twenty-five degrees and is too high for this, deliberately.
     */
    private const val MOST_SWING_DEGREES = 4.0f
    private const val LOWEST_TWILIGHT_DEGREES = -6.0f
    private const val HIGHEST_TWILIGHT_DEGREES = 10.0f

    private const val GROVE_RARITY = 3
    private const val TRIES_A_CHUNK = 4
}
