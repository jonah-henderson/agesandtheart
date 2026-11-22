package co.voik.agesandtheart.optics

import co.voik.agesandtheart.location
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor

/** Astrite's optics (design §7.4): the lens, and the emitter, receiver and reflector a beam runs between. */
object Optics {
    val LENS_ID: Identifier = "astrite_lens".location()
    val EMITTER_ID: Identifier = "astrite_emitter".location()
    val RECEIVER_ID: Identifier = "beam_receiver".location()
    val REFLECTOR_ID: Identifier = "reflector".location()

    private const val STRENGTH = 3.0f

    private fun properties(id: Identifier): BlockBehaviour.Properties = BlockBehaviour.Properties.of()
        .setId(ResourceKey.create(Registries.BLOCK, id))
        .mapColor(MapColor.COLOR_PURPLE)
        .strength(STRENGTH)
        .sound(SoundType.AMETHYST)
        .requiresCorrectToolForDrops()

    private fun itemFor(block: Block, id: Identifier): Item =
        BlockItem(block, Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix())

    val LENS: Item = Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, LENS_ID)))

    val EMITTER: AstriteEmitterBlock = AstriteEmitterBlock(properties(EMITTER_ID))
    val RECEIVER: BeamReceiverBlock = BeamReceiverBlock(properties(RECEIVER_ID))
    val REFLECTOR: ReflectorBlock = ReflectorBlock(properties(REFLECTOR_ID).noOcclusion())

    val EMITTER_ITEM: Item = itemFor(EMITTER, EMITTER_ID)
    val RECEIVER_ITEM: Item = itemFor(RECEIVER, RECEIVER_ID)
    val REFLECTOR_ITEM: Item = itemFor(REFLECTOR, REFLECTOR_ID)

    val EMITTER_ENTITY: BlockEntityType<AstriteEmitterBlockEntity> =
        BlockEntityType({ pos, state -> AstriteEmitterBlockEntity(pos, state) }, setOf(EMITTER))
    val RECEIVER_ENTITY: BlockEntityType<BeamReceiverBlockEntity> =
        BlockEntityType({ pos, state -> BeamReceiverBlockEntity(pos, state) }, setOf(RECEIVER))

    val BLOCKS: List<Pair<Identifier, Block>> = listOf(EMITTER_ID to EMITTER, RECEIVER_ID to RECEIVER, REFLECTOR_ID to REFLECTOR)
    val ITEMS: List<Pair<Identifier, Item>> =
        listOf(LENS_ID to LENS, EMITTER_ID to EMITTER_ITEM, RECEIVER_ID to RECEIVER_ITEM, REFLECTOR_ID to REFLECTOR_ITEM)
    val BLOCK_ENTITIES: List<Pair<Identifier, BlockEntityType<*>>> = listOf(EMITTER_ID to EMITTER_ENTITY, RECEIVER_ID to RECEIVER_ENTITY)
}
