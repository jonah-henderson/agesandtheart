package co.voik.agesandtheart.age.aspect

import net.minecraft.resources.Identifier

/**
 * One structure set a sentence can speak about — a registry object, like a [Biome] and a [Sea], and for
 * the same reason: naming one should need no per-mod work (§8.1).
 *
 * **A set, never a structure.** Within the overworld sets holding several, the members are biome
 * variations of one idea and vanilla re-rolls a set's selection until something fits the biome, so
 * per-structure control would mostly be a knob that did nothing. `minecraft:nether_complexes` is the one
 * set whose members genuinely differ, and it is split with data rather than code — see [Structures].
 *
 * As with a biome, what `preset_tags/structures.json` bounds is **which sets a vague word can reach**, not
 * which the Age has: an Age builds whatever vanilla would until a sentence says otherwise, and a set
 * nobody tagged is still perfectly reachable by name (§8.2).
 */
data class StructureSet(override val id: Identifier) : Referent {
    override val aspect = Aspect.STRUCTURES

    companion object {
        /** The set [key] names, or null where it is not a well-formed id. */
        fun named(key: String): StructureSet? = Identifier.tryParse(key)?.let(::StructureSet)
    }
}
