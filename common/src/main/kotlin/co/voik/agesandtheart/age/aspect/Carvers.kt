package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.carver.Porosity
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.WaterTable
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver

/**
 * What is going on beneath the surface: what has been cut back out of the rock, and where water stands
 * inside it. One preset names both, so the pairing cannot be got wrong by composing carelessly.
 *
 * Named in the recipe rather than inherited from a biome — field Ages sit on biomes that carry none. See
 * [co.voik.agesandtheart.generation.AgeChunkGenerator.applyCarvers].
 */
enum class Carvers(override val key: String) : AuthoredPreset {
    /**
     * Solid rock: whatever the shape laid down stays there. The empty set, and so the identity of the
     * union — carving is populative, so `solid ∪ caves` is `caves`.
     */
    SOLID("solid"),

    /**
     * Rock riddled with small pockets, wet near the surface and dry far below — isolated voids you break
     * into rather than tunnels you walk along.
     */
    POROUS("porous"),

    /** Vanilla's caves and canyons, running mostly dry, with flooded pockets where the rock is wet. */
    CAVES("caves"),

    /** The same caves, but the water table sits at the sea, so everything below it floods. */
    FLOODED_CAVES("flooded_caves"),

    ;

    override val aspect = Aspect.CARVERS

    override fun getSerializedName(): String = key

    /**
     * The carvers this preset runs, resolved from the registry the Age is being opened against.
     *
     * **One set rather than a map per step.** Vanilla used to run carving twice — an air pass and a liquid
     * one, keyed by `GenerationStep.Carving` — and collapsed the two into a single pass. We only ever
     * populated the air half, so the change costs nothing here and removes a map that had one key.
     */
    fun configuredCarvers(server: MinecraftServer): HolderSet<ConfiguredWorldCarver<*>> {
        val configured = server.registryAccess().lookupOrThrow(Registries.CONFIGURED_CARVER)
        val keys = when (this) {
            SOLID -> return HolderSet.direct()
            CAVES, FLOODED_CAVES -> UNDERGROUND_CARVERS.map(::vanillaCarver)
            // Small vugs rather than tunnels, which gives this preset's water table something to stand in.
            POROUS -> listOf(ResourceKey.create(Registries.CONFIGURED_CARVER, POROSITY))
        }
        return HolderSet.direct(keys.map(configured::getOrThrow))
    }

    /** Where water stands in the rock. Null wants no table at all. [seed] varies it per Age. */
    fun waterTable(seaFill: SeaFill, seed: Long): WaterTable? = when (this) {
        SOLID -> null
        // Dry enough to walk, with wet pockets. A world with no sea has no waterline to hang a table on.
        CAVES, POROUS -> seaFill.surfaceY?.let { WaterTable.matching(seaFill, seaLevel = seaFill.level, seed = seed) }
        FLOODED_CAVES -> seaFill.surfaceY?.let {
            WaterTable.matching(seaFill, seaLevel = seaFill.level, seed = seed).copy(floods = true)
        }
    }

    companion object {
        private val UNDERGROUND_CARVERS = listOf("cave", "cave_extra_underground", "canyon")
        private val POROSITY: Identifier = "porosity".location()

        private fun vanillaCarver(name: String): ResourceKey<ConfiguredWorldCarver<*>> =
            ResourceKey.create(Registries.CONFIGURED_CARVER, Identifier.withDefaultNamespace(name))
    }
}
