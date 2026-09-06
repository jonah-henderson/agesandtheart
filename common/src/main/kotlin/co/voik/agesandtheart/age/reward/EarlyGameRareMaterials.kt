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
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.worldgen.feature.RimeCrystal
import co.voik.agesandtheart.worldgen.feature.TemperedGround
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement
import net.minecraft.world.level.levelgen.placement.InSquarePlacement
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest
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
 * **Named for when they are wanted rather than for where they come from** (Jonah, 2026-09-06). These were
 * `CharacterMaterials`, which described the *gate* — an Age's character — and said nothing about what the
 * class is for. What it is for is the early game: **these are what the first upgrades are built from**,
 * the desk implements of §7.4, where the D'ni instruments sit a rung above them. One of them is not
 * literally early (the deep-ocean material is the last of them and needs protection to reach at all), and
 * that is accepted: it serves the same purpose, and a class name that tracked the exception would describe
 * nothing.
 *
 * **Breadth rather than commitment.** The danger material asks a writer to aim one axis hard; these ask
 * only that the Ages differ from one another, so they are meant to be easy to find and impossible to
 * gather without variety. Rime is the first: a cold Age growing crystals on its cliffs.
 *
 * **How they are collected is the point, not that they are ores.** Six of these placed as ore in six
 * biomes would be one reward found six times; each is meant to be won by a method peculiar to the Age that
 * holds it. Rime's is the mildest of them — climb to a sheer face — and the rest are designed in §7.1.2.
 *
 * **Both of rime's gates are hard** (Jonah, 2026-09-05): an Age must be cold enough that nothing thaws
 * *and* have a blizzard in it. Gear up to face hostile cold, or go without.
 */
object EarlyGameRareMaterials {

    /**
     * Which of them this Age grows — the half of the geologic survey that reports names (§7.7).
     *
     * The `when` is exhaustive on purpose: a new material has to say what conditions grow it, rather than
     * being added to the enum and quietly growing nowhere.
     */
    fun grownIn(
        composition: AgeComposition,
        spending: Spending,
        prices: Map<Manifestation, Price>,
    ): Set<EarlyGameRareMaterial> = EarlyGameRareMaterial.entries
        .filter { material ->
            when (material) {
                EarlyGameRareMaterial.RIME -> growsRime(composition, spending, prices)
                EarlyGameRareMaterial.TEMPERSTONE -> bakesTemperstone(composition)
            }
        }
        .toSet()

    /**
     * Whether stone is baked into temperstone here.
     *
     * Read from the recipe like everything else that pays out, so the desk's survey can answer before the
     * Age is opened. Both halves are needed: heat without lava has nothing to bake against, and lava in a
     * cold Age is a hazard rather than a kiln.
     */
    fun bakesTemperstone(composition: AgeComposition): Boolean =
        neverCools(composition) && standsInLava(composition)

    /**
     * Whether every one of the Age's climates bottoms out at or above the desert.
     *
     * The mirror of [neverThaws], and the same reasoning: an Age with one hot corner is not a hot world.
     * `0.55` is where `OverworldBiomeBuilder` starts calling a temperature desert — see
     * [ClimateAxis.landmarks], which reads the same table.
     */
    private fun neverCools(composition: AgeComposition): Boolean {
        val territories = composition.membersIn(Aspect.CLIMATE).coerceAtLeast(ONE_CLIMATE)
        return (0..<territories).all { member ->
            val bounded = Span.read(
                composition.optionsFor(Aspect.CLIMATE, member).of(ClimateAxis.TEMPERATURE.parameter),
            )
            bounded != null && bounded != Span.NATURAL && bounded.least >= DESERT
        }
    }

