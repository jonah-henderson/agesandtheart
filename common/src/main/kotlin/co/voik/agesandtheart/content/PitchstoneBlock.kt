package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Deretheni, which an axe in [SHAVES_IT] shaves into plates as a cake is eaten: a plate a use, off the west
 * face, until the stone is gone. A shaved stone drops the plates it has left rather than itself.
 */
class PitchstoneBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(SHAVED, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(SHAVED)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES[state.getValue(SHAVED)]

    override fun useItemOn(
        itemStack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (!itemStack.`is`(SHAVES_IT)) return super.useItemOn(itemStack, state, level, pos, player, hand, hitResult)
        level.playSound(player, pos, SoundEvents.AXE_SCRAPE.value(), SoundSource.BLOCKS, 1.0f, 1.0f)
        if (!level.isClientSide) {
            popResourceFromFace(level, pos, hitResult.direction, ItemStack(AgeContent.PITCHSTONE_PLATE))
            shaveOnce(state, level, pos, player)
            itemStack.hurtAndBreak(1, player, hand.asEquipmentSlot())
        }
        return InteractionResult.SUCCESS
    }

    private fun shaveOnce(state: BlockState, level: Level, pos: BlockPos, player: Player) {
        val shaved = state.getValue(SHAVED)
        if (shaved == LAST_SHAVING) {
            level.destroyBlock(pos, false, player)
            return
        }
        val thinner = state.setValue(SHAVED, shaved + 1)
        level.setBlock(pos, thinner, UPDATE_ALL)
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(player, thinner))
    }

    companion object {
        /** Diamond and better, as a tag so a pack may add its own. */
        val SHAVES_IT: TagKey<Item> = TagKey.create(Registries.ITEM, "shaves_pitchstone".location())

        const val PLATES_IN_A_STONE = 4
        private const val LAST_SHAVING = PLATES_IN_A_STONE - 1
        private const val PIXELS_A_PLATE = 16.0 / PLATES_IN_A_STONE

        /** How many plates have been taken; the last one takes the stone with it, so it never reaches four. */
        val SHAVED: IntegerProperty = IntegerProperty.create("shaved", 0, LAST_SHAVING)

        private val SHAPES: List<VoxelShape> =
            (0..LAST_SHAVING).map { shaved -> box(shaved * PIXELS_A_PLATE, 0.0, 0.0, 16.0, 16.0, 16.0) }
    }
}
