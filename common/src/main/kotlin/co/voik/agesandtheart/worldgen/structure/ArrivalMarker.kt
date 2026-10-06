package co.voik.agesandtheart.worldgen.structure

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.book.LinkTarget
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import net.minecraft.world.phys.Vec3

/**
 * Where a structure's linking books arrive: a structure block in its template named [marker], standing on
 * the floor, facing [facing] as the template was saved. The marker is never placed (jigsaw placement drops
 * structure blocks), so it is read back out of the template wherever the structure lands.
 *
 * ```json
 * { "marker": "minecraft:linking_book_target", "facing": "south", "name": "lost library" }
 * ```
 */
data class ArrivalMarker(val marker: String, val facing: Direction, val name: String) {

    /** The marker's position within [template], or null where the template has none. */
    fun inTemplate(template: StructureTemplate): BlockPos? =
        template.filterBlocks(BlockPos.ZERO, StructurePlaceSettings(), Blocks.STRUCTURE_BLOCK)
            .firstOrNull { it.nbt()?.getString(STRUCTURE_NAME)?.orElse(null) == marker }
            ?.pos()

    /** The arrival of a template placed at [origin] with [settings], whose marker sits at [marker] within it. */
    fun target(dimension: ResourceKey<Level>, marker: BlockPos, origin: BlockPos, settings: StructurePlaceSettings): LinkTarget {
        val at = StructureTemplate.calculateRelativePosition(settings, marker).offset(origin)
        val facingPlaced = settings.rotation.rotate(settings.mirror.mirror(facing))
        return LinkTarget(dimension, Vec3(at.x + HALF_BLOCK, at.y.toDouble(), at.z + HALF_BLOCK), facingPlaced.toYRot(), WordNames.titleCase(name))
    }

    /**
     * The arrival of whichever structure piece stands at [at] and carries this marker, for something decided
     * after generation, like a chest's loot. Null where no piece there has one.
     */
    fun around(level: ServerLevel, at: BlockPos): LinkTarget? {
        val structures = level.structureManager()
        for (structure in structures.getAllStructuresAt(at).keys) {
            val start = structures.getStructureWithPieceAt(at.x, at.y, at.z, structure)
            if (!start.isValid) continue
            for (piece in start.pieces) {
                val placed = piece as? PoolElementStructurePiece ?: continue
                val element = placed.element as? SinglePoolElement ?: continue
                val template = level.server.structureTemplateManager.getOrCreate(element.templateLocation)
                val marker = inTemplate(template) ?: continue
                val settings = StructurePlaceSettings().setRotation(placed.rotation)
                return target(level.dimension(), marker, placed.position, settings)
            }
        }
        return null
    }

    companion object {
        private const val STRUCTURE_NAME = "name"
        private const val HALF_BLOCK = 0.5

        val CODEC: Codec<ArrivalMarker> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("marker").forGetter(ArrivalMarker::marker),
                Direction.CODEC.fieldOf("facing").forGetter(ArrivalMarker::facing),
                Codec.STRING.fieldOf("name").forGetter(ArrivalMarker::name),
            ).apply(instance, ::ArrivalMarker)
        }
    }
}
