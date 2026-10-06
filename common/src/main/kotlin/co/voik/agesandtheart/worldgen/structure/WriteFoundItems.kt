package co.voik.agesandtheart.worldgen.structure

import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.WordRarity
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.PageItem
import co.voik.agesandtheart.page.PageWordFunction
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
 * Writes what a template saved in its blocks afresh for each placement, so no two structures hold the same
 * thing: a blank page gets a word drawn as a found page's is, and a descriptive book is set back to blank,
 * which writes itself as a basic book the first time it is opened (`DescriptiveBookItem.writeIfBlank`).
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
        val vocabulary = Vocabulary.of(placing.level.server)
        val registries = placing.registryAccess()
        val random = settings.getRandom(processedBlockInfo.pos)

        val written = TemplateItems.rewritten(saved, registries) { stack ->
            val isBlankPage = stack.`is`(AgeContent.PAGE) && !stack.has(AgeComponents.PAGE_WORD)
            when {
                isBlankPage -> {
                    val word = PageWordFunction.drawFoundWord(vocabulary, registries, random, rarity)
                    if (word == null) stack else stack.copy().also { PageItem.write(it, word, vocabulary) }
                }
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
