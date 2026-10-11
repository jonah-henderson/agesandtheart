package co.voik.agesandtheart.worldgen.structure

import co.voik.agesandtheart.age.word.WordRarity
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo

/**
 * Leaves what a template saved in its blocks to be written afresh, so no two structures hold the same thing:
 * a blank page is marked to be drawn **for whoever first takes it** (`PageWordFunction.writeForFinder`), and a
 * descriptive book is set back to blank, which writes itself as a basic book the first time it is opened
 * (`DescriptiveBookItem.writeIfBlank`). Neither can be written here: a structure is placed with nobody there.
 *
 * A page's word is drawn from the [rarity] buckets named, or from all of them where none are.
 *
 * ```json
 * { "processor_type": "agesandtheart:write_found_items", "rarity": "critical" }
 * ```
 */
class WriteFoundItems(private val rarity: Set<String>?) : StructureProcessor {

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
        if (!TemplateItems.holdsItems(saved)) return processedBlockInfo
        val placing = level as? ServerLevelAccessor ?: return processedBlockInfo
        val registries = placing.registryAccess()

        val written = TemplateItems.rewritten(saved, registries) { stack ->
            val isBlankPage = stack.`is`(AgeContent.PAGE) && !stack.has(AgeComponents.PAGE_WORD)
            when {
                isBlankPage -> stack.copy().also { it.set(AgeComponents.PAGE_DRAWN_FOR, rarity.orEmpty()) }
                stack.`is`(AgeContent.DESCRIPTIVE_BOOK) -> ItemStack(AgeContent.DESCRIPTIVE_BOOK, stack.count)
                else -> stack
            }
        } ?: return processedBlockInfo
        return StructureBlockInfo(processedBlockInfo.pos, processedBlockInfo.state, written)
    }

    override fun codec(): MapCodec<out StructureProcessor> = CODEC

    companion object {
        val CODEC: MapCodec<WriteFoundItems> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                WordRarity.BUCKET_NAMES_CODEC.optionalFieldOf("rarity").forGetter { java.util.Optional.ofNullable(it.rarity) },
            ).apply(instance) { rarity -> WriteFoundItems(rarity.orElse(null)) }
        }
    }
}
