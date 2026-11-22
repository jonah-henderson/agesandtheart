package co.voik.agesandtheart.desk

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * A grammar guide not yet studied (design §7.4): pages and survey reports used on it, or put in its slot, are studied
 * into it at once, and full it becomes the grammar guide. It is no implement until then.
 */
class EmptyGrammarGuideBlock(properties: Properties) : BaseEntityBlock(properties) {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = EmptyGrammarGuideBlockEntity(pos, state)

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.FAIL
        serverPlayer.openMenu(
            SimpleMenuProvider(
                { containerId, inventory, _ ->
                    GrammarStudyMenu.serving(containerId, inventory, ContainerLevelAccess.create(level, pos))
                },
                Component.translatable(TITLE),
            ),
        )
        return InteractionResult.CONSUME
    }

    /** One of what is used on it is studied each click, as a composter takes what it is handed. */
    override fun useItemOn(
        itemStack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (!GrammarStudyMenu.mayStudy(itemStack)) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level.isClientSide) return InteractionResult.SUCCESS
        val guide = level.getBlockEntity(pos) as? EmptyGrammarGuideBlockEntity ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        val wasStudied = guide.study(itemStack.copyWithCount(1)).isEmpty
        if (!wasStudied) return InteractionResult.TRY_WITH_EMPTY_HAND
        itemStack.consume(1, player)
        return InteractionResult.SUCCESS
    }

    /** In creative the loot table is skipped, so a guide part studied drops as an item carrying its progress. */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val guide = level.getBlockEntity(pos) as? EmptyGrammarGuideBlockEntity
        if (!level.isClientSide && player.preventsBlockDrops() && guide != null && guide.studied > 0) {
            val carried = ItemStack(this)
            carried.applyComponents(guide.collectComponents())
            val dropped = ItemEntity(level, pos.x + HALF, pos.y + HALF, pos.z + HALF, carried)
            dropped.setDefaultPickUpDelay()
            level.addFreshEntity(dropped)
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    private companion object {
        const val TITLE = "container.agesandtheart.empty_grammar_guide"
        const val HALF = 0.5
    }
}
