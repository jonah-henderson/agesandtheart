package co.voik.agesandtheart.worldgen.structure

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo

/**
 * Fills a template's containers from a different loot table, **leaving the block exactly as it was drawn**.
 *
 * The table a chest opens from is written into the template itself, so borrowing someone else's building
 * borrows its loot with it. Vanilla's `rule` processor can write a table (its `append_loot` modifier), but
 * only while replacing the whole block state, so a chest came out facing whichever way the rule said rather
 * than the way the builder set it. This changes the one tag and touches nothing else.
 *
 * A container naming a table that is not in [swaps] keeps the one it had.
 */
class LootTableSwap(private val swaps: Map<Identifier, Identifier>) : StructureProcessor {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun processBlock(
        level: LevelReader,
        targetPosition: BlockPos,
        referencePos: BlockPos,
        templateRelativePos: BlockPos,
        processedBlockInfo: StructureBlockInfo,
        settings: StructurePlaceSettings,
    ): StructureBlockInfo {
        val saved = processedBlockInfo.nbt ?: return processedBlockInfo
        val named = saved.getString(LOOT_TABLE).orElse(null)?.let(Identifier::tryParse) ?: return processedBlockInfo
        val instead = swaps[named] ?: return processedBlockInfo
        val swapped = saved.copy().apply { putString(LOOT_TABLE, instead.toString()) }
        return StructureBlockInfo(processedBlockInfo.pos, processedBlockInfo.state, swapped)
    }

    override fun codec(): MapCodec<out StructureProcessor> = CODEC

    companion object {
        /** Where a container's block entity keeps the table it has yet to open from. */
        private const val LOOT_TABLE = "LootTable"

        val CODEC: MapCodec<LootTableSwap> = Codec.unboundedMap(Identifier.CODEC, Identifier.CODEC)
            .fieldOf("swaps")
            .xmap(::LootTableSwap) { it.swaps }
    }
}
