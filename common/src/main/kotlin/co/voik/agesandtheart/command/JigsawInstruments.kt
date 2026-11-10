package co.voik.agesandtheart.command

import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.worldgen.ruins.JigsawWiring
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.IdentifierArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.JigsawBlockEntity
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * Wiring the D'ni city's jigsaw blocks without the jigsaw block's screen: the one looked at is set from a short name
 * by [JigsawWiring]'s convention, with the city's pools and connectors offered by tab, and the whole city's wiring
 * checked as the game now has it — pieces as last saved in the world, pools as the mod was started with.
 */
internal object JigsawInstruments {

    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(
            Commands.literal("jigsaw")
                .executes { context -> runShow(context.source, Report.prose(context.source)) }
                .then(Commands.literal("in").then(connectorArgument().executes(::runEntrance)))
                .then(
                    Commands.literal("out").then(
                        connectorArgument().then(
                            poolArgument()
                                .executes { context -> runExit(context, placementPriority = 0) }
                                .then(
                                    Commands.argument(PRIORITY_ARGUMENT, IntegerArgumentType.integer(0))
                                        .executes { context ->
                                            runExit(context, IntegerArgumentType.getInteger(context, PRIORITY_ARGUMENT))
                                        },
                                ),
                        ),
                    ),
                )
                .then(Commands.literal("check").executes { context -> runCheck(context.source, Report.prose(context.source)) }),
        )
    }

    /** `/age jigsaw` — what the jigsaw looked at is wired to. */
    private fun runShow(source: CommandSourceStack, report: Report): Int {
        val (jigsaw, at) = lookedAtJigsaw(source, report) ?: return FAILURE
        describe(jigsaw, at, report)
        report.finish()
        return SUCCESS
    }

    /** `/age jigsaw in <connector>` — the jigsaw looked at made the entrance `<connector>_in`, which draws nothing. */
    private fun runEntrance(context: CommandContext<CommandSourceStack>): Int {
        val report = Report.prose(context.source)
        val (jigsaw, at) = lookedAtJigsaw(context.source, report) ?: return FAILURE
        val base = StringArgumentType.getString(context, CONNECTOR_ARGUMENT)
        val nothing = Identifier.parse(JigsawWiring.NOTHING)
        wire(jigsaw, at, context.source, connector(base, JigsawWiring.ENTRANCE_SUFFIX), nothing, nothing, placementPriority = 0)
        describe(jigsaw, at, report)
        report.finish()
        return SUCCESS
    }

    /**
     * `/age jigsaw out <connector> <pool> [placement_priority]` — the jigsaw looked at made the exit `<connector>_out`,
     * drawing from `<pool>` a piece with the entrance `<connector>_in`. A higher priority is placed sooner, so the
     * city's main course claims its room before its offshoots do.
     */
    private fun runExit(context: CommandContext<CommandSourceStack>, placementPriority: Int): Int {
        val report = Report.prose(context.source)
        val (jigsaw, at) = lookedAtJigsaw(context.source, report) ?: return FAILURE
        val base = StringArgumentType.getString(context, CONNECTOR_ARGUMENT)
        val pool = IdentifierArgument.getId(context, POOL_ARGUMENT)
        val name = connector(base, JigsawWiring.EXIT_SUFFIX)
        val target = connector(base, JigsawWiring.ENTRANCE_SUFFIX)
        wire(jigsaw, at, context.source, name, target, pool, placementPriority)
        describe(jigsaw, at, report)
        report.finish()
        return SUCCESS
    }

    /** `/age jigsaw check` — every mistake in the city's wiring, as the game has it now. */
    private fun runCheck(source: CommandSourceStack, report: Report): Int {
        val pieces = piecesIn(source.server)
        val pools = poolsIn(source.server)
        val problems = JigsawWiring.problemsIn(pieces, pools)
        val ours = pieces.count { it.id.startsWith(JigsawWiring.SCOPE) }
        report.fact("checked", ours) { "Checked $ours pieces of the city's, and their pools" }
        for (problem in problems) report.entry("problems", mapOf("problem" to problem)) { problem }
        if (problems.isEmpty()) report.fact("wired", true) { "Everything is wired up." }
        report.finish()
        return SUCCESS
    }

    private fun connector(base: String, suffix: String): Identifier = Identifier.parse(JigsawWiring.connectorId(base, suffix))

    private fun wire(
        jigsaw: JigsawBlockEntity,
        at: BlockPos,
        source: CommandSourceStack,
        name: Identifier,
        target: Identifier,
        pool: Identifier,
        placementPriority: Int,
    ) {
        jigsaw.setName(name)
        jigsaw.setTarget(target)
        jigsaw.setPool(ResourceKey.create(Registries.TEMPLATE_POOL, pool))
        jigsaw.setPlacementPriority(placementPriority)
        jigsaw.setChanged()
        val state = source.level.getBlockState(at)
        source.level.sendBlockUpdated(at, state, state, Block.UPDATE_ALL)
    }

    private fun describe(jigsaw: JigsawBlockEntity, at: BlockPos, report: Report) {
        val where = "${at.x} ${at.y} ${at.z}"
        val pool = jigsaw.pool.identifier()
        report.entry(
            "jigsaw",
            mapOf("at" to where, "name" to jigsaw.name, "target" to jigsaw.target, "pool" to pool),
        ) { "Jigsaw at $where: ${jigsaw.name}, looking for ${jigsaw.target} in $pool (priority ${jigsaw.placementPriority})" }
    }

    /** The jigsaw block the player running the command is looking at, or null, having said why not. */
    private fun lookedAtJigsaw(source: CommandSourceStack, report: Report): Pair<JigsawBlockEntity, BlockPos>? {
        val player = source.player ?: return null.also { report.fail("Run this as a player looking at a jigsaw block") }
        val hit = player.pick(REACH_BLOCKS, 0.0f, false)
        val at = (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos
        val jigsaw = at?.let { source.level.getBlockEntity(it) } as? JigsawBlockEntity
        if (at == null || jigsaw == null) return null.also { report.fail("Look at a jigsaw block") }
        return jigsaw to at
    }

    private fun connectorArgument(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument(CONNECTOR_ARGUMENT, StringArgumentType.word())
            .suggests { context, builder -> SharedSuggestionProvider.suggest(connectorBases(context.source.server), builder) }

    private fun poolArgument(): RequiredArgumentBuilder<CommandSourceStack, Identifier> =
        Commands.argument(POOL_ARGUMENT, IdentifierArgument.id())
            .suggests { context, builder ->
                val ours = poolsIn(context.source.server).map { it.id }.filter { it.startsWith(JigsawWiring.SCOPE) }
                SharedSuggestionProvider.suggestResource(ours.map(Identifier::parse), builder)
            }

    /** The bases of every connector already in the city's pieces: `walkway` for `agesandtheart:ruins/walkway_out`. */
    private fun connectorBases(server: MinecraftServer): Set<String> =
        piecesIn(server).flatMap { it.connectors }
            .map { it.name }
            .filter { it.startsWith(JigsawWiring.SCOPE) }
            .map { it.removePrefix(JigsawWiring.SCOPE).removeSuffix(JigsawWiring.ENTRANCE_SUFFIX).removeSuffix(JigsawWiring.EXIT_SUFFIX) }
            .toSet()

    private fun piecesIn(server: MinecraftServer): List<JigsawWiring.Piece> {
        val manager = server.structureTemplateManager
        return manager.listTemplates().toList()
            .filter { it.toString().startsWith(JigsawWiring.SCOPE) }
            .map { id ->
                val jigsaws = manager.get(id).map { it.getJigsaws(BlockPos.ZERO, Rotation.NONE) }.orElse(emptyList())
                val connectors = jigsaws.map {
                    JigsawWiring.Connector(it.name().toString(), it.target().toString(), it.pool().identifier().toString())
                }
                JigsawWiring.Piece(id.toString(), connectors)
            }
    }

    private fun poolsIn(server: MinecraftServer): List<JigsawWiring.Pool> =
        server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL).listElements().toList().map { holder ->
            val pool = holder.value()
            val pieces = pool.templates.mapNotNull { (it.first as? SinglePoolElement)?.templateLocation?.toString() }
            val fallback = pool.fallback.unwrapKey().map { it.identifier().toString() }.orElse(JigsawWiring.NOTHING)
            JigsawWiring.Pool(holder.key().identifier().toString(), pieces, fallback)
        }

    private const val CONNECTOR_ARGUMENT = "connector"
    private const val POOL_ARGUMENT = "pool"
    private const val PRIORITY_ARGUMENT = "placement_priority"

    /** Further than a player can reach, so a jigsaw across a small room can be set without walking to it. */
    private const val REACH_BLOCKS = 16.0
}
