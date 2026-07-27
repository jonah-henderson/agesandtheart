package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.levelgen.Heightmap

/**
 * The `/age` debug command — the spike's trigger for exercising Age creation and travel. (The
 * player-facing Descriptive/Linking Books are the real interface; commands are the fast way to
 * drive the mechanic.)
 *
 * Brigadier is vanilla, so the whole command tree lives in `common`; each loader only has to hand
 * us its [CommandDispatcher] through its own command-registration event.
 *
 *   /age create <name> [seed]           — author a new Age (Spire preset) and persist it
 *   /age create <preset> <name> [seed]  — the same, from any [AgePreset]: `hills`, `caverns`, …
 *   /age tp <name>                      — travel to an Age
 *   /age delete <name>|all              — discard an Age (or every Age), chunks and all
 *   /age gen <name>                     — force-generate the spawn chunk and report what it made
 *   /age bench <name> [radius]          — time generating the chunks around the origin (ms/chunk)
 *   /age compare <a> <b> [radius]       — do two Ages generate the same world, block for block?
 *   /age list                           — list known Ages (with their recipe)
 */
object AgeCommand {
    private const val OPERATOR_PERMISSION_LEVEL = 2
    private const val NAME_ARGUMENT = "name"
    private const val RADIUS_ARGUMENT = "radius"
    private const val SEED_ARGUMENT = "seed"
    private const val FIRST_ARGUMENT = "first"
    private const val SECOND_ARGUMENT = "second"

    // Big enough to be dominated by generation rather than level-open overhead, small enough to run
    // on the server thread without tripping the watchdog.
    private const val DEFAULT_BENCHMARK_RADIUS = 8
    private const val MAX_BENCHMARK_RADIUS = 24
    private const val NANOS_PER_MILLISECOND = 1_000_000.0

