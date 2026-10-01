package co.voik.agesandtheart.worldgen.dni

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.generation.AgeChunkGenerator
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasBinding
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure
import java.util.Optional

/**
 * The D'ni city: a jigsaw whose start is handed in by [DniCitySite] rather than drawn from a height
 * provider, which `JigsawStructure` (final) insists on. What it is built of lives in
 * `worldgen/structure/dni_city.json`.
 *
 * Starts only on the chunk holding the site, because a start's pieces are referenced only as far as
 * `createReferences` scans around that chunk.
 */
class DniCityStructure(
    settings: StructureSettings,
    private val startPool: Holder<StructureTemplatePool>,
    private val startJigsawName: Optional<Identifier>,
    private val size: Int,
    private val maxDistanceFromCenter: JigsawStructure.MaxDistance,
    private val poolAliases: List<PoolAliasBinding>,
) : Structure(settings) {

    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> {
        val generator = context.chunkGenerator() as? AgeChunkGenerator ?: return Optional.empty()
        val site = DniCitySite.of(generator) ?: return Optional.empty()
        if (ChunkPos.containing(site.start) != context.chunkPos()) return Optional.empty()
        val city = JigsawPlacement.addPieces(
            context,
            startPool,
            startJigsawName,
            size,
            site.start,
            false,
            Optional.empty(),
            maxDistanceFromCenter,
            PoolAliasLookup.create(poolAliases, site.start, context.seed()),
            JigsawStructure.DEFAULT_DIMENSION_PADDING,
            JigsawStructure.DEFAULT_LIQUID_SETTINGS,
        )
        return city.map { withTheDevices(it, context) }
    }

    /** The jigsaw's pieces, and after them the machines the city holds one of, set by its frame. */
    private fun withTheDevices(city: GenerationStub, context: GenerationContext): GenerationStub =
        GenerationStub(city.position()) { builder ->
            val laid = city.getPiecesBuilder().build().pieces()
            laid.forEach(builder::addPiece)
            val centre = laid.firstOrNull() as? PoolElementStructurePiece ?: return@GenerationStub
            DniCity.devicesBefore(context.structureTemplateManager(), centre).forEach(builder::addPiece)
        }

    /** The recipe and the site have decided; the biome under the start is not asked. */
    override fun findValidGenerationPoint(context: GenerationContext): Optional<GenerationStub> =
        findGenerationPoint(context)

    override fun type(): StructureType<*> = AgeContent.DNI_CITY_STRUCTURE

    companion object {
        /** `JigsawStructure`'s fields, less the ones that say where the start goes. */
        val CODEC: MapCodec<DniCityStructure> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                settingsCodec(instance),
                StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter { it.startPool },
                Identifier.CODEC.optionalFieldOf("start_jigsaw_name").forGetter { it.startJigsawName },
                Codec.intRange(JigsawStructure.MIN_DEPTH, JigsawStructure.MAX_DEPTH).fieldOf("size").forGetter { it.size },
                JigsawStructure.MaxDistance.CODEC.fieldOf("max_distance_from_center")
                    .forGetter { it.maxDistanceFromCenter },
                PoolAliasBinding.CODEC.listOf().optionalFieldOf("pool_aliases", emptyList())
                    .forGetter { it.poolAliases },
            ).apply(instance) { settings, pool, jigsaw, size, reach, aliases ->
                DniCityStructure(settings, pool, jigsaw, size, reach, aliases)
            }
        }
    }
}
