package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.AmbientMedium
import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.WaterTable
import net.minecraft.core.HolderSet
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver

/**
 * The smooth-field preset: rolling hills over a sea, the counterpart to the toolkit's hard-edged CSG
 * worlds. Its whole job is to exercise [NoiseHeightmap] — one primitive, no combinators — so what the
 * noise actually looks like is readable without anything else in the way.
 *
 * The sea earns its place here: it reads the relief back to you as coastline, so how far the surface
 * swings is visible at a glance rather than having to be walked.
 */
object NoiseField {

    fun hills(): TerrainField = NoiseHeightmap(
        seed = TERRAIN_SEED,
        // Detail at roughly 128, 64 and 32 blocks — broad hills with a little shape on their flanks.
        firstOctave = -7,
        amplitudes = listOf(1.0, 0.5, 0.25),
        scaleX = 1.0,
        scaleZ = 1.0,
        baseY = 68,
        relief = 30.0,
        flatY = -64,
    )

    /**
     * [carvers] cut caves and canyons out of the hills — supplied by the caller because configured
     * carvers are registry objects, and an Age names them as part of its recipe rather than inheriting
     * them from a biome (see [FieldChunkGenerator.applyCarvers]).
     */
    fun hillsGenerator(
        biomeSource: BiomeSource,
        carvers: Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>> = emptyMap(),
    ): FieldChunkGenerator {
        // Built once and used twice — by the generator to shape the rock, and (when the biome source
        // wants depth measured against it) to say how deeply buried a point is. One value, so the two
        // cannot drift apart.
        val terrain = hills()
        return FieldChunkGenerator(
            biomeSource,
            terrain,
            AmbientMedium.sea(Blocks.WATER.defaultBlockState(), level = SEA_LEVEL),
            // Vanilla's own palette now that the Age has vanilla's biomes to hang it on: our hand-built
            // VERDANT dressed every biome alike, which stops being the right answer here.
            Palette.VANILLA_OVERWORLD,
            carvers,
            // Deep caves run mostly dry, with wet pockets where the rock is flooded, while the sea
            // still wins just beneath the seabed so nothing hangs over a hollow.
            WaterTable.matching(
                AmbientMedium.sea(Blocks.WATER.defaultBlockState(), level = SEA_LEVEL),
                seaLevel = SEA_LEVEL,
                seed = TABLE_SEED,
            ),
        )
    }

    private const val TERRAIN_SEED = 0x1DEA_5EEDL
    private const val SEA_LEVEL = 63
    private const val TABLE_SEED = 0xEBBED_1L
}
