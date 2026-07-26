package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap

/**
 * The `/age` debug command — the spike's trigger for exercising Age creation and travel. (The
 * player-facing Descriptive/Linking Books are the real interface; commands are the fast way to
 * drive the mechanic.)
 *
 * Brigadier is vanilla, so the whole command tree lives in `common`; each loader only has to hand
 * us its [CommandDispatcher] through its own command-registration event.
 *
 *   /age create <name>           — author a new Age (Spire preset) and persist it
 *   /age create field <name>     — a field-generator Age (the Spire island as a field tree)
 *   /age create pyramids <name>  — a field-generator Age: instanced pyramids on a plain
 *   /age create pyrvaried <name> — the same, with each pyramid turned and resized
 *   /age create hills <name>     — a noise-heightmap Age: rolling hills over a sea
 *   /age create shapes <name>    — a walkable sampler of the shape vocabulary and its combinators
 *   /age create pillars <name>   — colossal rectangular pillars on a jittered grid, over an ocean
 *   /age create vanilla <name>   — Minecraft's own overworld generation (Tier-B delegate; bench reference)
 *   /age create vanillabare <n>  — the same pipeline over a barren biome, so nothing decorates
 *   /age tp <name>               — travel to an Age
 *   /age delete <name>|all       — discard an Age (or every Age), chunks and all
 *   /age gen <name>              — force-generate the spawn chunk and report what the generator made
 *   /age bench <name> [radius]   — time generating the chunks around the origin (ms/chunk)
 *   /age list                    — list known Ages (with their generator kind)
 */
object AgeCommand {
    private const val OPERATOR_PERMISSION_LEVEL = 2
    private const val NAME_ARGUMENT = "name"
    private const val RADIUS_ARGUMENT = "radius"

    // Big enough to be dominated by generation rather than level-open overhead, small enough to run
    // on the server thread without tripping the watchdog.
    private const val DEFAULT_BENCHMARK_RADIUS = 8
    private const val MAX_BENCHMARK_RADIUS = 24
    private const val NANOS_PER_MILLISECOND = 1_000_000.0

