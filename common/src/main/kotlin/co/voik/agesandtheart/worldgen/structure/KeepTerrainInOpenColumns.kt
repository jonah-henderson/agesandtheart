package co.voik.agesandtheart.worldgen.structure

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo

/**
 * Leaves the ground alone wherever a template's column holds nothing but air: the margin a building's
 * bounding box takes beyond its walls, which would otherwise be carved out of the terrain as a box. Air in
 * any column that holds part of the building is still placed, so the inside stays clear.
 *
 * ```json
 * { "processor_type": "agesandtheart:keep_terrain_in_open_columns" }
 * ```
 *
 * It judges a column by the whole template, so it asks for the whole piece on every chunk's placement.
 */
object KeepTerrainInOpenColumns : StructureProcessor {

    override fun evaluatesEntirePieceState(): Boolean = true

    override fun finalizeProcessing(
        level: ServerLevelAccessor,
        position: BlockPos,
        referencePos: BlockPos,
        originalBlockInfos: List<StructureBlockInfo>,
        processedBlockInfos: List<StructureBlockInfo>,
        settings: StructurePlaceSettings,
    ): List<StructureBlockInfo> {
        fun columnOf(info: StructureBlockInfo) = info.pos.x to info.pos.z
        val holdsSomething = originalBlockInfos.filterNot { it.state.isAir }.mapTo(HashSet(), ::columnOf)
        val leftToTheGround = originalBlockInfos
            .filter { it.state.isAir && columnOf(it) !in holdsSomething }
            .mapTo(HashSet()) { StructureTemplate.calculateRelativePosition(settings, it.pos).offset(position) }
        return processedBlockInfos.filterNot { it.state.isAir && it.pos in leftToTheGround }
    }

    val CODEC: MapCodec<KeepTerrainInOpenColumns> = MapCodec.unit(this)

    override fun codec(): MapCodec<out StructureProcessor> = CODEC
}
