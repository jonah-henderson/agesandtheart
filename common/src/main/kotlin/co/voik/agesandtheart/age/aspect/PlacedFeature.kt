package co.voik.agesandtheart.age.aspect

import net.minecraft.resources.Identifier

/**
 * One feature a sentence can speak about — an ore, a flower patch, a lake, a spring — as a registry
 * object, like a [Biome] and a [StructureSet], and for the same reason: naming one should need no per-mod
 * work (§8.1).
 *
 * **A *placed* feature, which is the one vanilla actually places.** A `Feature` is the code that builds
 * the thing, a `ConfiguredFeature` is that code with its blocks chosen, and a `PlacedFeature` is a
 * configured one with the rules for where it goes. Only the last is in a biome's list, so it is the only
 * one a writer can name and have mean something.
 *
 * As with a biome, what `preset_tags/features.json` bounds is **which features a vague word can reach**,
 * not which the Age has: an Age grows whatever its biomes would until a sentence says otherwise, and a
 * feature nobody tagged is still perfectly reachable by name (§8.2).
 */
data class PlacedFeature(override val id: Identifier) : Referent {
    override val aspect = Aspect.FEATURES

    companion object {
        /** The feature [key] names, or null where it is not a well-formed id. */
        fun named(key: String): PlacedFeature? = Identifier.tryParse(key)?.let(::PlacedFeature)
    }
}
