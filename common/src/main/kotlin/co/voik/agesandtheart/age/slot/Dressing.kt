package co.voik.agesandtheart.age.slot

import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets
import net.minecraft.world.level.levelgen.structure.StructureSet

/**
 * What an Age looks and grows like: its biomes, and the blocks those biomes paint the shape in.
 *
 * The two travel together because a palette is written *against* a biome table — vanilla's surface
 * rules ask "which biome is this?" at nearly every turn, so [Palette.VANILLA_OVERWORLD] means nothing
 * over a world that is one biome everywhere, and [Palette.BARE_ROCK] would flatten a world that has
 * real ones. Letting a writer pair them freely would mostly produce grey.
 *
 * Structures ride here too, for now. They are closer to the *contents* slot the design describes (§3.1)
 * than to dressing, but that slot has nothing behind it yet, and villages plainly belong to the same
 * decision as "this Age has vanilla's biomes in it". When contents arrives they should move.
 */
enum class Dressing(override val key: String) : SlotPreset {
    /** Grey stone and andesite: the shape itself, with nothing growing to distract from it. */
    BARE_ROCK("bare_rock"),

    /** Our own green dressing, applied evenly — a world that looks alive without vanilla's biome table. */
    VERDANT("verdant"),

    /** Spire's green plasma sea, and bare rock above it. */
    PLASMA("plasma"),

    /**
     * Vanilla's own biomes, climate and palette: the sane default for an Age whose author has expressed
     * no preference, and the only dressing under which the world grows anything.
     */
    OVERWORLD("overworld"),
    ;

    override val slot = Slot.DRESSING

    override val parameters: List<Parameter>
        get() = if (this == OVERWORLD) listOf(SETTLEMENT) else emptyList()

    override fun getSerializedName(): String = key

    /**
     * The biomes this dressing lays over [landform].
     *
     * [OVERWORLD] grounds its climate in the Age's own rock rather than pinning it to the surface, which
     * is what reaches vanilla's underground biomes — dripstone and lush caves through the middle of the
     * rock, the deep dark at the very bottom. Everything else is one biome everywhere, because for those
     * Ages the shape *is* the subject.
     */
    fun biomes(server: MinecraftServer, landform: TerrainField, seed: Long): BiomeSource = when (this) {
        OVERWORLD -> AgeBiomeSource.vanillaOverworld(server, seed).groundedIn(landform)
        PLASMA -> fixedBiome(server, ResourceKey.create(Registries.BIOME, AgeGeneration.PLASMA_BIOME))
        // Vanilla's barren `the_void`: a bright, neutral sky, and nothing in the way of the shape.
        BARE_ROCK, VERDANT -> fixedBiome(server, Biomes.THE_VOID)
    }

    /** The blocks the shape gets repainted in, once it is laid down. */
    fun palette(): SurfaceRules.RuleSource = when (this) {
        OVERWORLD -> Palette.VANILLA_OVERWORLD
        VERDANT -> Palette.VERDANT
        BARE_ROCK, PLASMA -> Palette.BARE_ROCK
    }

    /**
     * What vanilla is allowed to build here, named by the Age rather than inherited.
     *
     * Naming a set is not the same as placing it: the generator's state builder drops any set whose
     * structures want a biome this Age's biome source cannot produce, so an Age with no jungle gets no
     * jungle temples without anyone having to say so. Leaving the nether and end sets out is honesty
     * rather than filtering — no overworld biome could ever admit them.
     */
    fun structures(server: MinecraftServer, options: Options): HolderSet<StructureSet> {
        if (this != OVERWORLD || options.of(SETTLEMENT) == "none") return HolderSet.direct(emptyList())
        val sets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
        return HolderSet.direct(OVERWORLD_STRUCTURE_SETS.map(sets::getOrThrow))
    }

    private fun fixedBiome(server: MinecraftServer, biome: ResourceKey<Biome>) =
        FixedBiomeSource(server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(biome))

    companion object {
        /** Whether anyone ever built here. Vanilla's whole overworld set, or an empty world. */
        val SETTLEMENT = Parameter("settlement", "none", "vanilla")

        private val OVERWORLD_STRUCTURE_SETS = listOf(
            BuiltinStructureSets.VILLAGES,
            BuiltinStructureSets.DESERT_PYRAMIDS,
            BuiltinStructureSets.IGLOOS,
            BuiltinStructureSets.JUNGLE_TEMPLES,
            BuiltinStructureSets.SWAMP_HUTS,
            BuiltinStructureSets.PILLAGER_OUTPOSTS,
            BuiltinStructureSets.OCEAN_MONUMENTS,
            BuiltinStructureSets.WOODLAND_MANSIONS,
            BuiltinStructureSets.BURIED_TREASURES,
            BuiltinStructureSets.MINESHAFTS,
            BuiltinStructureSets.RUINED_PORTALS,
            BuiltinStructureSets.SHIPWRECKS,
            BuiltinStructureSets.OCEAN_RUINS,
            BuiltinStructureSets.ANCIENT_CITIES,
            BuiltinStructureSets.STRONGHOLDS,
            BuiltinStructureSets.TRAIL_RUINS,
            BuiltinStructureSets.TRIAL_CHAMBERS,
        )
    }
}
