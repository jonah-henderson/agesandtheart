package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.AmbientMedium
import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.blending.Blender
import java.util.concurrent.CompletableFuture

/**
 * The Tier-A generator: it owns no shape of its own — it samples whatever [field] it is handed and
 * fills the empty space below with [ambient]. Archetypes are field *presets*, not subclasses.
 *
 * Unlike [SpireChunkGenerator] (a bespoke Tier-B preset kept alongside), this participates in the
 * vanilla decoration pipeline: it reports honest surface heights ([getBaseHeight]/[getBaseColumn])
 * and primes the world-surface heightmaps during fill, so inherited `applyBiomeDecoration` places
 * features and structures on the terrain rather than in the sea. Surface rules and carvers are the
 * next wiring (see `notes/terrain-architecture.md`); for now fill lays a single solid block.
 */
class FieldChunkGenerator(
    private val biomeSource: BiomeSource,
    private val field: TerrainField,
    private val ambient: AmbientMedium,
) : ChunkGenerator(biomeSource) {

    override fun codec(): MapCodec<out ChunkGenerator> = CODEC

    override fun fillFromNoise(
        blender: Blender,
        randomState: RandomState,
        structureManager: StructureManager,
        chunk: ChunkAccess,
    ): CompletableFuture<ChunkAccess> {
        val chunkMinX = chunk.pos.minBlockX
        val chunkMinZ = chunk.pos.minBlockZ
        val oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG)
        val worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG)
        val cursor = BlockPos.MutableBlockPos()

        for (localX in 0..<16) {
            for (localZ in 0..<16) {
                val worldX = chunkMinX + localX
                val worldZ = chunkMinZ + localZ
                val spans = field.columnSpans(worldX, worldZ)

                for (y in MIN_Y..<TOP_Y) {
                    val state = when {
                        spans.contains(y) -> SOLID
                        ambient.fillsAt(y) -> ambient.block
                        else -> null
                    } ?: continue
                    chunk.setBlockState(cursor.set(worldX, y, worldZ), state, false)
                    oceanFloor.update(localX, y, localZ, state)
                    worldSurface.update(localX, y, localZ, state)
                }
            }
        }
        return CompletableFuture.completedFuture(chunk)
    }

    // --- Surface height contract: honest answers so structures/features land on the terrain. ---

    override fun getBaseHeight(x: Int, z: Int, type: Heightmap.Types, level: LevelHeightAccessor, randomState: RandomState): Int =
        field.columnSpans(x, z).highestSolidY?.plus(1) ?: ambient.level

    override fun getBaseColumn(x: Int, z: Int, level: LevelHeightAccessor, randomState: RandomState): NoiseColumn {
        val spans = field.columnSpans(x, z)
        val column = Array(GEN_HEIGHT) { index ->
            val y = MIN_Y + index
            when {
                spans.contains(y) -> SOLID
                ambient.fillsAt(y) -> ambient.block
                else -> AIR
            }
        }
        return NoiseColumn(MIN_Y, column)
    }

    // --- Next wiring: surface rules in buildSurface, biome carvers in applyCarvers. Stubbed for now. ---

    override fun buildSurface(level: WorldGenRegion, structureManager: StructureManager, randomState: RandomState, chunk: ChunkAccess) = Unit

    override fun applyCarvers(
        level: WorldGenRegion,
        seed: Long,
        randomState: RandomState,
        biomeManager: BiomeManager,
        structureManager: StructureManager,
        chunk: ChunkAccess,
        step: GenerationStep.Carving,
    ) = Unit

    override fun spawnOriginalMobs(level: WorldGenRegion) = Unit

    override fun addDebugScreenInfo(info: MutableList<String>, randomState: RandomState, pos: BlockPos) = Unit

    override fun getGenDepth(): Int = GEN_HEIGHT

    override fun getSeaLevel(): Int = ambient.level

    override fun getMinY(): Int = MIN_Y

    companion object {
        val CODEC: MapCodec<FieldChunkGenerator> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter { it.biomeSource },
                TerrainField.CODEC.fieldOf("field").forGetter { it.field },
                AmbientMedium.CODEC.forGetter { it.ambient },
            ).apply(instance, ::FieldChunkGenerator)
        }

        // Vertical layout matches the agesandtheart:age dimension type (min_y -64, height 384).
        private const val MIN_Y = -64
        private const val GEN_HEIGHT = 384
        private const val TOP_Y = MIN_Y + GEN_HEIGHT

        // Placeholder palette — a real per-biome palette + surface rules replace this later.
        private val SOLID: BlockState = Blocks.STONE.defaultBlockState()
        private val AIR: BlockState = Blocks.AIR.defaultBlockState()
    }
}
