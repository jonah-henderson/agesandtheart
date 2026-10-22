package co.voik.agesandtheart.command

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.age.ReportFor
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.page.PageLoot
import co.voik.agesandtheart.page.PageLootTarget
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams

internal object LootInstruments {

    /**
     * `/age loot [<times>]` — every container pages are put in, opened [times] times over through vanilla's own
     * table: how often one held a page or a notebook against the chance it was given, and how many it held. Proof that the
     * injection reaches each table on this loader, which a list of targets cannot be.
     */
    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(
            Commands.literal(LOOT_LITERAL)
                .opening { context -> Report.prose(context.source) }
                .then(Commands.literal(Report.STRUCTURED_LITERAL).opening { context -> Report.structured(context.source) }),
        )
        age.then(
            Commands.literal(CONTAINERS_LITERAL).then(
                Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(0, MOST_RADIUS))
                    .executes { context -> runContainers(context, IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT)) },
            ),
        )
    }

    /**
     * `/age containers <radius>` — every unopened container in the loaded chunks within `radius` chunks of where it is
     * run, by the table it fills from, and what of ours they would hold, each opened as its own seed would: what a
     * structure placed with `/place structure` actually gives a player who loots all of it.
     */
    private fun runContainers(context: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = context.source
        val level = source.level
        val centre = ChunkPos.containing(BlockPos.containing(source.position))
        val containers = (-radius..radius).flatMap { x ->
            (-radius..radius).mapNotNull { z -> level.chunkSource.getChunkNow(centre.x() + x, centre.z() + z) }
        }.flatMap { it.blockEntities.values }.filterIsInstance<RandomizableContainer>().filter { it.lootTable != null }
        val params = LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, source.position).create(LootContextParamSets.CHEST)
        val byTable = containers.groupBy { it.lootTable?.identifier().toString() }
        fun heldBy(container: RandomizableContainer) =
            container.lootTable?.let(source.server.reloadableRegistries()::getLootTable)?.getRandomItems(params, container.lootTableSeed).orEmpty()
        fun idOf(stack: ItemStack) = BuiltInRegistries.ITEM.getKey(stack.item)
        val ours = containers.flatMap(::heldBy).filter { idOf(it).namespace == Constants.MOD_ID }
            .groupBy { idOf(it).path }.mapValues { (_, stacks) -> stacks.sumOf { it.count } }
        source.sendSuccess({ Component.literal("${containers.size} unopened containers within $radius chunks:") }, false)
        for ((table, held) in byTable.toSortedMap()) source.sendSuccess({ Component.literal("  $table: ${held.size}") }, false)
        source.sendSuccess({ Component.literal("Of ours, they would hold:") }, false)
        for ((item, count) in ours.toSortedMap()) source.sendSuccess({ Component.literal("  $item: $count") }, false)
        return SUCCESS
    }

    private const val LOOT_LITERAL = "loot"
    private const val CONTAINERS_LITERAL = "containers"
    private const val RADIUS_ARGUMENT = "radius"
    private const val MOST_RADIUS = 32
    private const val TIMES_ARGUMENT = "times"
    private const val DEFAULT_TIMES = 400
    private const val MOST_TIMES = 10_000

    private fun <T : ArgumentBuilder<CommandSourceStack, T>> T.opening(reportFor: ReportFor): T =
        executes { context -> runLoot(context, DEFAULT_TIMES, reportFor(context)) }
            .then(
                Commands.argument(TIMES_ARGUMENT, IntegerArgumentType.integer(1, MOST_TIMES))
                    .executes { context ->
                        runLoot(context, IntegerArgumentType.getInteger(context, TIMES_ARGUMENT), reportFor(context))
                    },
            )

    private fun runLoot(context: CommandContext<CommandSourceStack>, times: Int, report: Report): Int {
        val source = context.source
        val params = LootParams.Builder(source.level)
            .withParameter(LootContextParams.ORIGIN, source.position)
            .create(LootContextParamSets.CHEST)
        val targets = PageLoot.TARGETS.filter { it.injected in PageLoot.PAGE_TABLES }
        report.say { "Every page container opened $times times, with nobody opening it:" }
        for (target in targets) {
            val table = source.server.reloadableRegistries().getLootTable(target.table)
            val opened = openedTimes(table, params, times)
            val path = target.table.identifier().path
            report.entry(
                "containers",
                mapOf(
                    "table" to target.table.identifier().toString(),
                    "chance" to target.chance,
                    "found" to opened.found,
                    "pages" to opened.pages,
                    "notebooks" to opened.notebooks,
                    "times" to times,
                ),
            ) { sayingOf(path, target, opened, times) }
        }
        report.finish()
        return SUCCESS
    }

    /** How many openings held writing of either kind, and how much of each there was. */
    private data class Opened(val found: Int, val pages: Int, val notebooks: Int)

    private fun openedTimes(table: LootTable, params: LootParams, times: Int): Opened {
        var found = 0
        var pages = 0
        var notebooks = 0
        repeat(times) {
            val items = table.getRandomItems(params)
            val heldPages = items.filter { it.`is`(AgeContent.PAGE) }.sumOf { it.count }
            val heldNotebooks = items.filter { it.`is`(AgeContent.NOTEBOOK) }.sumOf { it.count }
            if (heldPages + heldNotebooks > 0) found++
            pages += heldPages
            notebooks += heldNotebooks
        }
        return Opened(found, pages, notebooks)
    }

    private fun sayingOf(path: String, target: PageLootTarget, opened: Opened, times: Int): String {
        val share = opened.found.toDouble() / times
        val perFind = if (opened.found == 0) 0.0 else opened.pages.toDouble() / opened.found
        val reached = if (opened.found == 0) "  — NEVER" else ""
        return "  %s: %.0f%% held writing (given %.0f%%), %.1f pages a time, %d notebook(s)$reached"
            .format(path, share * PER_CENT, target.chance * PER_CENT, perFind, opened.notebooks)
    }

}