    /** Whether any of the Age's seas is lava, which is what puts heat against rock at all. */
    private fun standsInLava(composition: AgeComposition): Boolean =
        composition.seas.any { sea -> !sea.isEmpty && sea.id == LAVA }

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
        grown: Set<EarlyGameRareMaterial>,
    ): (Holder<Biome>) -> BiomeGenerationSettings {
        if (grown.isEmpty()) return base
        val laid = grown.flatMap(::placementsOf)
        val settled = ConcurrentHashMap<Holder<Biome>, BiomeGenerationSettings>()
        return { biome -> settled.computeIfAbsent(biome) { added(base(it), laid) } }
    }

    /** How a material is placed — one pass, or several where the material arrives more than one way. */
    private fun placementsOf(material: EarlyGameRareMaterial): List<Holder<PlacedFeature>> = when (material) {
        EarlyGameRareMaterial.RIME -> listOf(scanningTheChunk(RimeCrystal))
        EarlyGameRareMaterial.TEMPERSTONE -> listOf(scanningTheChunk(TemperedGround), rawBlobs())
    }

    /**
     * A feature that walks the chunk itself, run once over it.
     *
     * Scattering these would waste nearly every attempt: a sheer face stands at well under one per cent of
     * columns, and lava is found by walking out from where it already is rather than by sampling for it.
     */
    private fun scanningTheChunk(feature: Feature<NoneFeatureConfiguration>): Holder<PlacedFeature> =
        Holder.direct(
            PlacedFeature(
                Holder.direct(ConfiguredFeature(feature, NoneFeatureConfiguration.INSTANCE)),
                listOf(CountPlacement.of(ONE_PASS)),
            ),
        )

    /**
     * Raw temperstone scattered through the rock, away from any lava.
     *
     * **The formation teaches and this supplies.** What a player reads off the bands around lava is a rule
     * they then have to work, and working it needs more raw stone than one lava shore holds — so this is
     * deliberately common, on vanilla's own ore machinery, and it is the *raw* form only. Tempering it is
     * the player's job.
     */
    private fun rawBlobs(): Holder<PlacedFeature> {
        val targets = listOf(
            OreConfiguration.target(
                TagMatchTest(BlockTags.BASE_STONE_OVERWORLD),
                AgeContent.RAW_TEMPERSTONE_BLOCK.defaultBlockState(),
            ),
            OreConfiguration.target(
                TagMatchTest(BlockTags.BASE_STONE_NETHER),
                AgeContent.RAW_TEMPERSTONE_BLOCK.defaultBlockState(),
            ),
        )
        return Holder.direct(
            PlacedFeature(
                Holder.direct(ConfiguredFeature(Feature.ORE, OreConfiguration(targets, BLOB_SIZE))),
                listOf(
                    CountPlacement.of(BLOBS_PER_CHUNK),
                    InSquarePlacement.spread(),
                    HeightRangePlacement.uniform(VerticalAnchor.bottom(), VerticalAnchor.top()),
                ),
            ),
        )
    }

    private fun added(
        settings: BiomeGenerationSettings,
        laid: List<Holder<PlacedFeature>>,
    ): BiomeGenerationSettings {
        val built = BiomeGenerationSettings.PlainBuilder()
        settings.carvers.forEach(built::addCarver)
        settings.features().forEachIndexed { step, atStep -> atStep.forEach { built.addFeature(step, it) } }
        laid.forEach { built.addFeature(GenerationStep.Decoration.LOCAL_MODIFICATIONS.ordinal, it) }
        return built.build()
    }

    /**
     * Where `OverworldBiomeBuilder` stops calling a temperature snowy, read off [ClimateAxis.landmarks].
     *
     * Written as the same number rather than derived from the landmark list, because a landmark is a label
     * on a table and this is a rule about an Age — they agree today and are allowed to stop agreeing.
     */
    private const val SNOWY = -0.45

    /** Where `OverworldBiomeBuilder` starts calling a temperature desert, read off [ClimateAxis.landmarks]. */
    private const val DESERT = 0.55

    private val LAVA: Identifier = Identifier.withDefaultNamespace("lava")

    /**
     * How much raw stone an Age that bakes it holds.
     *
     * **Generous on purpose**: iron runs at twenty a chunk and this is meant to be the thing you have
     * plenty of and must do something with, where the tempered form is what you have to work for.
     */
    private const val BLOB_SIZE = 9
    private const val BLOBS_PER_CHUNK = 14

    private const val ONE_CLIMATE = 1
    private const val ONE_PASS = 1
    private const val NOTHING_INFLICTED = 0.0
}

/**
 * A material an Age grows for being a certain way, named rather than measured — see [Survey].
 *
 * **The `when` in [EarlyGameRareMaterials.grownIn] is exhaustive on purpose**, so an entry added here has
 * to say what conditions grow it rather than quietly growing nowhere.
 *
 * It holds no block: what a survey line reads is the material's own item name, looked up where the item
 * registry is safe to touch, so the report can never call it something the player does not see on the
 * block. Naming one here would also class-initialise `AgeContent`, which no offline check survives.
 */
enum class EarlyGameRareMaterial {
    RIME,
    TEMPERSTONE,
}
