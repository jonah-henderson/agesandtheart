package co.voik.agesandtheart.age.reward

import net.minecraft.core.Holder
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import java.util.concurrent.ConcurrentHashMap

/**
 * **What an Age owes its writer, laid over what its biomes already place** — the ores, the craters, the
 * volcanic vents and the character materials, added to the biome's own features at the step each belongs
 * at (design §7.7).
 *
 * **One layer type and one pass**, where there were four of each. Every reward that adds something to
 * generation had written out the same function — copy the carvers, copy the features step by step, append
 * its own, build — and then wrapped the whole settings lookup in a memo of its own, so the four of them
 * stacked five closures deep and a single biome lookup during generation walked four maps. What actually
 * differs between them is two facts: which features, and at which step.
 *
 * **Ordering is the caller's and it matters.** Features are appended, so a layer laid later sits later in
 * its step; the nesting this replaces put the deposits nearest the biome's own and the character materials
 * furthest out, and `AgeGeneration` keeps that order in the list it passes.
 */
object Decoration {

    /** Features one reward adds, and the step they are added at. */
    data class Layer(val step: GenerationStep.Decoration, val features: List<Holder<PlacedFeature>>)

    /** The biomes' own [replaced] feature, wherever it is placed, wrapped by [by]. */
    data class Swap(val replaced: ResourceKey<PlacedFeature>, val by: (Holder<PlacedFeature>) -> Holder<PlacedFeature>)

    /** One layer of several features at a single step, or null where the reward laid nothing. */
    fun layerOf(step: GenerationStep.Decoration, features: List<Holder<PlacedFeature>>): Layer? =
        if (features.isEmpty()) null else Layer(step, features)

    /**
     * [base] with every layer added at its own step.
     *
     * **Remembered per biome, and the identity is the point** — `FeatureSorter` indexes the list it is
     * given by identity, so a settings object rebuilt on each lookup comes back as −1 in the middle of
     * generation. One memo covers every layer, which is the whole saving.
     */
    fun laidOver(
        base: (Holder<Biome>) -> BiomeGenerationSettings,
        layers: List<Layer>,
        swaps: List<Swap> = emptyList(),
    ): (Holder<Biome>) -> BiomeGenerationSettings {
        if (layers.isEmpty() && swaps.isEmpty()) return base
        val settled = ConcurrentHashMap<Holder<Biome>, BiomeGenerationSettings>()
        // One wrapped feature for every biome placing the original, for the same identity reason.
        val swapped = ConcurrentHashMap<ResourceKey<PlacedFeature>, Holder<PlacedFeature>>()
        fun swappedFor(feature: Holder<PlacedFeature>): Holder<PlacedFeature> {
            val key = feature.unwrapKey().orElse(null) ?: return feature
            val swap = swaps.firstOrNull { it.replaced == key } ?: return feature
            return swapped.computeIfAbsent(key) { swap.by(feature) }
        }
        return { biome -> settled.computeIfAbsent(biome) { added(base(it), layers, ::swappedFor) } }
    }

    private fun added(
        settings: BiomeGenerationSettings,
        layers: List<Layer>,
        swappedFor: (Holder<PlacedFeature>) -> Holder<PlacedFeature>,
    ): BiomeGenerationSettings {
        val built = BiomeGenerationSettings.PlainBuilder()
        settings.carvers.forEach(built::addCarver)
        settings.features().forEachIndexed { step, atStep -> atStep.forEach { built.addFeature(step, swappedFor(it)) } }
        for (layer in layers) layer.features.forEach { built.addFeature(layer.step.ordinal, it) }
        return built.build()
    }
}
