package co.voik.agesandtheart.age.aspect

import net.minecraft.resources.Identifier

/**
 * One kind of creature a sentence can speak about — a registry object, like a [Biome], a [StructureSet]
 * and a [PlacedFeature], and for the same reason: naming one should need no per-mod work (§8.1).
 *
 * **What lives here naturally, and nothing else.** An Age may refuse to *grow* life; a writer may always
 * bring it. That line is vanilla's own, drawn in `EntitySpawnReason` between `NATURAL`, `CHUNK_GENERATION`
 * and `SPAWNER` on one side and `BREEDING`, `SPAWN_EGG` and `BUCKET` on the other, and this aspect only
 * ever touches the first.
 *
 * As with a biome, `preset_tags/spawns.json` bounds **which creatures a vague word can reach**, not which
 * the Age has: an Age holds whatever its biomes would until a sentence says otherwise, and a creature
 * nobody tagged is still perfectly reachable by name (§8.2).
 */
data class Spawn(override val id: Identifier) : Referent {
    override val aspect = Aspect.SPAWNS

    companion object {
        /** The creature [key] names, or null where it is not a well-formed id. */
        fun named(key: String): Spawn? = Identifier.tryParse(key)?.let(::Spawn)
    }
}
