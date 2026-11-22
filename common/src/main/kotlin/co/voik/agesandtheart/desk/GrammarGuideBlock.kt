package co.voik.agesandtheart.desk

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * The D'ni grammar guide (design §7.4): an implement the desk counts, and where a reader studies a new word of a
 * tier, paid for in pages. Using it hands the reader a study they finished, then opens it.
 */
class GrammarGuideBlock(properties: Properties) : BaseEntityBlock(properties) {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = GrammarGuideBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, AgeContent.GRAMMAR_GUIDE_ENTITY) { tickedIn, _, _, guide -> guide.tick(tickedIn as ServerLevel) }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val reader = player as? ServerPlayer ?: return InteractionResult.FAIL
        (level.getBlockEntity(pos) as? GrammarGuideBlockEntity)?.collect(reader)
        reader.openMenu(
            SimpleMenuProvider(
                { containerId, inventory, _ ->
                    GrammarGuideMenu.serving(containerId, inventory, ContainerLevelAccess.create(level, pos), reader)
                },
                Component.translatable(TITLE),
            ),
        )
        return InteractionResult.CONSUME
    }

    private companion object {
        const val TITLE = "block.agesandtheart.grammar_guide"
    }
}
