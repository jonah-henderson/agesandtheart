package co.voik.agesandtheart.worldgen.structure

import co.voik.agesandtheart.book.LinkTarget
import co.voik.agesandtheart.book.LinkingBookItem
import com.mojang.serialization.Codec
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo

/**
 * Binds every unbound linking book a template saved in its blocks to where the structure was placed: the
 * [arrival] marker in [template], wherever and however the structure lands. A [homeChance] of them lead home
 * instead, drawn per book off the block's position.
 *
 * ```json
 * { "processor_type": "agesandtheart:bind_linking_books", "template": "agesandtheart:lost_library",
 *   "arrival": { "marker": "minecraft:linking_book_target", "facing": "south", "name": "lost library" },
 *   "home_chance": 0.05 }
 * ```
 *
 * [template] is named rather than read off the piece because a processor is never told which template it
 * is placing, and the marker itself has already been dropped by the time anything sees the blocks.
 */
class BindLinkingBooks(
    private val template: Identifier,
    private val arrival: ArrivalMarker,
    private val homeChance: Float,
) : StructureProcessor {

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
        val marker = arrival.inTemplate(placing.level.server.structureTemplateManager.getOrCreate(template))
            ?: return processedBlockInfo
        val target = arrival.target(placing.level.dimension(), marker, targetPosition, settings)
        val random = settings.getRandom(targetPosition)
        fun targetOfTheNextBook() = if (random.nextFloat() < homeChance) LinkTarget.home(placing.level.server) else target

        val bound = TemplateItems.rewritten(saved, level.registryAccess()) { stack ->
            val isUnboundLinkingBook = stack.`is`(AgeContent.LINKING_BOOK) && !stack.has(AgeComponents.LINK_TARGET)
            if (isUnboundLinkingBook) stack.copy().also { LinkingBookItem.bindTo(it, targetOfTheNextBook()) } else stack
        } ?: return processedBlockInfo
        return StructureBlockInfo(processedBlockInfo.pos, processedBlockInfo.state, bound)
    }

    override fun codec(): MapCodec<out StructureProcessor> = CODEC

    companion object {
        val CODEC: MapCodec<BindLinkingBooks> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Identifier.CODEC.fieldOf("template").forGetter(BindLinkingBooks::template),
                ArrivalMarker.CODEC.fieldOf("arrival").forGetter(BindLinkingBooks::arrival),
                Codec.floatRange(0.0f, 1.0f).optionalFieldOf("home_chance", NO_BOOK_LEADS_HOME).forGetter(BindLinkingBooks::homeChance),
            ).apply(instance, ::BindLinkingBooks)
        }

        private const val NO_BOOK_LEADS_HOME = 0.0f
    }
}
