package co.voik.agesandtheart.age.aspect

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier

/**
 * One biome a sentence can speak about — a registry object, like a [Sea], and for the same reason: naming
 * one should need no per-mod work (§8.1).
 *
 * **A biome is weighed, never seated.** [Aspect.BIOMES] holds a [Holds.WEIGHTED_SET], so an Age begins
 * with vanilla's whole table and a sentence adjusts how often each thing turns up, or trims the set with
 * `only` and `except`. Nothing is drawn between, because everything is already there.
 *
 * What the curated pool in `preset_tags/biomes.json` bounds is therefore **which biomes a vague word can
 * reach**, not which the Age has. A biome nobody tagged keeps its ordinary weight and stays perfectly
 * reachable by name — §8.2 keeping the resolver's work proportional to our curation rather than to the
 * size of the registry.
 */
data class Biome(override val id: Identifier) : RegistryReference {
    override val aspect = Aspect.BIOMES

    override val registry = Registries.BIOME

    companion object {
        /** The biome [key] names, or null where it is not a well-formed id. */
        fun named(key: String): Biome? = Identifier.tryParse(key)?.let(::Biome)
    }
}
