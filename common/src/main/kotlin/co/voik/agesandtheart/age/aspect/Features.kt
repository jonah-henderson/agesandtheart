package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.worldgen.feature.FeatureDensity
import co.voik.agesandtheart.worldgen.feature.FeatureShape
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.placement.PlacedFeature as VanillaPlacedFeature
import java.util.concurrent.ConcurrentHashMap

/**
 * What is placed in and on the ground — ores, flora, lakes, springs (design §3.1, vanilla's `feature` and
 * `GenerationStep.Decoration`). **Whatever the biomes would place**, until a sentence says otherwise.
 *
 * No preset, for the same reason biomes and structures have none: an Age does not pick one of a few ways
 * to be decorated, it starts from what its biomes carry and a sentence adjusts it. [PLACES] is where the
 * writing happens — naming a feature asks for it, `except` strikes one out, `only` keeps just what was
 * named.
 *
 * **Features are per biome in vanilla, and this is per Age**, which is the one real departure. Vanilla
 * resolves a feature list through the biome, so scoping *this* aspect to a biome is a thing the language
 * could eventually say (§4.3.1's `in <biome>`); until it does, an Age's answer covers all of them.
 *
 * §7.2 is why it is load-bearing rather than decoration: "write an Age that supplies an ink farm" needs a
 * writer to be able to say what is *in* the ground.
 */
object Features {

    /**
     * What is placed here — populative, with `only`/`except` to narrow (§3.2, [Claim]). Its values are
     * *placed* features. Named `places` to avoid `features.features`.
     *
     * A mention is worth the **ordinary** amount, like a structure set and unlike a biome: naming a
     * feature asks for a thing that was not there rather than for more of a thing that was. And it may be
     * emptied — a world where nothing is placed is a world — so [NOTHING] is what says so.
     *
     * **A rung here is absolute and takes nothing from anything else** ([FeatureDensity]): twice the trees
     * is twice the trees, where twice the desert is necessarily less of some other biome.
     */
    val PLACES = Parameter.population("grows", leastKept = NOTHING_AT_ALL, emptiedBy = NOTHING).perBiome()

    /** How an Age says nothing is placed here at all: bare ground, whatever its biomes would have carried. */
    const val NOTHING = "nothing"

    /**
     * How big one of a thing is, how thick a patch of it is, and how deep in the column it sits — the
     * three knobs [FeatureShape] found worth turning in the whole of vanilla's feature data.
     *
     * **Dials rather than rungs, and the difference is what each is about.** A rung says how many of one
     * named thing there are; these say what *this Age* is like, so they apply to everything it grows. A
     * word bends them exactly as `arid` bends a climate axis.
     */
    val SIZE = Parameter.ranged("size").perBiome()
    val THICKNESS = Parameter.ranged("thickness").perBiome()
    val HEIGHT = Parameter.ranged("height").perBiome()

    private const val NOTHING_AT_ALL = 0.0

    /**
     * A biome's own generation settings, adjusted by whatever the sentence said — the function
     * `ChunkGenerator` takes for exactly this and `NoiseBasedChunkGenerator` never passes on.
     *
     * Returns the settings **unchanged** where nothing was said, so an Age nobody spoke to about growing
     * things is decorated by its biomes alone and pays nothing for the seam.
     */
    fun placedIn(
        server: MinecraftServer,
        options: Options,
        salt: Long,
        rock: List<BlockState>,
    ): (Holder<Biome>) -> BiomeGenerationSettings {
        val claims = options.claimsOn(PLACES)
        val asked = Population.of(claims)
        val shape = Shape(
            size = options.steer(SIZE, salt),
            thickness = options.steer(THICKNESS, salt),
            height = options.steer(HEIGHT, salt),
            // **Not a knob a writer turns.** An ore replaces what its rule matches, and vanilla's rules
            // match vanilla's stone — so an Age made of anything else grows no ore at all unless the rock
            // it *is* gets added to them. Carried here because this is where a feature is rebuilt.
            rock = rock,
        )
        val scoped = claims.any { it.confinedTo != null }
        if (asked.isSilent && !scoped && shape.asksForNothing) {
            return { biome -> biome.value().generationSettings }
        }
        // **Answered once per biome and remembered, and that is a correctness rule rather than a saving.**
        // `FeatureSorter` indexes the sorted feature list by **identity** (`createIndexIdentityLookup`), and
        // `applyBiomeDecoration` looks a feature up in that index every chunk. Hand it an equal-but-new
        // `PlacedFeature` the second time and the lookup misses, which is -1 into a list.
        val settled = ConcurrentHashMap<Holder<Biome>, BiomeGenerationSettings>()
        return { biome ->
            settled.computeIfAbsent(biome) {
                // A claim confined to one biome (§4.3.1) is absent from every other, so each biome's
                // settings are built from what applies *there*.
                val here = Population.of(claims, it.unwrapKey().orElse(null)?.identifier())
                settingsFrom(
                    it,
                    wanted(server, here),
                    here.struck.mapNotNull(Identifier::tryParse).toSet(),
                    here.exclusive || here.wanted.any { claim -> claim.value == NOTHING },
                    shape,
                )
            }
        }
    }

