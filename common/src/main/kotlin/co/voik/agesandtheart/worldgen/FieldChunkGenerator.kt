package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.AmbientMedium
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
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
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.Beardifier
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.NoiseChunk
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.WorldGenerationContext
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
    private val surfaceRule: SurfaceRules.RuleSource = Palette.PLAIN_STONE,
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

    /**
     * Repaints the shape the field laid down, according to [surfaceRule] — vanilla's own
     * [net.minecraft.world.level.levelgen.SurfaceSystem] doing the column walk for us (see [Palette]).
     *
     * The one piece it wants that a field Age has no use for is a [NoiseChunk]. We hand it one built on
     * `NoiseGeneratorSettings.dummy()`, whose router is inert and whose aquifers are off — the same
     * settings the game already used to build the [RandomState] it passes us, since this generator is
     * not a `NoiseBasedChunkGenerator`. So nothing here consults noise; it is an access toll, paid once
     * per chunk.
     */
    override fun buildSurface(level: WorldGenRegion, structureManager: StructureManager, randomState: RandomState, chunk: ChunkAccess) {
        val noiseChunk = chunk.getOrCreateNoiseChunk { access ->
            NoiseChunk.forChunk(
                access,
                randomState,
                // The real beardifier rather than the inert marker: it is public, and it is what will
                // let structures flatten the ground around themselves once they are switched on.
                Beardifier.forStructuresInChunk(structureManager, access.pos),
                NoiseGeneratorSettings.dummy(),
                ambientFluid,
                Blender.empty(),
            )
        }
        randomState.surfaceSystem().buildSurface(
            randomState,
            level.biomeManager,
            level.registryAccess().registryOrThrow(Registries.BIOME),
            /* useLegacyRandomSource = */ false,
            WorldGenerationContext(this, level),
            chunk,
            noiseChunk,
            surfaceRule,
        )
    }

    // The ambient sea, in the shape the surface machinery asks for; aquifers are off, so this is only
    // ever consulted as a plain answer to "what fluid is at this height", which the medium already knows.
    private val ambientFluid = Aquifer.FluidPicker { _, _, _ -> Aquifer.FluidStatus(ambient.level, ambient.block) }

    // --- Next wiring: biome carvers in applyCarvers. Stubbed for now. ---

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
                // Optional so field Ages serialised before palettes existed still load.
                SurfaceRules.RuleSource.CODEC.optionalFieldOf("surface_rule", Palette.PLAIN_STONE)
                    .forGetter { it.surfaceRule },
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