    // Brigadier command result codes.
    private const val SUCCESS = 1
    private const val FAILURE = 0

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("age")
                .requires { source -> source.hasPermission(OPERATOR_PERMISSION_LEVEL) }
                .then(createSubcommand())
                .then(teleportSubcommand())
                .then(deleteSubcommand())
                .then(generateSubcommand())
                .then(benchmarkSubcommand())
                .then(listSubcommand()),
        )
    }

    private fun createSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("create")
            .then(
                Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                    .executes { context -> runCreate(context, AgeGeneration.GENERATOR_SPIRE) },
            )
            .then(
                Commands.literal("field").then(
                    Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .executes { context -> runCreate(context, AgeGeneration.GENERATOR_FIELD) },
                ),
            )
            .then(
                Commands.literal("pyramids").then(
                    Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .executes { context -> runCreate(context, AgeGeneration.GENERATOR_PYRAMIDS) },
                ),
            )
            .then(
                Commands.literal("pyrings").then(
                    Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .executes { context -> runCreate(context, AgeGeneration.GENERATOR_PYRINGS) },
                ),
            )
            .then(
                Commands.literal("pyrvaried").then(
                    Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .executes { context -> runCreate(context, AgeGeneration.GENERATOR_PYRVARIED) },
                ),
            )
            .then(
                Commands.literal("hills").then(
                    Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .executes { context -> runCreate(context, AgeGeneration.GENERATOR_HILLS) },
                ),
            )
            .then(
                Commands.literal("shapes").then(
                    Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .executes { context -> runCreate(context, AgeGeneration.GENERATOR_SHAPES) },
                ),
            )
            .then(
                Commands.literal("pillars").then(
                    Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .executes { context -> runCreate(context, AgeGeneration.GENERATOR_PILLARS) },
                ),
            )
            .then(
                Commands.literal("vanilla").then(
                    Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .executes { context -> runCreate(context, AgeGeneration.GENERATOR_VANILLA) },
                ),
            )
            .then(
                Commands.literal("vanillabare").then(
                    Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                        .executes { context -> runCreate(context, AgeGeneration.GENERATOR_VANILLA_BARE) },
                ),
            )

    private fun teleportSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("tp").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runTeleport),
        )

    private fun generateSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("gen").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runGenerate),
        )

    private fun benchmarkSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("bench").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runBenchmark(context, DEFAULT_BENCHMARK_RADIUS) }
                .then(
                    Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(1, MAX_BENCHMARK_RADIUS))
                        .executes { context ->
                            runBenchmark(context, IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT))
                        },
                ),
        )

    /** `all` is a literal rather than a name, so it cannot collide with an Age actually called "all". */
    private fun deleteSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("delete")
            .then(Commands.literal("all").executes(::runDeleteAll))
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runDelete))

    private fun listSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("list").executes(::runList)

    private fun ageId(name: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name.lowercase())

    private fun runCreate(context: CommandContext<CommandSourceStack>, generatorKey: String): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (!Ages.isSupported()) {
            source.sendFailure(Component.literal("Runtime Ages aren't supported on this loader yet (NeoForge backend pending)"))
            return FAILURE
        }
        if (id in AgeSavedData.get(source.server).ages) {
            source.sendFailure(Component.literal("Age '$name' already exists"))
            return FAILURE
        }
        val level = Ages.create(source.server, id, generatorKey)
        if (level == null) {
            source.sendFailure(Component.literal("Could not create Age '$name'"))
            return FAILURE
        }
        source.sendSuccess({ Component.literal("Created Age '$name' [$generatorKey] ($id). Travel with /age tp $name") }, true)
        return SUCCESS
    }

    private fun runDelete(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        if (!Ages.delete(source.server, ageId(name))) {
            source.sendFailure(Component.literal("Could not delete Age '$name' — no such Age, or the loader can't"))
            return FAILURE
        }
        source.sendSuccess({ Component.literal("Deleted Age '$name'") }, true)
        return SUCCESS
    }

    private fun runDeleteAll(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val deleted = Ages.deleteAll(source.server)
        if (deleted == 0) {
            source.sendFailure(Component.literal("No Ages to delete"))
            return FAILURE
        }
        source.sendSuccess({ Component.literal("Deleted $deleted Age(s)") }, true)
        return SUCCESS
    }

    private fun runTeleport(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (id !in AgeSavedData.get(source.server).ages) {
            source.sendFailure(Component.literal("No Age named '$name' — create it with /age create $name"))
            return FAILURE
        }
        val level = Ages.open(source.server, id)
        if (level == null) {
            source.sendFailure(Component.literal("Could not open Age '$name'"))
            return FAILURE
        }
        Ages.teleport(player, level)
        source.sendSuccess({ Component.literal("Travelled to Age '$name'") }, true)
        return SUCCESS
    }

    /**
     * Force-generates the Age's spawn column (the real chunk-gen path, same as travel) and reports
     * what the generator produced there — a headless sanity check for a generator without needing a
     * player to travel. Reads the surface height and a few probe blocks (surface, sea, sky).
     */
    private fun runGenerate(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (id !in AgeSavedData.get(source.server).ages) {
            source.sendFailure(Component.literal("No Age named '$name' — create it with /age create $name"))
            return FAILURE
        }
        val level = Ages.open(source.server, id)
        if (level == null) {
            source.sendFailure(Component.literal("Could not open Age '$name'"))
            return FAILURE
        }
        level.getChunk(0, 0) // force full generation of the spawn chunk
        val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0)
        val surfaceBlock = blockName(level, 0, surfaceY - 1, 0)
        // Scan the whole spawn chunk for its tallest column — reveals instanced geometry above the ground.
        var peakY = Int.MIN_VALUE
        var peakX = 0
        var peakZ = 0
        for (localX in 0..<16) {
            for (localZ in 0..<16) {
                val height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ)
                if (height > peakY) {
                    peakY = height
                    peakX = localX
                    peakZ = localZ
                }
            }
        }
        val peakBlock = blockName(level, peakX, peakY - 1, peakZ)
        source.sendSuccess({
            Component.literal(
                "Age '$name' spawn chunk: origin surface y=$surfaceY ($surfaceBlock); " +
                    "tallest column y=$peakY at ($peakX,$peakZ) ($peakBlock)",
            )
        }, false)
        return SUCCESS
    }

    /**
     * Times full generation of the `(2·radius+1)²` chunks around the Age's origin — the measurement
     * behind performance decisions like whether the instancer needs per-chunk memoisation. Centred on
     * the origin deliberately: that's where a density gradient packs the most instances into a column,
     * so it's the expensive case. Run it on a **freshly created** Age — chunks already generated come
     * from the cache and would time nothing.
     */
    private fun runBenchmark(context: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (id !in AgeSavedData.get(source.server).ages) {
            source.sendFailure(Component.literal("No Age named '$name' — create it with /age create $name"))
            return FAILURE
        }
        val level = Ages.open(source.server, id)
        if (level == null) {
            source.sendFailure(Component.literal("Could not open Age '$name'"))
            return FAILURE
        }
        val startedAt = System.nanoTime()
        for (chunkX in -radius..radius) {
            for (chunkZ in -radius..radius) {
                level.getChunk(chunkX, chunkZ)
            }
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
        val chunkCount = (2 * radius + 1) * (2 * radius + 1)
        source.sendSuccess({
            Component.literal(
                "Age '$name': generated $chunkCount chunks in ${"%.0f".format(elapsedMillis)} ms " +
                    "(${"%.2f".format(elapsedMillis / chunkCount)} ms/chunk)",
            )
        }, false)
        return SUCCESS
    }

    private fun blockName(level: ServerLevel, x: Int, y: Int, z: Int): String =
        BuiltInRegistries.BLOCK.getKey(level.getBlockState(BlockPos(x, y, z)).block).toString()

    private fun runList(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val saved = AgeSavedData.get(source.server)
        val ages = saved.ages
        if (ages.isEmpty()) {
            source.sendSuccess({ Component.literal("No Ages yet — write one with /age create <name>") }, false)
        } else {
            val listing = ages.joinToString(", ") { id -> "$id [${saved.generatorKey(id)}]" }
            source.sendSuccess({ Component.literal("Ages (${ages.size}): $listing") }, false)
        }
        return SUCCESS
    }
}
