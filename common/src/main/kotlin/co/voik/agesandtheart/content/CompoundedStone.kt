package co.voik.agesandtheart.content

import co.voik.agesandtheart.advancement.AgeTriggers
import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.util.Unit as MinecraftUnit
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ToolMaterial
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor

/**
 * Nara — stone a fusion-compounder has pressed to many times its density (design §7.1.2), and the tools
 * made of it. "Nara" is the language file's; the id says what it is.
 *
 * **The tools never break and take no enchantment.** Unbreakable is a component. Unenchantable is the
 * tools staying out of vanilla's tool tags: an enchantment names the items it supports by those tags, and
 * the enchanting table and the anvil both ask the enchantment, so an item in none of them is offered
 * nothing and accepts no book.
 */
object CompoundedStone {

    /** What may break nara at all. Every other tool makes no progress on it, as on bedrock. */
    val BREAKS_IT: TagKey<Item> = TagKey.create(Registries.ITEM, "breaks_compounded_stone".location())

    /**
     * Pale, because it multiplies polished deepslate down to canon's dark grey-green. The item models carry
     * the same tint in their own JSON, darker for the tools, whose stand-in is iron.
     */
    const val TINT = 0xA8BFA0

    private val registeredBlocks = mutableListOf<Pair<Identifier, Block>>()
    private val registeredItems = mutableListOf<Pair<Identifier, Item>>()

    /** Bedrock's blast resistance, so no bomb touches it; the hardness is a nara pickaxe's three seconds. */
    private const val HARDNESS = 25.0f
    private const val BLAST_RESISTANCE = 3_600_000.0f

    val BLOCK: Block = block(
        "compounded_stone",
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.TERRACOTTA_GREEN)
            .strength(HARDNESS, BLAST_RESISTANCE)
            .sound(SoundType.POLISHED_DEEPSLATE)
            .requiresCorrectToolForDrops(),
    )

    val BLOCK_ITEM: Item = item("compounded_stone", Item.Properties().useBlockDescriptionPrefix()) { BlockItem(BLOCK, it) }

    /**
     * Gold's speed and iron's damage, mining anything netherite can.
     *
     * Durability, enchantability and repair items are placeholders that the components below make moot:
     * [ToolMaterial] insists on all three.
     */
    private val MATERIAL = ToolMaterial(
        BlockTags.INCORRECT_FOR_NETHERITE_TOOL,
        ToolMaterial.NETHERITE.durability(),
        ToolMaterial.GOLD.speed(),
        ToolMaterial.IRON.attackDamageBonus(),
        ToolMaterial.GOLD.enchantmentValue(),
        BREAKS_IT,
    )

    // Iron's baselines, tool for tool, as `Items` lays them.
    val PICKAXE: Item = tool("compounded_stone_pickaxe", ::CompoundedStonePickaxe) { it.pickaxe(MATERIAL, 1.0f, -2.8f) }
    val AXE: Item = tool("compounded_stone_axe") { it.axe(MATERIAL, 6.0f, -3.1f) }
    val SHOVEL: Item = tool("compounded_stone_shovel") { it.shovel(MATERIAL, 1.5f, -3.0f) }
    val HOE: Item = tool("compounded_stone_hoe") { it.hoe(MATERIAL, -2.0f, -1.0f) }
    val SWORD: Item = tool("compounded_stone_sword") { it.sword(MATERIAL, 3.0f, -2.4f) }

    val blocks: List<Pair<Identifier, Block>> get() = registeredBlocks
    val items: List<Pair<Identifier, Item>> get() = registeredItems

    private fun tool(
        path: String,
        make: (Item.Properties) -> Item = ::Item,
        shape: (Item.Properties) -> Item.Properties,
    ): Item = item(path, shape(Item.Properties()).component(DataComponents.UNBREAKABLE, MinecraftUnit.INSTANCE).fireResistant(), make)

    private fun block(path: String, properties: BlockBehaviour.Properties): Block {
        val id = path.location()
        return CompoundedStoneBlock(properties.setId(ResourceKey.create(Registries.BLOCK, id)))
            .also { registeredBlocks += id to it }
    }

    private fun <I : Item> item(path: String, properties: Item.Properties, make: (Item.Properties) -> I): I {
        val id = path.location()
        return make(properties.setId(ResourceKey.create(Registries.ITEM, id))).also { registeredItems += id to it }
    }
}

/** Nara, which nothing but a nara tool makes any progress on. */
class CompoundedStoneBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    override fun getDestroyProgress(state: BlockState, player: Player, level: BlockGetter, pos: BlockPos): Float {
        val holdsWhatBreaksIt = player.mainHandItem.`is`(CompoundedStone.BREAKS_IT)
        return if (holdsWhatBreaksIt) super.getDestroyProgress(state, player, level, pos) else 0.0f
    }
}

/** The nara pickaxe, which tells the advancements when it has broken something. */
class CompoundedStonePickaxe(properties: Item.Properties) : Item(properties) {
    override fun mineBlock(stack: ItemStack, level: Level, state: BlockState, pos: BlockPos, owner: LivingEntity): Boolean {
        if (owner is ServerPlayer) AgeTriggers.MINED_WITH_NARA.trigger(owner)
        return super.mineBlock(stack, level, state, pos, owner)
    }
}
