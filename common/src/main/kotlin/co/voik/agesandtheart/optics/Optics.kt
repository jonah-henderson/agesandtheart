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

/**
 * Astrite's optics (design §7.4): the lens and mirror, the collector gathering sunlight, the emitter focusing it into a
 * laser, the reflector and receiver a beam runs between, and the conduit carrying light between them.
 */
object Optics {
    val LENS_ID: Identifier = "astrite_lens".location()
    val MIRROR_ID: Identifier = "astrite_mirror".location()
    val EMITTER_ID: Identifier = "astrite_emitter".location()
    val RECEIVER_ID: Identifier = "beam_receiver".location()
    val REFLECTOR_ID: Identifier = "reflector".location()
    val COLLECTOR_ID: Identifier = "sunlight_collector".location()
    val CONDUIT_ID: Identifier = "optical_conduit".location()

    private const val STRENGTH = 3.0f
    private const val CONDUIT_STRENGTH = 0.5f
    private const val MIRROR_STRENGTH = 0.3f

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
    val COLLECTOR: SunlightCollectorBlock = SunlightCollectorBlock(properties(COLLECTOR_ID).noOcclusion())
    val MIRROR_BLOCK: AstriteMirrorBlock = AstriteMirrorBlock(properties(MIRROR_ID).strength(MIRROR_STRENGTH).sound(SoundType.GLASS).noOcclusion())
    val CONDUIT: OpticalConduitBlock = OpticalConduitBlock(
        properties(CONDUIT_ID).strength(CONDUIT_STRENGTH).sound(SoundType.GLASS).noOcclusion().isRedstoneConductor { _, _, _ -> false },
    )

    val EMITTER_ITEM: Item = itemFor(EMITTER, EMITTER_ID)
    val RECEIVER_ITEM: Item = itemFor(RECEIVER, RECEIVER_ID)
    val REFLECTOR_ITEM: Item = itemFor(REFLECTOR, REFLECTOR_ID)

    /** Kept under its own item name, since it was an item before it could be placed. */
    val MIRROR: Item = BlockItem(MIRROR_BLOCK, Item.Properties().setId(ResourceKey.create(Registries.ITEM, MIRROR_ID)))
    val COLLECTOR_ITEM: Item = itemFor(COLLECTOR, COLLECTOR_ID)
    val CONDUIT_ITEM: Item = itemFor(CONDUIT, CONDUIT_ID)

    val EMITTER_ENTITY: BlockEntityType<AstriteEmitterBlockEntity> =
        BlockEntityType({ pos, state -> AstriteEmitterBlockEntity(pos, state) }, setOf(EMITTER))
    val RECEIVER_ENTITY: BlockEntityType<BeamReceiverBlockEntity> =
        BlockEntityType({ pos, state -> BeamReceiverBlockEntity(pos, state) }, setOf(RECEIVER))
    val COLLECTOR_ENTITY: BlockEntityType<SunlightCollectorBlockEntity> =
        BlockEntityType({ pos, state -> SunlightCollectorBlockEntity(pos, state) }, setOf(COLLECTOR))
    val MIRROR_ENTITY: BlockEntityType<AstriteMirrorBlockEntity> =
        BlockEntityType({ pos, state -> AstriteMirrorBlockEntity(pos, state) }, setOf(MIRROR_BLOCK))

    val BLOCKS: List<Pair<Identifier, Block>> = listOf(
        EMITTER_ID to EMITTER,
        RECEIVER_ID to RECEIVER,
        REFLECTOR_ID to REFLECTOR,
        COLLECTOR_ID to COLLECTOR,
        CONDUIT_ID to CONDUIT,
        MIRROR_ID to MIRROR_BLOCK,
    )
    val ITEMS: List<Pair<Identifier, Item>> = listOf(
        LENS_ID to LENS,
        MIRROR_ID to MIRROR,
        EMITTER_ID to EMITTER_ITEM,
        RECEIVER_ID to RECEIVER_ITEM,
        REFLECTOR_ID to REFLECTOR_ITEM,
        COLLECTOR_ID to COLLECTOR_ITEM,
        CONDUIT_ID to CONDUIT_ITEM,
    )
    val BLOCK_ENTITIES: List<Pair<Identifier, BlockEntityType<*>>> = listOf(
        EMITTER_ID to EMITTER_ENTITY,
        RECEIVER_ID to RECEIVER_ENTITY,
        COLLECTOR_ID to COLLECTOR_ENTITY,
        MIRROR_ID to MIRROR_ENTITY,
    )
}
