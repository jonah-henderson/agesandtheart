package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.sky.Skies
import co.voik.agesandtheart.sky.SkySpec
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Scope
import co.voik.agesandtheart.age.word.grammar.Sentence
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
 * The `/age` debug command, until the player-facing books exist. Brigadier is vanilla, so the tree
 * lives in `common`; each loader hands us its [CommandDispatcher].
 *
 *   /age create <name> [seed]           — author a new Age (Spire preset) and persist it
 *   /age create <preset> <name> [seed]  — the same, from any [AgePreset]: `hills`, `caverns`, …
 *   /age compose <name> [seed] <spec>   — author one out of aspects: `terrain=hills sea=water`
 *   /age write <name> [seed] <words>    — author one out of *words*: `beautiful floating riddled`
 *   /age words                          — the vocabulary the Art currently knows
 *   /age tp <name>                      — travel to an Age
 *   /age delete <name>|all              — discard an Age (or every Age), chunks and all
 *   /age gen <name>                     — force-generate the spawn chunk and report what it made
 *   /age bench <name> [radius]          — time generating the chunks around the origin (ms/chunk)
 *   /age biomes <name> [radius]         — what share of the surface each biome covers (for weight tuning)
 *   /age compare <a> <b> [radius]       — do two Ages generate the same world, block for block?
 *   /age sky <name> [<spec>]            — read an Age's suns and moons, or preview different ones in it
 *   /age list                           — list known Ages (with their recipe)
 */
object AgeCommand {
    private const val OPERATOR_PERMISSION_LEVEL = 2
    private const val NAME_ARGUMENT = "name"
    private const val RADIUS_ARGUMENT = "radius"
    private const val SEED_ARGUMENT = "seed"
    private const val SPECIFICATION_ARGUMENT = "spec"
    private const val SENTENCE_ARGUMENT = "words"
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

    /** What `/age sky`'s preview spec may name, and the prefix its parameters carry. */
    private const val SKY_ASPECT = "sky"

    /** A terrain to satisfy `AgeComposition.parse`, which refuses a composition without one. Read by nothing. */
    private const val PREVIEW_SCAFFOLD = "terrain=hills"