    // A comparison reads every block of every chunk in range, so it stays small by default.
    private const val DEFAULT_COMPARE_RADIUS = 2
    private const val MAX_COMPARE_RADIUS = 8
    private const val MAX_REPORTED_DIFFERENCES = 3

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
                .then(compareSubcommand())
                .then(listSubcommand()),
        )
    }

    /**
     * `/age create [<preset>] <name> [<seed>]` — one branch per [AgePreset], built from the enum rather
     * than listed by hand, so a new preset is offered here the moment it exists. The bare form (no
     * preset) stays Spire, as it always has been.
     *
     * The optional seed is what makes `/age compare` worth anything: an Age normally seeds itself from
     * its own name, so two Ages can never be the same world by accident. Naming the seed is the only
     * way to write the *same* recipe twice.
     */
    private fun createSubcommand(): LiteralArgumentBuilder<CommandSourceStack> {
        val create = Commands.literal("create").then(namedAge(AgePreset.SPIRE))
        for (preset in AgePreset.entries) {
            create.then(Commands.literal(preset.key).then(namedAge(preset)))
        }
        return create
    }

    /** `<name> [<seed>]`, the tail every `create` branch ends in. */
    private fun namedAge(preset: AgePreset) =
        Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
            .executes { context -> runCreate(context, preset, seed = null) }
            .then(
                Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg())
                    .executes { context -> runCreate(context, preset, LongArgumentType.getLong(context, SEED_ARGUMENT)) },
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

    private fun compareSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("compare").then(
            Commands.argument(FIRST_ARGUMENT, StringArgumentType.word()).then(
                Commands.argument(SECOND_ARGUMENT, StringArgumentType.word())
                    .executes { context -> runCompare(context, DEFAULT_COMPARE_RADIUS) }
                    .then(
                        Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(0, MAX_COMPARE_RADIUS))
                            .executes { context ->
                                runCompare(context, IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT))
                            },
                    ),
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

    private fun runCreate(context: CommandContext<CommandSourceStack>, preset: AgePreset, seed: Long?): Int {
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
        val recipe = if (seed == null) AgeRecipe.forPreset(preset, id) else AgeRecipe(preset, seed)
        val level = Ages.create(source.server, id, recipe)
        if (level == null) {
            source.sendFailure(Component.literal("Could not create Age '$name'"))
            return FAILURE
        }
        source.sendSuccess({ Component.literal("Created Age '$name' [${preset.key}] ($id). Travel with /age tp $name") }, true)
        return SUCCESS
    }

    /**
     * Generates the same square of chunks in two Ages and compares them block for block.
     *
     * The instrument for the one property everything else assumes: **an Age is its recipe**, so the
     * same recipe and the same seed must give the same world. Write two Ages with a shared seed
     * (`/age create hills a 42` and `/age create hills b 42`) and this says whether they agree.
     *
     * Both are generated *in one server run*, one after the other, which is what makes it cheap enough
     * to use while iterating: no baseline save to keep, no second boot, no comparison of saved region
     * files. It answers for the generator, not for the save format.
     *
     * It reads every block rather than sampling, because the differences worth catching are small: a
     * scatter of ore in one chunk, a tree that moved. Radius stays low by default for the same reason.
     */
    private fun runCompare(context: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = context.source
        val saved = AgeSavedData.get(source.server)
        val firstName = StringArgumentType.getString(context, FIRST_ARGUMENT)
        val secondName = StringArgumentType.getString(context, SECOND_ARGUMENT)

        val first = openForCompare(context, firstName) ?: return FAILURE
        val second = openForCompare(context, secondName) ?: return FAILURE

        val firstRecipe = saved.recipe(ageId(firstName))
        val secondRecipe = saved.recipe(ageId(secondName))
        source.sendSuccess({
            Component.literal(
                "Comparing '$firstName' [${firstRecipe.preset.key} seed=${firstRecipe.seed}] " +
                    "with '$secondName' [${secondRecipe.preset.key} seed=${secondRecipe.seed}]",
            )
        }, false)

        // Each world generated whole before the other is touched, so this asks whether the recipe
        // reproduces — not whether two worlds interleaved on the chunk workers happen to agree.
        val chunks = (-radius..radius).flatMap { chunkX -> (-radius..radius).map { chunkZ -> chunkX to chunkZ } }
        for ((chunkX, chunkZ) in chunks) first.getChunk(chunkX, chunkZ)
        for ((chunkX, chunkZ) in chunks) second.getChunk(chunkX, chunkZ)

        var differingBlocks = 0
        var differingChunks = 0
        val examples = mutableListOf<String>()
        for ((chunkX, chunkZ) in chunks) {
            val differences = compareChunk(first, second, chunkX, chunkZ, examples)
            if (differences > 0) differingChunks++
            differingBlocks += differences
        }

        val verdict = if (differingBlocks == 0) {
            "identical: ${chunks.size} chunks agree block for block"
        } else {
            "$differingBlocks block(s) differ across $differingChunks of ${chunks.size} chunks"
        }
        source.sendSuccess({ Component.literal("  $verdict") }, false)
        examples.forEach { example -> source.sendSuccess({ Component.literal("  $example") }, false) }
        return SUCCESS
    }

    /** Opens an Age for comparison, complaining in the way the other subcommands do if it cannot. */
    private fun openForCompare(context: CommandContext<CommandSourceStack>, name: String): ServerLevel? {
        val source = context.source
        val id = ageId(name)
        if (id !in AgeSavedData.get(source.server).ages) {
            source.sendFailure(Component.literal("No Age named '$name' — create it with /age create $name"))
            return null
        }
        return Ages.open(source.server, id)
            ?: null.also { source.sendFailure(Component.literal("Could not open Age '$name'")) }
    }

    /** Every block of one chunk against the other's, collecting the first few disagreements. */
    private fun compareChunk(
        first: ServerLevel,
        second: ServerLevel,
        chunkX: Int,
        chunkZ: Int,
        examples: MutableList<String>,
    ): Int {
        val here = first.getChunk(chunkX, chunkZ)
        val there = second.getChunk(chunkX, chunkZ)
        val cursor = BlockPos.MutableBlockPos()
        var differences = 0

        for (localX in 0..<BLOCKS_PER_CHUNK) {
            for (localZ in 0..<BLOCKS_PER_CHUNK) {
                for (y in here.minBuildHeight..<here.maxBuildHeight) {
                    cursor.set(chunkX * BLOCKS_PER_CHUNK + localX, y, chunkZ * BLOCKS_PER_CHUNK + localZ)
                    val mine = here.getBlockState(cursor)
                    val theirs = there.getBlockState(cursor)
                    if (mine == theirs) continue
                    differences++
                    if (examples.size < MAX_REPORTED_DIFFERENCES) {
                        examples += "at (${cursor.x}, ${cursor.y}, ${cursor.z}): " +
                            "${BuiltInRegistries.BLOCK.getKey(mine.block)} vs ${BuiltInRegistries.BLOCK.getKey(theirs.block)}"
                    }
                }
            }
        }
        return differences
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
        surveyBiomes(level, SURVEY_RADIUS_CHUNKS).forEach { line ->
            source.sendSuccess({ Component.literal("  $line") }, false)
        }
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

    /**
     * What an Age's biome source would place over a wide area, asked of the source directly rather than
     * read back out of generated chunks. A biome source is a pure function of position, so this needs no
     * terrain at all and costs nothing — which is the only way to survey thousands of columns headlessly.
     *
     * Reports the two things that are actually in question: how varied the horizontal mosaic is, and
     * whether biomes change with **depth** — the latter being entirely down to
     * [co.voik.agesandtheart.worldgen.biome.ClimateDepth], since vanilla's underground biomes are reached
     * by the depth parameter or not at all.
     */
    private fun surveyBiomes(level: ServerLevel, radiusChunks: Int): List<String> {
        val source = level.chunkSource.generator.biomeSource
        val climate = level.chunkSource.randomState().sampler()
        val lowestQuartY = QuartPos.fromBlock(level.minBuildHeight)
        val highestQuartY = QuartPos.fromBlock(level.maxBuildHeight - 1)

        val everywhere = mutableSetOf<String>()
        val deepOnly = mutableSetOf<String>()
        var layeredColumns = 0
        var columns = 0

        val quartRadius = QuartPos.fromBlock(radiusChunks * BLOCKS_PER_CHUNK)
        for (quartX in -quartRadius..quartRadius step SURVEY_QUART_STRIDE) {
            for (quartZ in -quartRadius..quartRadius step SURVEY_QUART_STRIDE) {
                columns++
                val top = biomeName(source, climate, quartX, highestQuartY, quartZ)
                everywhere += top
                var layered = false
                for (quartY in lowestQuartY..highestQuartY) {
                    val here = biomeName(source, climate, quartX, quartY, quartZ)
                    everywhere += here
                    if (here != top) {
                        deepOnly += here
                        layered = true
                    }
                }
                if (layered) layeredColumns++
            }
        }
        return listOf(
            "$columns columns sampled within $radiusChunks chunks: ${everywhere.size} distinct biomes",
            "$layeredColumns of $columns columns change biome with depth",
            if (deepOnly.isEmpty()) "no below-surface biomes" else "below the surface: ${deepOnly.sorted().joinToString(", ")}",
        )
    }

    private fun biomeName(source: BiomeSource, climate: Climate.Sampler, quartX: Int, quartY: Int, quartZ: Int): String =
        source.getNoiseBiome(quartX, quartY, quartZ, climate).unwrapKey()
            .map { it.location().toString() }
            .orElse("(unnamed)")

    /** Every fourth quart cell, i.e. one column per 16 blocks — dense enough to find small biomes. */
    private const val SURVEY_QUART_STRIDE = 4
    private const val BLOCKS_PER_CHUNK = 16

    /** Wide enough to cross several biomes at vanilla's scale, and free since nothing is generated. */
    private const val SURVEY_RADIUS_CHUNKS = 64

    private fun runList(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val saved = AgeSavedData.get(source.server)
        val ages = saved.ages
        if (ages.isEmpty()) {
            source.sendSuccess({ Component.literal("No Ages yet — write one with /age create <name>") }, false)
        } else {
            val listing = ages.joinToString(", ") { id -> "$id [${saved.recipe(id).preset.key}]" }
            source.sendSuccess({ Component.literal("Ages (${ages.size}): $listing") }, false)
        }
        return SUCCESS
    }
}
