package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import co.voik.agesandtheart.mixin.ItemRemainderAccessor
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction

/**
 * Plasma (design §7.1.2): a sea that looks like a fluid and has no fluid state, so no bucket, pipe or pump
 * can take it. Drawn at full brightness; only the sea's skin casts light, since a block closed in on every
 * side lights nothing and would still cost the light engine a source. [PlasmaBlock] is the sea itself, which only generation lays; [UnstablePlasmaBlock] is plasma
 * anywhere else, which flares and burns out.
 */
object Plasma {

    /** What plasma does not consume, and nothing else is spared: not even what nothing else can break. */
    val PROOF: TagKey<Block> = TagKey.create(Registries.BLOCK, "plasma_proof".location())

    val DAMAGE: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, "plasma".location())

    private val registeredBlocks = mutableListOf<Pair<Identifier, Block>>()
    private val registeredItems = mutableListOf<Pair<Identifier, Item>>()

    // Never `noCollision()`: it also clears `canOcclude`, which stops a sea's inner faces being culled
    // (walked 2026-10-02: every face of a sea a hundred deep was drawn). The blocks' empty shapes do it.
    val SEA: Block = block("plasma") { properties ->
        PlasmaBlock(
            properties
                .mapColor(MapColor.COLOR_LIGHT_GREEN)
                .strength(-1.0f, BLAST_PROOF)
                .lightLevel { state -> if (state.getValue(PlasmaBlock.BURIED)) DARK else FULL_LIGHT }
                .emissiveRendering { true }
                .isValidSpawn { _, _, _, _ -> false }
                .pushReaction(PushReaction.IMMOVEABLE)
                .noLootTable()
                .sound(SoundType.EMPTY),
        )
    }

    val UNSTABLE: Block = block("unstable_plasma") { properties ->
        UnstablePlasmaBlock(
            properties
                .mapColor(MapColor.COLOR_LIGHT_GREEN)
                .strength(-1.0f, BLAST_PROOF)
                .lightLevel { FULL_LIGHT }
                .emissiveRendering { true }
                .isValidSpawn { _, _, _, _ -> false }
                .pushReaction(PushReaction.POPPED)
                .noLootTable()
                .sound(SoundType.EMPTY),
        )
    }

    /** An empty deretheni unit, which takes a block of the sea into itself. */
    val CONTAINMENT_UNIT: Item = item("plasma_containment_unit", Item.Properties().stacksTo(UNITS_A_STACK)) {
        PlasmaContainmentUnitItem(it)
    }

    /** Plasma in its unit: the compounder's repair, a furnace's fuel and a lamp — see [ContainedPlasmaBlock]. */
    val CONTAINED: Block = block("contained_plasma") { properties ->
        ContainedPlasmaBlock(
            properties
                .mapColor(MapColor.COLOR_LIGHT_GREEN)
                .strength(CONTAINED_STRENGTH)
                .sound(SoundType.LANTERN)
                .lightLevel { FULL_LIGHT }
                .noOcclusion()
                .pushReaction(PushReaction.POPPED),
        )
    }

    /**
     * Burns as ten lava buckets do (`context_int_provider/cooking/time_contained_plasma`), leaving the unit —
     * given it in [leaveTheUnitBehind], since the builder's remainder wants the unit registered already.
     */
    val CONTAINED_ITEM: Item = item(
        "contained_plasma",
        Item.Properties()
            .stacksTo(UNITS_A_STACK)
            .useBlockDescriptionPrefix()
            .cookingFuel(ResourceKey.create(Registries.CONTEXT_INT_PROVIDER, "cooking/time_contained_plasma".location())),
    ) { BlockItem(CONTAINED, it) }

    private const val UNITS_A_STACK = 16

    /** Broken in a moment by hand, as nothing else so dangerous is: a player is never meant to lose it by accident. */
    private const val CONTAINED_STRENGTH = 1.0f

    private const val BLAST_PROOF = 3_600_000.0f
    private const val FULL_LIGHT = 15
    private const val DARK = 0

    val blocks: List<Pair<Identifier, Block>> get() = registeredBlocks
    val items: List<Pair<Identifier, Item>> get() = registeredItems

    /** Whether plasma leaves [state] where it stands. */
    fun spares(state: BlockState): Boolean = state.isAir || state.`is`(PROOF)

    /** Whatever falls into plasma is gone: a creature is killed by it, and anything else simply ceases. */
    fun annihilate(entity: Entity, level: ServerLevel) {
        if (entity !is LivingEntity) return entity.discard()
        entity.hurtServer(level, damageIn(level), Float.MAX_VALUE)
    }

    fun damageIn(level: ServerLevel): DamageSource =
        DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DAMAGE))

    /** Called once every item of ours is registered. */
    fun leaveTheUnitBehind() {
        (CONTAINED_ITEM as ItemRemainderAccessor).`agesandtheart$setCraftingRemainder`(ItemStackTemplate(CONTAINMENT_UNIT))
    }

    private fun <I : Item> item(path: String, properties: Item.Properties, make: (Item.Properties) -> I): I {
        val id = path.location()
        return make(properties.setId(ResourceKey.create(Registries.ITEM, id))).also { registeredItems += id to it }
    }

    private fun block(path: String, make: (BlockBehaviour.Properties) -> Block): Block {
        val id = path.location()
        return make(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id)))
            .also { registeredBlocks += id to it }
    }
}