    /** The three dials together, since every one of them travels to the same place. */
    private data class Shape(
        val size: Double?,
        val thickness: Double?,
        val height: Double?,
        val rock: List<BlockState>,
    ) {
        /** Nothing turned, and nothing to reach — the case where a feature is handed back untouched. */
        val asksForNothing: Boolean
            get() = FeatureShape.asksForNothing(size, thickness, height) && FeatureShape.oresCanReach(rock)

        fun applied(feature: Holder<VanillaPlacedFeature>): Holder<VanillaPlacedFeature> =
            if (asksForNothing) feature else FeatureShape.reshaped(feature, size, thickness, height, rock)
    }

    /**
     * The features the sentence asked for, each with the step it belongs in.
     *
     * **A placed feature does not know its own step** — the step is the *index* of the list it sits in, so
     * it is a fact about the biome rather than about the feature. So a named feature is given the step it
     * already occupies wherever the pack uses it, and [ORPHAN_STEP] only where nothing does: an ore asked
     * for by name lands among the ores, and a flower among the flowers, with no table of ours to maintain.
     */
    private fun wanted(server: MinecraftServer, asked: Population): Map<Int, List<Holder<VanillaPlacedFeature>>> {
        val features = server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)
        val biomes = server.registryAccess().lookupOrThrow(Registries.BIOME)
        val byStep = mutableMapOf<Int, MutableList<Holder<VanillaPlacedFeature>>>()
        for (claim in asked.wanted) {
            if (claim.value == NOTHING) continue
            val named = Identifier.tryParse(claim.value) ?: continue
            val found = features.get(ResourceKey.create(Registries.PLACED_FEATURE, named)).orElse(null)
            if (found == null) {
                Constants.LOG.warn("An Age asked to grow '{}', which is no placed feature in this pack", named)
                continue
            }
            byStep.getOrPut(stepFor(named, biomes)) { mutableListOf() } += FeatureDensity.applied(found, claim.density)
        }
        return byStep
    }

    /** Which step a feature sits in wherever this pack already uses it — see [wanted]. */
    private fun stepFor(feature: Identifier, biomes: HolderLookup<Biome>): Int {
        for (biome in biomes.listElements()) {
            biome.value().generationSettings.features().forEachIndexed { step, atStep ->
                if (atStep.any { it.unwrapKey().orElse(null)?.identifier() == feature }) return step
            }
        }
        return ORPHAN_STEP
    }

    /**
     * Where a feature nothing else places goes: among the vegetation, which is where a thing that grows
     * belongs and the step that runs after the ground is settled.
     */
    private val ORPHAN_STEP = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal

    /**
     * One biome's settings with the sentence applied: everything named added at the step it belongs in,
     * everything struck out dropped, and the biome's own list left out entirely where the writer said
     * `only` or [NOTHING].
     *
     * **Carvers are carried across untouched.** They live on these settings too and belong to the Carvers
     * aspect, which reaches them through the generator — so this rebuilds the feature half and copies the
     * other, rather than silently emptying a world of its caves.
     */
    private fun settingsFrom(
        biome: Holder<Biome>,
        added: Map<Int, List<Holder<VanillaPlacedFeature>>>,
        struck: Set<Identifier>,
        startsFromNothing: Boolean,
        shape: Shape,
    ): BiomeGenerationSettings {
        val own = biome.value().generationSettings
        val built = BiomeGenerationSettings.PlainBuilder()
        // 26.1 keeps one carver list per biome rather than one per carving step, so this is a copy
        // rather than a walk over steps.
        own.carvers.forEach(built::addCarver)
        val kept = if (startsFromNothing) emptyList() else own.features()
        kept.forEachIndexed { step, atStep ->
            atStep.filterNot { idOf(it) in struck }
                .forEach { feature -> built.addFeature(step, shape.applied(feature)) }
        }
        for ((step, features) in added) {
            features.forEach { feature -> built.addFeature(step, shape.applied(feature)) }
        }
        return built.build()
    }

    private fun idOf(feature: Holder<VanillaPlacedFeature>): Identifier? =
        feature.unwrapKey().orElse(null)?.identifier()
}
