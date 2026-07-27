package co.voik.agesandtheart.age.slot

import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.field.AmbientMedium
import co.voik.agesandtheart.worldgen.field.WaterTable
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver

/**
 * What is going on beneath the surface: what has been cut back out of the rock, and where water stands
 * inside it.
 *
 * The two belong together because they are answers to the same question. Carving a cave system and
 * then flooding it to the waterline gives you a drowned Age nobody can walk; carving one and leaving it
 * dry gives you the thing players actually go underground for. So a subsurface preset names both, and
 * the pairing cannot be got wrong by composing carelessly.
 *
 * Carvers are named here rather than inherited from a biome deliberately — field Ages sit on biomes
 * that carry none, and an Age already describes its whole world as replayable data, so its carvers
 * belong in the recipe. See [co.voik.agesandtheart.worldgen.FieldChunkGenerator.applyCarvers].
 */
enum class Subsurface(override val key: String) : SlotPreset {
    /** Solid rock. Whatever the shape laid down stays there. */
    SOLID("solid"),

    /**
     * Nothing is cut, but the rock is wet through: water stands where the stone is porous and the deep
     * runs dry. What a landform whose caves *are* its shape wants — there is nothing left to carve, and
     * a flat table at the sea would simply drown it.
     */
    POROUS("porous"),

    /** Vanilla's caves and canyons, running mostly dry, with flooded pockets where the rock is wet. */
    CAVES("caves"),

    /** The same caves, but the water table sits at the sea, so everything below it floods. */
    FLOODED_CAVES("flooded_caves"),

    /** Our own wind erosion — the pass that pares blocky masses back to ribs and spires. */
    WEATHERED("weathered"),
    ;

    override val slot = Slot.SUBSURFACE

    override fun getSerializedName(): String = key

    /** The carvers this preset runs, resolved from the registry the Age is being opened against. */
    fun carvers(server: MinecraftServer): Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>> {
        val configured = server.registryAccess().lookupOrThrow(Registries.CONFIGURED_CARVER)
        val keys = when (this) {
            SOLID, POROUS -> return emptyMap()
            CAVES, FLOODED_CAVES -> UNDERGROUND_CARVERS.map(::vanillaCarver)
            WEATHERED -> listOf(ResourceKey.create(Registries.CONFIGURED_CARVER, EROSION))
        }
        return mapOf(GenerationStep.Carving.AIR to HolderSet.direct(keys.map(configured::getOrThrow)))
    }

    /**
     * Where water stands in the rock. Null means "a flat table at the ambient sea", which is what a
     * world with nothing hollow underneath wants anyway.
     *
     * [seed] varies the table per Age so two Ages are not wet in the same places.
     */
    fun waterTable(ambient: AmbientMedium, seed: Long): WaterTable? = when (this) {
        SOLID, WEATHERED, FLOODED_CAVES -> null
        // Dry enough to walk, with wet pockets — the reason to go underground at all. A world with no
        // medium has no waterline to hang a table on, and wants none: nothing was going to flood.
        CAVES, POROUS -> ambient.surfaceY?.let { WaterTable.matching(ambient, seaLevel = ambient.level, seed = seed) }
    }

    companion object {
        private val UNDERGROUND_CARVERS = listOf("cave", "cave_extra_underground", "canyon")
        private val EROSION: ResourceLocation = "erosion".location()

        private fun vanillaCarver(name: String): ResourceKey<ConfiguredWorldCarver<*>> =
            ResourceKey.create(Registries.CONFIGURED_CARVER, ResourceLocation.withDefaultNamespace(name))
    }
}
