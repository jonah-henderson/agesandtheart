package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Price
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import co.voik.agesandtheart.worldgen.feature.RimeCrystal
import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import net.minecraft.world.level.levelgen.placement.CountPlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import java.util.concurrent.ConcurrentHashMap

/**
 * What an Age grows for **being a certain way** (design §7.1.2) — the earliest of the three material
 * classes, and the one that pays for having written many unlike Ages.
 *
 * **Breadth rather than commitment.** The danger material asks a writer to aim one axis hard; these ask
 * only that the Ages differ from one another, so they are meant to be easy to find and impossible to
 * gather without variety. Rime is the first: a cold Age growing crystals on its cliffs.
 *
 * **Both gates are hard** (Jonah, 2026-09-05): an Age must be cold enough that nothing thaws *and* have a
 * blizzard in it. Gear up to face hostile cold, or go without.
 */
object CharacterMaterials {

    /**
     * Whether rime grows here at all.
     *
     * **Read from the recipe, like everything else that pays out**, so the answer is the same before the
     * Age is opened as after — which is what lets the geologic survey (§7.7) say so at the desk.
     */
    fun growsRime(
        composition: AgeComposition,
        spending: Spending,
        prices: Map<Manifestation, Price>,
    ): Boolean = neverThaws(composition) && aBlizzardBlows(composition, spending, prices)

    /**
     * Whether every one of the Age's climates tops out at or below the snow.
     *
     * **The whole Age, not the coldest part of it.** A world with one frozen corner is not a frozen world,
     * and a crystal that grew in it would be a reward for a corner. `-0.45` is where `OverworldBiomeBuilder`
     * itself stops calling a temperature snowy — see [ClimateAxis.landmarks], which reads the same table.
     */
    private fun neverThaws(composition: AgeComposition): Boolean {
        val territories = composition.membersIn(Aspect.CLIMATE).coerceAtLeast(ONE_CLIMATE)
        return (0..<territories).all { member ->
            val bounded = Span.read(
                composition.optionsFor(Aspect.CLIMATE, member).of(ClimateAxis.TEMPERATURE.parameter),
            )
            bounded != null && bounded != Span.NATURAL && bounded.most <= SNOWY
        }
    }

    /**
     * Whether a blizzard blows here, written or inflicted.
     *
     * **Inflicted counts.** A blizzard an Age fell into is still a blizzard to stand in, and the cold gate
     * beside it already refuses the case where that would be absurd — an inflicted storm in a hot Age has
     * no teeth and no crystals either.
     */
    private fun aBlizzardBlows(
        composition: AgeComposition,
        spending: Spending,
        prices: Map<Manifestation, Price>,
    ): Boolean {
        val written = Phenomena.claimsIn(composition.optionsFor(Aspect.PHENOMENA, 0))
            .any { it.value == Phenomenon.BLIZZARD.key }
        val manifestation = Phenomenon.BLIZZARD.inflictedBy ?: return written
        return written || spending.reach(manifestation, prices) > NOTHING_INFLICTED
    }

    /**
     * [base]'s settings with rime added where an Age grows it, or [base] itself where it does not.
     *
     * One `PlacedFeature` for the whole Age and the settings remembered per biome, for the reason
     * [Deposits] spells out: the sorted feature list is indexed by identity, and an equal-but-new object is
     * a lookup miss in the middle of generation.
     */
    fun laidOver(
        base: (Holder<Biome>) -> BiomeGenerationSettings,
        grows: Boolean,
    ): (Holder<Biome>) -> BiomeGenerationSettings {
        if (!grows) return base
        val crystals = Holder.direct(
            PlacedFeature(
                Holder.direct(ConfiguredFeature(RimeCrystal, NoneFeatureConfiguration.INSTANCE)),
                // **Once per chunk, because the feature scans the chunk itself.** Scattering it would spend
                // nearly every attempt on flat ground, a sheer face standing at well under one per cent of
                // columns — see `SheerFace`.
                listOf(CountPlacement.of(ONE_PASS)),
            ),
        )
        val settled = ConcurrentHashMap<Holder<Biome>, BiomeGenerationSettings>()
        return { biome -> settled.computeIfAbsent(biome) { added(base(it), crystals) } }
    }

    private fun added(
        settings: BiomeGenerationSettings,
        crystals: Holder<PlacedFeature>,
    ): BiomeGenerationSettings {
        val built = BiomeGenerationSettings.PlainBuilder()
        settings.carvers.forEach(built::addCarver)
        settings.features().forEachIndexed { step, atStep -> atStep.forEach { built.addFeature(step, it) } }
        built.addFeature(GenerationStep.Decoration.LOCAL_MODIFICATIONS.ordinal, crystals)
        return built.build()
    }

    /**
     * Where `OverworldBiomeBuilder` stops calling a temperature snowy, read off [ClimateAxis.landmarks].
     *
     * Written as the same number rather than derived from the landmark list, because a landmark is a label
     * on a table and this is a rule about an Age — they agree today and are allowed to stop agreeing.
     */
    private const val SNOWY = -0.45

    private const val ONE_CLIMATE = 1
    private const val ONE_PASS = 1
    private const val NOTHING_INFLICTED = 0.0
}
