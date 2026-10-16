package co.voik.agesandtheart.worldgen.feature

import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * What an `incongruous features` Age grows: **everything any of its biomes grows, in every one of them, at any
 * height** — ore through the whole column, trees out of their biomes (`notes/strange-words-plan.md` §4).
 *
 * Vanilla's own biome filter asks the generator for a biome's list, so a list holding everything passes it
 * everywhere and needs no seam of its own. A height band is opened to the whole world; every other rule a
 * feature carries — what it must stand on, how deep the water — is kept, so a tree still wants ground.
 *
 * **Yema keep to where they grow**: the paper tree is a reward with a window of its own, and a free-for-all
 * would hand it out.
 *
 * Built once over every biome the Age has, and remembered by identity, which `FeatureSorter` indexes by.
 */
object FreeForAll {

    fun over(
        settings: (Holder<Biome>) -> BiomeGenerationSettings,
        biomes: Collection<Holder<Biome>>,
    ): (Holder<Biome>) -> BiomeGenerationSettings {
        val own = biomes.associateWith(settings)
        val everything: List<List<Holder<PlacedFeature>>> by lazy { everythingIn(own.values) }
        val settled = ConcurrentHashMap<Holder<Biome>, BiomeGenerationSettings>()
        return { biome ->
            settled.computeIfAbsent(biome) {
                val its = own[it] ?: settings(it)
                val built = BiomeGenerationSettings.PlainBuilder()
                its.carvers.forEach(built::addCarver)
                everything.forEachIndexed { step, atStep -> atStep.forEach { feature -> built.addFeature(step, feature) } }
                its.features().forEachIndexed { step, atStep ->
                    atStep.filter(::isYema).forEach { feature -> built.addFeature(step, feature) }
                }
                built.build()
            }
        }
    }

    /** Every feature but the Yema, step by step, each once and opened to any height. */
    private fun everythingIn(settings: Collection<BiomeGenerationSettings>): List<List<Holder<PlacedFeature>>> {
        val steps = settings.maxOfOrNull { it.features().size } ?: 0
        val opened = IdentityHashMap<Holder<PlacedFeature>, Holder<PlacedFeature>>()
        return (0..<steps).map { step ->
            val seen = IdentityHashMap<Holder<PlacedFeature>, Unit>()
            settings.flatMap { it.features().getOrNull(step)?.toList().orEmpty() }
                .filterNot(::isYema)
                .filter { seen.put(it, Unit) == null }
                .map { opened.getOrPut(it) { atAnyHeight(it) } }
        }
    }

    private fun isYema(feature: Holder<PlacedFeature>): Boolean {
        val grows = feature.value().feature().value()
        return grows === PaperTree || grows === PaperTreeGrove
    }

    /** [placed] with any height band it carries opened to the whole column. */
    private fun atAnyHeight(placed: Holder<PlacedFeature>): Holder<PlacedFeature> {
        val placement = placed.value().placement()
        if (placement.none { it is HeightRangePlacement }) return placed
        val anywhere = HeightRangePlacement.of(UniformHeight.of(VerticalAnchor.bottom(), VerticalAnchor.top()))
        return Holder.direct(PlacedFeature(placed.value().feature(), placement.map { if (it is HeightRangePlacement) anywhere else it }))
    }
}
