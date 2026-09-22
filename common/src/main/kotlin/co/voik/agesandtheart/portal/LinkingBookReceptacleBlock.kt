package co.voik.agesandtheart.portal

import net.minecraft.util.Prediction
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.BlockHitResult

/**
 * Set on a phasmium frame, it is what lights the portal: a bound linking book put into it opens the frame
 * onto the book's destination, and taking the book out puts the portal out again.
 *
 * "On the frame" is any face of any frame block. The book stays in the receptacle, as it stays on a lectern —
 * a portal is a door that is kept, where a book in the hand is spent.
 */
class LinkingBookReceptacleBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(HAS_BOOK, false))
    }


    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        LinkingBookReceptacleBlockEntity(pos, state)

    /** Anything else in the hand falls through to the empty-handed click, which takes a held book out. */
    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        val acceptsThisBook = !state.getValue(HAS_BOOK) && LinkingPortals.isBoundLinkingBook(stack)
        if (!acceptsThisBook) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level !is ServerLevel) return InteractionResult.SUCCESS
        val receptacle = level.getBlockEntity(pos) as? LinkingBookReceptacleBlockEntity ?: return InteractionResult.PASS
        val book = stack.consumeAndReturn(1, player)
        receptacle.hold(book)
        level.setBlock(pos, state.setValue(HAS_BOOK, true), UPDATE_ALL)
        level.playSound(null, pos, SoundEvents.BOOK_PUT, SoundSource.BLOCKS, VOLUME, PITCH)
        when (LinkingPortals.open(level, pos, book)) {
            LinkingPortals.Opening.OPENED ->
                level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, VOLUME, PITCH)
            LinkingPortals.Opening.NO_FRAME -> tell(player, "no_frame")
            LinkingPortals.Opening.SAME_WORLD -> tell(player, "same_world")
        }
        return InteractionResult.SUCCESS
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (!state.getValue(HAS_BOOK)) return InteractionResult.PASS
        if (level !is ServerLevel) return InteractionResult.SUCCESS
        val receptacle = level.getBlockEntity(pos) as? LinkingBookReceptacleBlockEntity ?: return InteractionResult.PASS
        val book = receptacle.release()
        level.setBlock(pos, state.setValue(HAS_BOOK, false), UPDATE_ALL)
        LinkingPortals.closeAround(level, pos)
        player.inventory.placeItemBackInInventory(book, Prediction.SERVER_ONLY)
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, VOLUME, PITCH)
        return InteractionResult.SUCCESS
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(HAS_BOOK)
    }

    companion object {

        val HAS_BOOK: BooleanProperty = BlockStateProperties.HAS_BOOK

        private const val VOLUME = 1.0f
        private const val PITCH = 1.0f

        private fun tell(player: Player, reason: String) {
            (player as? ServerPlayer)?.sendSystemMessage(Component.translatable("portal.agesandtheart.$reason"), true)
        }
    }
}