    // Brigadier command result codes.
    private const val SUCCESS = 1
    private const val FAILURE = 0

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("age")
                .requires { source -> source.hasPermission(OPERATOR_PERMISSION_LEVEL) }
                .then(createSubcommand())
                .then(composeSubcommand())
                .then(writeSubcommand())
                .then(vocabularySubcommand())
                .then(teleportSubcommand())
                .then(deleteSubcommand())
                .then(generateSubcommand())
                .then(biomeCensusSubcommand())
                .then(benchmarkSubcommand())
                .then(compareSubcommand())
                .then(skySubcommand())
                .then(listSubcommand()),
        )
    }

    /**
     * `/age create [<preset>] <name> [<seed>]` — one branch per [AgePreset], built from the enum, so a
     * new preset is offered the moment it exists. The bare form stays Spire.
     *
     * The optional seed is what makes `/age compare` worth anything: an Age otherwise seeds itself from
     * its own name, so naming the seed is the only way to write the same recipe twice.
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

    /**
     * `/age compose <name> [<seed>] <spec>`, where the spec runs to the end of the line.
     *
     * The seed sits before the spec because a greedy argument can have nothing after it, and the seeded
     * tail is registered first so `compose age 42 …` reads the 42 as a seed.
     */
    private fun composeSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("compose").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .then(
                    Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg()).then(
                        Commands.argument(SPECIFICATION_ARGUMENT, StringArgumentType.greedyString())
                            .executes { context -> runCompose(context, LongArgumentType.getLong(context, SEED_ARGUMENT)) },
                    ),
                )
                .then(
                    Commands.argument(SPECIFICATION_ARGUMENT, StringArgumentType.greedyString())
                        .executes { context -> runCompose(context, seed = null) },
                ),
        )

    /**
     * `/age write <name> [<seed>] <words…>` — authors an Age out of words rather than aspect names.
     *
     * Shaped like `compose` so the two can be diffed: what this resolves to prints in `compose`'s own
     * spelling, and pasting that back with the same seed must give the same world.
     */
    private fun writeSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("write").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .then(
                    Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg()).then(
                        Commands.argument(SENTENCE_ARGUMENT, StringArgumentType.greedyString())
                            .executes { context -> runWrite(context, LongArgumentType.getLong(context, SEED_ARGUMENT)) },
                    ),
                )
                .then(
                    Commands.argument(SENTENCE_ARGUMENT, StringArgumentType.greedyString())
                        .executes { context -> runWrite(context, seed = null) },
                ),
        )

    private fun vocabularySubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("words").executes(::runVocabulary)

    private fun teleportSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("tp").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runTeleport),
        )

    /**
     * `/age sky <name> [<spec>]` — read an Age's sky, or preview a different one in it.
     *
     * The spec form sends a sky to everyone standing in the Age and changes nothing about the Age, so
     * walking out and back in reverts it. Read by [AgeComposition.parse], so it is spelled like `compose`.
     */
    private fun skySubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("sky").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runSkyReport(context, preview = null) }
                .then(
                    Commands.argument(SPECIFICATION_ARGUMENT, StringArgumentType.greedyString())
                        .executes { context ->
                            runSkyReport(context, StringArgumentType.getString(context, SPECIFICATION_ARGUMENT))
                        },
                ),
        )

    private fun generateSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("gen").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runGenerate),
        )

    private fun biomeCensusSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("biomes").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runBiomeCensus(context, SURVEY_RADIUS_CHUNKS) }
                .then(
                    Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(1, MAX_CENSUS_RADIUS))
                        .executes { context ->
                            runBiomeCensus(context, IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT))
                        },
                ),
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

    private fun runCreate(context: CommandContext<CommandSourceStack>, preset: AgePreset, seed: Long?): Int =
        write(context, AgeRecipe.worldFor(preset), seed)

    /** `/age compose <name> [<seed>] <spec>` — writes an Age out of aspects instead of naming a preset. */
    private fun runCompose(context: CommandContext<CommandSourceStack>, seed: Long?): Int {
        val specification = StringArgumentType.getString(context, SPECIFICATION_ARGUMENT)
        val composition = AgeComposition.parse(specification).getOrElse { problem ->
            context.source.sendFailure(Component.literal(problem.message ?: "Could not read '$specification'"))
            return FAILURE
        }
        return write(context, AgeWorld.Composed(composition), seed)
    }

    /** Writes an Age down and opens it — the tail `create` and `compose` share. */
    private fun write(context: CommandContext<CommandSourceStack>, world: AgeWorld, seed: Long?): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (!canWrite(source, name, id)) return FAILURE
        return open(source, name, id, AgeRecipe.written(source.server, world, seed ?: AgeRecipe.seedFor(id)))
    }

    /** Whether an Age called [name] can be written here at all — having said why, if not. */
    private fun canWrite(source: CommandSourceStack, name: String, id: ResourceLocation): Boolean {
        if (!Ages.isSupported()) {
            source.sendFailure(Component.literal("Runtime Ages aren't supported on this loader yet (NeoForge backend pending)"))
            return false
        }
        if (id in AgeSavedData.get(source.server).ages) {
            source.sendFailure(Component.literal("Age '$name' already exists"))
            return false
        }
        return true
    }

    /** Persists a recipe and opens its dimension — the last step of every way of authoring an Age. */
    private fun open(source: CommandSourceStack, name: String, id: ResourceLocation, recipe: AgeRecipe): Int {
        val level = Ages.create(source.server, id, recipe)
        if (level == null) {
            source.sendFailure(Component.literal("Could not create Age '$name'"))
            return FAILURE
        }
        source.sendSuccess({ Component.literal("Created Age '$name' [$recipe] ($id). Travel with /age tp $name") }, true)
        return SUCCESS
    }

    /**
     * `/age write <name> [<seed>] <words…>` — a sentence in, an Age out. [Resolver] does the work; this
     * reads the words and reports the composition, the cost, and every flaw with its reason.
     */
    private fun runWrite(context: CommandContext<CommandSourceStack>, seed: Long?): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (!canWrite(source, name, id)) return FAILURE

        val vocabulary = Vocabulary.of(source.server)
        reportProblems(source, vocabulary)
        val pages = StringArgumentType.getString(context, SENTENCE_ARGUMENT)
            .split(' ').filter(String::isNotBlank)
        val read = Grammar.read(vocabulary, pages)
        if (read.isEmpty) {
            source.sendFailure(Component.literal("An Age needs at least one word the Art can read"))
            return FAILURE
        }
        reportParse(source, read)

        val chosenSeed = seed ?: AgeRecipe.seedFor(id)
        val resolution = Resolver.resolve(vocabulary, read, chosenSeed)
        val result = open(source, name, id, AgeRecipe.written(source.server, resolution, chosenSeed))
        if (result == FAILURE) return FAILURE

        source.sendSuccess({ Component.literal("  cost ${resolution.cost}, ${resolution.instability}") }, false)
        for (flaw in resolution.instability.flaws) {
            source.sendSuccess({ Component.literal("  ! $flaw") }, false)
        }
        return SUCCESS
    }

    /**
     * What the Art made of the book, said out loud before the Age is opened — the stand-in for the
     * grammar having no punctuation (design §4.3.1), until scratch mode does it properly.
     */
    private fun reportParse(source: CommandSourceStack, read: Sentence) {
        for (said in read.constraints) {
            val aimed = when (val scope = said.scope) {
                is Scope.Everywhere ->
                    if (scope.emphasised.isEmpty()) "everywhere"
                    else "everywhere, most of all ${scope.emphasised.joinToString(" ") { it.key }}"
                is Scope.Confined -> scope.aspects.joinToString(" ") { it.key }.ifEmpty { "wherever it fits" }
            }
            val joined = said.group?.let { " (joined)" } ?: ""
            source.sendSuccess({ Component.literal("  ${said.word.name} → $aimed$joined") }, false)
        }
        // Vagueness, never instability (§4.3) — but said plainly, so a typo is visible.
        if (read.dropped.isNotEmpty()) {
            source.sendSuccess(
                { Component.literal("  unread, so the Age comes out vaguer: ${read.dropped.joinToString(" ")}") },
                false,
            )
        }
    }

    /** `/age words` — the whole vocabulary, with each word's tier and the aspects it may fill. */
    private fun runVocabulary(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val vocabulary = Vocabulary.of(source.server)
        reportProblems(source, vocabulary)
        if (vocabulary.words.isEmpty()) {
            source.sendFailure(Component.literal("The Art knows no words at all — is the mod's data pack loaded?"))
            return FAILURE
        }
        // Authored words are listed; derived ones are counted, since there is one per block in the pack.
        val authored = vocabulary.words.filter { it.id.namespace == Constants.MOD_ID }
        source.sendSuccess({ Component.literal("The Art knows ${vocabulary.words.size} words.") }, false)
        source.sendSuccess({ Component.literal("${authored.size} written by hand:") }, false)
        for (word in authored) {
            val about = if (word.aspects.isEmpty()) "anywhere" else word.aspects.joinToString(" ") { it.key }
            val asks = word.query.entries.sortedBy { it.key }
                .joinToString(" ") { (tag, weight) -> if (weight < 0) "-$tag" else tag }
            source.sendSuccess({ Component.literal("  ${word.name} — ${word.tier.key}, $about: $asks") }, false)
        }
        val structural = vocabulary.grammarWords
        if (structural.isNotEmpty()) {
            source.sendSuccess(
                { Component.literal("${structural.size} structural: ${structural.joinToString(" ") { it.name }}") },
                false,
            )
        }
        // Per namespace, which says at a glance whether a mod's content reached the vocabulary (§8).
        val derivedByPack = vocabulary.words.filter { it.id.namespace != Constants.MOD_ID }
            .groupingBy { it.id.namespace }.eachCount().entries.sortedByDescending { it.value }
        if (derivedByPack.isNotEmpty()) {
            val counts = derivedByPack.joinToString(", ") { (pack, many) -> "$pack $many" }
            source.sendSuccess({ Component.literal("${vocabulary.words.size - authored.size} derived — $counts") }, false)
            source.sendSuccess(
                { Component.literal("  say any block, biome or structure by name, e.g. 'copper_block', 'mansion'") },
                false,
            )
        }
        return SUCCESS
    }

    /**
     * Anything wrong with the loaded vocabulary. A word that could not be read must be reported, never
     * silently absent (design §3.3).
     */
    private fun reportProblems(source: CommandSourceStack, vocabulary: Vocabulary) {
        for (problem in vocabulary.problems) {
            source.sendFailure(Component.literal("Vocabulary problem: $problem"))
        }
    }

    /**
     * Generates the same square of chunks in two Ages and compares them block for block — the instrument
     * for "an Age is its recipe". Write two with a shared seed and this says whether they agree.
     *
     * Answers for the generator, not the save format: both are generated in one server run. Reads every
     * block rather than sampling, because the differences worth catching are small.
     */
    private fun runCompare(context: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = context.source
        val saved = AgeSavedData.get(source.server)
        val firstName = StringArgumentType.getString(context, FIRST_ARGUMENT)
        val secondName = StringArgumentType.getString(context, SECOND_ARGUMENT)

        val first = openNamedAge(source, firstName) ?: return FAILURE
        val second = openNamedAge(source, secondName) ?: return FAILURE

        val firstRecipe = saved.recipe(ageId(firstName))
        val secondRecipe = saved.recipe(ageId(secondName))
        source.sendSuccess({
            Component.literal(
                "Comparing '$firstName' [$firstRecipe] with '$secondName' [$secondRecipe]",
            )
        }, false)

        // Each world generated whole before the other is touched, so this asks whether the recipe
        // reproduces rather than whether two interleaved worlds happen to agree.
        val chunks = (-radius..radius).flatMap { chunkX -> (-radius..radius).map { chunkZ -> chunkX to chunkZ } }
        for ((chunkX, chunkZ) in chunks) first.getChunk(chunkX, chunkZ)
        for ((chunkX, chunkZ) in chunks) second.getChunk(chunkX, chunkZ)

        val differences = chunks.map { (chunkX, chunkZ) -> compareChunk(first, second, chunkX, chunkZ) }
        val differingBlocks = differences.sumOf { it.blocks }
        val differingChunks = differences.count { it.blocks > 0 }

        val verdict = if (differingBlocks == 0) {
            "identical: ${chunks.size} chunks agree block for block"
        } else {
            "$differingBlocks block(s) differ across $differingChunks of ${chunks.size} chunks"
        }
        source.sendSuccess({ Component.literal("  $verdict") }, false)
        differences.flatMap { it.examples }.take(MAX_REPORTED_DIFFERENCES).forEach { example ->
            source.sendSuccess({ Component.literal("  $example") }, false)
        }
        return SUCCESS
    }

    /** The Age called [name], opened — or null, having already said why. */
    private fun openNamedAge(source: CommandSourceStack, name: String): ServerLevel? {
        val id = ageId(name)
        if (id !in AgeSavedData.get(source.server).ages) {
            source.sendFailure(Component.literal("No Age named '$name' — create it with /age create $name"))
            return null
        }
        val level = Ages.open(source.server, id)
        if (level == null) source.sendFailure(Component.literal("Could not open Age '$name'"))
        return level
    }

    /** A column of the spawn chunk, by its local position and how high it stands. */
    private data class Column(val x: Int, val z: Int, val height: Int)

    /** The spawn chunk's tallest column — which is what reveals instanced geometry above the ground. */
    private fun tallestColumn(level: ServerLevel): Column {
        var tallest = Column(0, 0, Int.MIN_VALUE)
        for (localX in 0..<BLOCKS_PER_CHUNK) {
            for (localZ in 0..<BLOCKS_PER_CHUNK) {
                val height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, localX, localZ)
                if (height > tallest.height) tallest = Column(localX, localZ, height)
            }
        }
        return tallest
    }

    /** How far apart one chunk is in two Ages: how many blocks, and a few of them named. */
    private data class ChunkDifference(val blocks: Int, val examples: List<String>)

    /** Every block of one chunk against the other's, keeping the first few disagreements. */
    private fun compareChunk(first: ServerLevel, second: ServerLevel, chunkX: Int, chunkZ: Int): ChunkDifference {
        val here = first.getChunk(chunkX, chunkZ)
        val there = second.getChunk(chunkX, chunkZ)
        val cursor = BlockPos.MutableBlockPos()
        val examples = mutableListOf<String>()
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
        return ChunkDifference(differences, examples)
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

    /** Prints an Age's sky, and when [preview] is given, shows that one instead. */
    private fun runSkyReport(context: CommandContext<CommandSourceStack>, preview: String?): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name) ?: return FAILURE
        val recipe = AgeSavedData.get(source.server).recipe(ageId(name))

        val spec = if (preview == null) {
            AgeGeneration.skySpec(recipe)
        } else {
            previewSpec(source, preview, recipe.seed) ?: return FAILURE
        }

        if (preview != null) Skies.preview(level, spec)
        val heading = if (preview == null) "Age '$name' sky" else "Previewing in '$name' (reverts on re-entry)"
        source.sendSuccess({ Component.literal(heading) }, false)
        for (line in spec.described()) {
            source.sendSuccess({ Component.literal("  $line") }, false)
        }
        return SUCCESS
    }

    /**
     * The sky a preview spec asks for, or null having said why.
     *
     * Reuses `/age compose`'s parser, which refuses a composition with no terrain — so [PREVIEW_SCAFFOLD]
     * is prepended and ignored. Since that scaffold is invisible, naming any other aspect is refused
     * rather than silently overridden.
     */
    private fun previewSpec(source: CommandSourceStack, preview: String, seed: Long): SkySpec? {
        val strayAspects = preview.split(' ')
            .filter { token -> token.isNotBlank() }
            .map { token -> token.substringBefore('=') }
            .filterNot { named -> named == SKY_ASPECT || named.startsWith("$SKY_ASPECT.") }
        if (strayAspects.isNotEmpty()) {
            source.sendFailure(
                Component.literal(
                    "`/age sky` previews the sky only, but you named ${strayAspects.joinToString(" ")}. " +
                        "Write it as `sky=plain sky.suns=three`, and use `/age compose` to change anything else.",
                ),
            )
            return null
        }
        // An unknown option value is refused here, where `/age compose` keeps it: a composition is a save
        // and must keep saying what it said, but an instrument that silently ignores `orbits=wilde` and
        // shows the default is worse than one that refuses.
        val skyParameters = Sky.PLAIN.parameters.associateBy { parameter -> parameter.name }
        val unreadable = preview.split(' ')
            .filter { token -> token.isNotBlank() && token.startsWith("$SKY_ASPECT.") }
            .mapNotNull { token ->
                val name = token.substringBefore('=').removePrefix("$SKY_ASPECT.")
                val value = token.substringAfter('=', missingDelimiterValue = "")
                val parameter = skyParameters[name]
                    ?: return@mapNotNull "$name — no such sky parameter. Try: ${skyParameters.keys.joinToString(" ")}"
                if (parameter.accepts(value)) null
                else "$name=$value — try: ${parameter.options.joinToString(" ")}"
            }
        if (unreadable.isNotEmpty()) {
            source.sendFailure(Component.literal(unreadable.joinToString("; ")))
            return null
        }

        // The terrain here is scaffolding for the parser and is read by nothing.
        val composition = AgeComposition.parse("$PREVIEW_SCAFFOLD $preview").getOrElse { problem ->
            source.sendFailure(Component.literal(problem.message ?: "Could not read '$preview'"))
            return null
        }
        // The Age's own seed, so a preview differs from the real sky only where the words differ.
        return composition.sky.specFor(composition.optionsFor(Aspect.SKY, 0), seed)
    }

    private fun runTeleport(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name) ?: return FAILURE
        Ages.teleport(player, level)
        source.sendSuccess({ Component.literal("Travelled to Age '$name'") }, true)
        return SUCCESS
    }

    /**
     * Force-generates the Age's spawn chunk by the real chunk-gen path and reports what it made — a
     * headless sanity check needing no player.
     */
    private fun runGenerate(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name) ?: return FAILURE
        level.getChunk(0, 0) // force full generation of the spawn chunk
        val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0)
        val surfaceBlock = blockName(level, 0, surfaceY - 1, 0)
        val peak = tallestColumn(level)
        val peakBlock = blockName(level, peak.x, peak.height - 1, peak.z)
        source.sendSuccess({
            Component.literal(
                "Age '$name' spawn chunk: origin surface y=$surfaceY ($surfaceBlock); " +
                    "tallest column y=${peak.height} at (${peak.x},${peak.z}) ($peakBlock)",
            )
        }, false)
        surveyBiomes(level, SURVEY_RADIUS_CHUNKS).forEach { line ->
            source.sendSuccess({ Component.literal("  $line") }, false)
        }
        return SUCCESS
    }

    /**
     * Times full generation of the `(2·radius+1)²` chunks around the Age's origin — the expensive case,
     * since a density gradient packs the most instances there. Run it on a freshly created Age: chunks
     * already generated come from the cache and time nothing.
     */
    private fun runBenchmark(context: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name) ?: return FAILURE
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
     * What share of the surface each biome covers — the instrument for tuning biome weights, where
     * [surveyBiomes]'s "which biomes exist here" is the wrong question.
     *
     * Samples the biome source directly rather than generated chunks, so it measures the climate table
     * alone and costs no generation. Surface only.
     */
    private fun runBiomeCensus(context: CommandContext<CommandSourceStack>, radiusChunks: Int): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name) ?: return FAILURE

        val biomes = level.chunkSource.generator.biomeSource
        val climate = level.chunkSource.randomState().sampler()
        val surfaceQuartY = QuartPos.fromBlock(level.maxBuildHeight - 1)
        val quartRadius = QuartPos.fromBlock(radiusChunks * BLOCKS_PER_CHUNK)

        val counts = mutableMapOf<String, Int>()
        for (quartX in -quartRadius..quartRadius step SURVEY_QUART_STRIDE) {
            for (quartZ in -quartRadius..quartRadius step SURVEY_QUART_STRIDE) {
                val here = biomeName(biomes, climate, quartX, surfaceQuartY, quartZ)
                counts[here] = (counts[here] ?: 0) + 1
            }
        }
        val sampled = counts.values.sum()
        source.sendSuccess({
            Component.literal(
                "Age '$name' surface biomes: $sampled samples within $radiusChunks chunks, " +
                    "${counts.size} distinct",
            )
        }, false)
        // Commonest first: the question is nearly always "did the thing I named take more ground".
        for ((biome, count) in counts.entries.sortedByDescending { it.value }) {
            val share = PERCENT * count / sampled
            source.sendSuccess({ Component.literal("  ${"%5.2f".format(share)}%  $biome ($count)") }, false)
        }
        return SUCCESS
    }

    /**
     * Which biomes an Age's source places over a wide area, and whether they change with depth — the
     * latter being down to [co.voik.agesandtheart.worldgen.biome.ClimateDepth] alone.
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

    /** How far a census may reach. Larger than the benchmark's cap because this generates nothing. */
    private const val MAX_CENSUS_RADIUS = 512

    private const val PERCENT = 100.0
    private const val BLOCKS_PER_CHUNK = 16

    /** Wide enough to cross several biomes at vanilla's scale, and free since nothing is generated. */
    private const val SURVEY_RADIUS_CHUNKS = 64

    /**
     * Every Age and the recipe it is rebuilt from, one to a line. Unrecognised options are called out,
     * so a misspelt knob is distinguishable from one that had no effect.
     */
    private fun runList(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val saved = AgeSavedData.get(source.server)
        val ages = saved.ages
        if (ages.isEmpty()) {
            source.sendSuccess({ Component.literal("No Ages yet — write one with /age create <name>") }, false)
            return SUCCESS
        }
        source.sendSuccess({ Component.literal("Ages (${ages.size}):") }, false)
        for (id in ages) {
            val recipe = saved.recipe(id)
            source.sendSuccess({ Component.literal("  $id — $recipe") }, false)
            val unknown = recipe.composition?.unknownOptions.orEmpty()
            if (unknown.isNotEmpty()) {
                source.sendSuccess({ Component.literal("    (ignored, unrecognised: ${unknown.joinToString(" ")})") }, false)
            }
        }
        return SUCCESS
    }
}
