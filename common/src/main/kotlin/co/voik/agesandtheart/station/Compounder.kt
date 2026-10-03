package co.voik.agesandtheart.station

import co.voik.agesandtheart.content.Plasma
import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.util.RandomSource
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.SoundType
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.redstone.Orientation
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.phys.BlockHitResult

/**
 * The D'ni fusion-compounder (design §7.1.2): one in a city, not to be broken or moved, since a player who
 * could break the only one in their city would be soft-locked.
 *
 * **What stands beside it shows on it**, as a block state per [CompounderNeed], so a writer reads the
 * arrangement off the machine before opening it: sparks for power, flame for heat, snow for cold.
 *
 * **A crafter's redstone** (Jonah): a rising signal compounds once and puts the result out of its front,
 * into a container there or onto the floor. Hoppers fill the inputs and take nothing out, since the result
 * is worked out rather than stored.
 *
 * **Found broken, and nothing runs until it is [REPAIRED]** with a whole unit of contained plasma, which is
 * what puts nara after masterwork ink.
 */
class CompounderBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(
            NEEDS.values.fold(stateDefinition.any()) { state, property -> state.setValue(property, false) }
                .setValue(FACING, Direction.NORTH)
                .setValue(TRIGGERED, false)
                .setValue(REPAIRED, false),
        )
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = CompounderBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        NEEDS.values.forEach { builder.add(it) }
        builder.add(FACING, TRIGGERED, REPAIRED)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        showingWhatStandsBeside(defaultBlockState(), context.level, context.clickedPos)
            .setValue(FACING, context.nearestLookingDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    /** A rising signal compounds a moment later, as a crafter's does; a falling one readies it for the next. */
    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        orientation: Orientation?,
        movedByPiston: Boolean,
    ) {
        val isPowered = level.hasNeighborSignal(pos)
        val wasPowered = state.getValue(TRIGGERED)
        if (isPowered && !wasPowered) {
            level.scheduleTick(pos, this, A_CRAFTERS_DELAY)
            level.setBlock(pos, state.setValue(TRIGGERED, true), Block.UPDATE_CLIENTS)
        } else if (!isPowered && wasPowered) {
            level.setBlock(pos, state.setValue(TRIGGERED, false), Block.UPDATE_CLIENTS)
        }
    }

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (!state.getValue(REPAIRED)) return
        (level.getBlockEntity(pos) as? CompounderBlockEntity)?.compoundOutOfTheFront(level, state.getValue(FACING))
    }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        val repairsIt = stack.`is`(Plasma.CONTAINED_ITEM) && !state.getValue(REPAIRED)
        if (!repairsIt) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level.isClientSide) return InteractionResult.SUCCESS
        stack.consume(1, player)
        level.setBlock(pos, state.setValue(REPAIRED, true), UPDATE_ALL)
        level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0f, 1.0f)
        (level as? ServerLevel)?.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, SPARKS, 0.4, 0.4, 0.4, 0.1)
        player.sendSystemMessage(Component.translatable(REPAIRED_MESSAGE))
        return InteractionResult.CONSUME
    }

    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        directionToNeighbour: Direction,
        neighbourPos: BlockPos,
        neighbourState: BlockState,
        random: RandomSource,
    ): BlockState = showingWhatStandsBeside(state, level, pos)

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        if (!state.getValue(REPAIRED)) {
            player.sendOverlayMessage(Component.translatable(BROKEN_MESSAGE))
            return InteractionResult.CONSUME
        }
        val entity = level.getBlockEntity(pos) as? CompounderBlockEntity ?: return InteractionResult.FAIL
        player.openMenu(entity)
        return InteractionResult.CONSUME
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        for ((need, property) in NEEDS) {
            if (!state.getValue(property)) continue
            level.addParticle(
                particleFor(need),
                pos.x + random.nextDouble(),
                pos.y + ABOVE_THE_MACHINE,
                pos.z + random.nextDouble(),
                0.0,
                0.0,
                0.0,
            )
        }
    }

    private fun particleFor(need: CompounderNeed): ParticleOptions = when (need) {
        CompounderNeed.POWER -> ParticleTypes.ELECTRIC_SPARK
        CompounderNeed.HEAT -> ParticleTypes.SMALL_FLAME
        CompounderNeed.COLD -> ParticleTypes.SNOWFLAKE
    }

    companion object {
        /** One property per need, named for it: `power`, `heat`, `cold`. */
        val NEEDS: Map<CompounderNeed, BooleanProperty> =
            CompounderNeed.entries.associateWith { BooleanProperty.create(it.serializedName) }

        private const val ABOVE_THE_MACHINE = 1.05

        /** Which way its front faces: where a result goes. */
        val FACING: EnumProperty<Direction> = BlockStateProperties.FACING

        /** Whether it is powered, so only a rising signal compounds. */
        val TRIGGERED: BooleanProperty = BlockStateProperties.TRIGGERED

        /** Whether its core has been given contained plasma; until then it does nothing at all. */
        val REPAIRED: BooleanProperty = BooleanProperty.create("repaired")

        const val BROKEN_MESSAGE = "block.agesandtheart.fusion_compounder.broken"
        const val REPAIRED_MESSAGE = "block.agesandtheart.fusion_compounder.repaired"

        private const val SPARKS = 30

        /** The crafter's four ticks between a signal and the craft. */
        private const val A_CRAFTERS_DELAY = 4

        fun showingWhatStandsBeside(state: BlockState, level: BlockGetter, pos: BlockPos): BlockState =
            NEEDS.entries.fold(state) { shown, (need, property) ->
                val isMet = Direction.entries.any { face -> need.isMetBy(level.getBlockState(pos.relative(face))) }
                shown.setValue(property, isMet)
            }
    }
}

/** The compounder's registrations, for [co.voik.agesandtheart.content.AgeContent]'s lists. */
object Compounder {
    val ID: Identifier = "fusion_compounder".location()

    /** Bedrock's numbers: nothing breaks it, and nothing blows it up. */
    private const val UNBREAKABLE = -1.0f
    private const val UNBLASTABLE = 3_600_000.0f

    val BLOCK: CompounderBlock = CompounderBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ID))
            .mapColor(MapColor.TERRACOTTA_GREEN)
            .strength(UNBREAKABLE, UNBLASTABLE)
            .sound(SoundType.POLISHED_DEEPSLATE)
            .pushReaction(PushReaction.IMMOVEABLE)
            .noLootTable(),
    )

    /** For an operator's hand; a player only ever finds one standing. */
    val ITEM: Item = BlockItem(
        BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix(),
    )

    val ENTITY: BlockEntityType<CompounderBlockEntity> =
        BlockEntityType({ pos, state -> CompounderBlockEntity(pos, state) }, setOf(BLOCK))

    val MENU: MenuType<CompounderMenu> = MenuType(
        { containerId, inventory -> CompounderMenu(containerId, inventory) },
        FeatureFlags.VANILLA_SET,
    )
}
