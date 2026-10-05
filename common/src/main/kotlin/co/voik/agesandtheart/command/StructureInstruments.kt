package co.voik.agesandtheart.command

import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.content.AgeComponents
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.Container
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.LecternBlockEntity

internal object StructureInstruments {

    /** What a placed structure holds, read back without opening anything. */
    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(contentsSubcommand())
    }

    /**
     * `/age contents <radius>` — every item held in a block within [radius] of where it is run, and every
     * container still waiting on a loot table, as `<x y z> <block> <item>`. A linking book says where it
     * leads, a page its word, a descriptive book whether it is written. Reading a chest that has a table
     * would roll it, so those report only the table.
     */
    private fun contentsSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("contents") { reportFor ->
            Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(1, MAX_RADIUS))
                .executes { context -> runContents(context, reportFor(context)) }
        }

    private fun runContents(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val level = context.source.level
        val centre = BlockPos.containing(context.source.position)
        val radius = IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT)
        for (at in BlockPos.betweenClosed(centre.offset(-radius, -radius, -radius), centre.offset(radius, radius, radius))) {
            val blockEntity = level.getBlockEntity(at) ?: continue
            val block = BuiltInRegistries.BLOCK.getKey(blockEntity.blockState.block).path
            val where = "${at.x} ${at.y} ${at.z}"
            val table = (blockEntity as? RandomizableContainer)?.lootTable
            val held = when {
                table != null -> {
                    report.entry("contents", mapOf("at" to where, "block" to block, "loot_table" to table.identifier())) {
                        "$where $block loot table ${table.identifier()}"
                    }
                    emptyList()
                }
                blockEntity is LecternBlockEntity -> listOf(blockEntity.book)
                blockEntity is Container -> (0..<blockEntity.containerSize).map(blockEntity::getItem)
                else -> emptyList()
            }
            for (stack in held.filterNot(ItemStack::isEmpty)) {
                val item = BuiltInRegistries.ITEM.getKey(stack.item).path
                val detail = detailOf(stack)
                report.entry("contents", mapOf("at" to where, "block" to block, "item" to item, "detail" to detail)) {
                    "$where $block $item $detail".trim()
                }
            }
        }
        report.finish()
        return SUCCESS
    }

    private fun detailOf(stack: ItemStack): String {
        stack.get(AgeComponents.LINK_TARGET)?.let { target ->
            val at = target.position
            return "leads to ${target.dimension.identifier()} %.1f %.1f %.1f facing %.0f".format(at.x, at.y, at.z, target.yaw)
        }
        stack.get(AgeComponents.PAGE_WORD)?.let { return "word $it" }
        if (stack.has(AgeComponents.BOOK_WORDS)) return "written"
        return ""
    }

    private const val RADIUS_ARGUMENT = "radius"
    private const val MAX_RADIUS = 48
}
