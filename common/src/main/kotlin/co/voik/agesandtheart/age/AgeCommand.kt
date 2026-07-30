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
 * The `/age` debug command — the spike's trigger for exercising Age creation and travel. (The
 * player-facing Descriptive/Linking Books are the real interface; commands are the fast way to
 * drive the mechanic.)
 *
 * Brigadier is vanilla, so the whole command tree lives in `common`; each loader only has to hand
 * us its [CommandDispatcher] through its own command-registration event.
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

    /**
     * A terrain to satisfy `AgeComposition.parse`, which refuses a composition without one. Read by nothing —
     * see [previewSpec].
     */
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

    /**
     * `/age compose <name> [<seed>] <spec>`, where the spec runs to the end of the line.
     *
     * The seed sits *before* the spec because a greedy argument can have nothing after it. Both tails
     * are offered under the name, and the seeded one is registered first so that `compose age 42 …`
     * reads the 42 as a seed rather than as the first word of a spec — which no spec could start with,
     * since every token in one is `aspect=preset`.
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
     * `/age write <name> [<seed>] <words…>` — the first command that authors an Age the way a *writer*
     * will, out of words rather than out of aspect names.
     *
     * Shaped exactly like `compose` (greedy tail, optional seed in front of it) so the two can be diffed
     * against each other: what this resolves to prints in `compose`'s own spelling, so pasting that into
     * `/age compose` with the same seed must give the same world. A free harness for the one property
     * everything downstream leans on.
     *
     * No parsing beyond splitting on spaces — grammar is Phase 4. A word nobody knows is refused *here*,
     * because this is a command and a typo is a mistake; the pen proper must never refuse a sentence
     * (design §2), since validation would make precision risk-free.
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
     * `/age sky <name> [<spec>]` — read an Age's sky, or *preview* a different one in it.
     *
     * The instrument that makes orbits tunable. An orbit is a thing you have to see to judge, and
     * re-authoring an Age to move a sun ten degrees would make that loop useless — so the spec form sends a
     * sky to everyone standing in the Age and changes nothing about the Age itself. Walk out and back in and
     * the recipe's own sky returns, which makes the preview self-cancelling.
     *
     * The spec is read by [AgeComposition.parse], the same parser `/age compose` uses, so it is written the
     * same way — `sky=storm sky.suns=three sky.orbits=wild` — and cannot drift out of step with it.
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

    /**
     * `/age compose <name> [<seed>] <spec>` — writes an Age out of aspects instead of naming a preset.
     *
     * The nearest thing to authorship the mod has before books exist: the spec is the same sentence a
     * writer will eventually write with symbols, spelled `terrain=hills sea=water`. Failing loudly on
     * a name nobody knows is right *here* and wrong in the pen — see [AgeComposition.Companion.parse].
     */
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
     * `/age write <name> [<seed>] <words…>` — a sentence in, an Age out. The first time the mod does the
     * thing it is *for*.
     *
     * Everything interesting happens in [Resolver]; this reads the words, hands them over, and then says
     * out loud what came back — the composition in `/age compose`'s own spelling, what the sentence cost
     * in fine inks, and every flaw with its reason. That last part is the design's central promise: a
     * flawed Age must be **diagnosable**, which is what separates this from arbitrary punishment.
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
     * What the Art made of the book, said out loud before the Age is opened.
     *
     * **The mitigation for the grammar having no punctuation** (design §4.3.1). A writer lays out a flat row
     * of pages and the sections exist only in the parser, so without this the rules are invisible and Phase 4
     * is being tested blind. Scratch mode does it properly in Phase 5; until then, this is it.
     *
     * The fiction makes the honest version legal: a player already knows the concepts and lacks only the
     * D'ni words for them (§4.5), so telling them what was read is a translation, not hand-holding.
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
        // Vagueness, never instability: what could not be read makes the Age less determined and is charged
        // nothing at all (§4.3). Said plainly so a typo is visible rather than merely ineffective.
        if (read.dropped.isNotEmpty()) {
            source.sendSuccess(
                { Component.literal("  unread, so the Age comes out vaguer: ${read.dropped.joinToString(" ")}") },
                false,
            )
        }
    }

    /**
     * `/age words` — the whole vocabulary, since which words exist is otherwise invisible until books do.
     *
     * Prints the tier and the aspects each word may fill, because those two are what make a sentence
     * behave the way it does: a word about the sky cannot pin the ground, and a vague word cannot fail.
     */
    private fun runVocabulary(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val vocabulary = Vocabulary.of(source.server)
        reportProblems(source, vocabulary)
        if (vocabulary.words.isEmpty()) {
            source.sendFailure(Component.literal("The Art knows no words at all — is the mod's data pack loaded?"))
            return FAILURE
        }
        // Authored words are listed; derived ones are *counted*. There is a word for every block in the
        // pack, so printing them all is thousands of lines of chat that bury the handful anyone needs to
        // read — and a derived word needs no explanation anyway, since it names exactly the thing it spells.
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
        // Counted per namespace, which is the useful cut: it says at a glance whether a mod's content
        // reached the vocabulary at all, which is the interop promise §8 makes.
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
     * Anything wrong with the loaded vocabulary, said before it can cause confusion.
     *
     * §3.3's one hard requirement: a word that could not be read must be *reported*, never silently
     * absent — a corpus quietly missing a word is indistinguishable from a resolver that ignored it.
     */
    private fun reportProblems(source: CommandSourceStack, vocabulary: Vocabulary) {
        for (problem in vocabulary.problems) {
            source.sendFailure(Component.literal("Vocabulary problem: $problem"))
        }
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
        // reproduces — not whether two worlds interleaved on the chunk workers happen to agree.
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

    /**
     * The Age called [name], opened — or null, having already said why. Every subcommand that takes an
     * Age by name starts here, so they all fail the same way and in the same words.
     */
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

    /**
     * Prints an Age's sky, and when [preview] is given, shows that one instead.
     *
     * Both halves print, because seeing the numbers is most of the value: "three suns" says nothing about why
     * two of them ended up bunched together, and the tilts do.
     */
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
     * **Reuses `/age compose`'s parser, which needs propping up to do it.** `AgeComposition.parse` refuses a
     * composition with no terrain — rightly, since an Age needs one — so a sky-only spec like `sky.suns=two` is
     * rejected out of hand. Discovered by running it rather than by reading it: the first version of this command
     * was unusable for exactly this reason.
     *
     * So a throwaway terrain is prepended and then ignored. The alternative was a second parser for sky options
     * alone, which would be one more thing to keep in step with `compose`'s spelling for no gain.
     *
     * The scaffold is *invisible* to the writer, which makes naming any other aspect a trap: `terrain=pillars`
     * would be silently overridden and the writer told nothing. So anything that is not the sky is **refused**
     * rather than dropped — §3.3's rule, applied to a debug command because the argument holds there too.
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
        // **An unknown option value is refused here, where `/age compose` keeps it.** That difference is
        // deliberate and the two are right for different reasons. A composition is a *save*: it must keep saying
        // what it said even when this pack no longer understands a word, so `compose` records an unrecognised
        // option and reports it through `/age list`. A preview is an *instrument*, and an instrument that
        // silently ignores `orbits=wilde` and shows you the default is worse than one that refuses — you would
        // stand there wondering why nothing moved. Found by running it: `sky.suns=nonsense` previewed one sun and
        // said nothing.
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
        // The Age's own seed, so a preview differs from the real sky only where the *words* differ — which is what
        // makes two previews comparable to each other and to the Age.
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
     * Force-generates the Age's spawn column (the real chunk-gen path, same as travel) and reports
     * what the generator produced there — a headless sanity check for a generator without needing a
     * player to travel. Reads the surface height and a few probe blocks (surface, sea, sky).
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
     * Times full generation of the `(2·radius+1)²` chunks around the Age's origin — the measurement
     * behind performance decisions like whether the instancer needs per-chunk memoisation. Centred on
     * the origin deliberately: that's where a density gradient packs the most instances into a column,
     * so it's the expensive case. Run it on a **freshly created** Age — chunks already generated come
     * from the cache and would time nothing.
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
     * What an Age's biome source would place over a wide area, asked of the source directly rather than
     * read back out of generated chunks. A biome source is a pure function of position, so this needs no
     * terrain at all and costs nothing — which is the only way to survey thousands of columns headlessly.
     *
     * Reports the two things that are actually in question: how varied the horizontal mosaic is, and
     * whether biomes change with **depth** — the latter being entirely down to
     * [co.voik.agesandtheart.worldgen.biome.ClimateDepth], since vanilla's underground biomes are reached
     * by the depth parameter or not at all.
     */
    /**
     * What share of the surface each biome actually covers — the instrument for tuning biome weights.
     *
     * [surveyBiomes] answers "which biomes exist here", which is the wrong question for weighting: naming
     * `cherry_grove` is meant to make cherry grove *commoner*, and a set tells you nothing about commoner.
     * Written after a walk where a named biome's nearest instance sat at the same distance as in an Age that
     * had never named it — which could equally mean the weighting did a little or did nothing at all, and
     * guessing between those two would have meant tuning a constant that might not be the problem.
     *
     * Samples the **biome source directly** rather than generated chunks, so it costs no chunk generation
     * and measures the climate table alone — decoration, carvers and structures cannot muddy the answer.
     * Surface only, since that is where biome weighting is judged; [surveyBiomes] is still the one to ask
     * about the underground.
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
        // Commonest first, because the question is nearly always "did the thing I named take more ground".
        for ((biome, count) in counts.entries.sortedByDescending { it.value }) {
            val share = PERCENT * count / sampled
            source.sendSuccess({ Component.literal("  ${"%5.2f".format(share)}%  $biome ($count)") }, false)
        }
        return SUCCESS
    }

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

    /**
     * How far a census may reach. Larger than the benchmark's cap because this generates nothing — it asks
     * the biome source directly — so the only cost is arithmetic, and a rare biome needs ground to be rare in.
     */
    private const val MAX_CENSUS_RADIUS = 512

    private const val PERCENT = 100.0
    private const val BLOCKS_PER_CHUNK = 16

    /** Wide enough to cross several biomes at vanilla's scale, and free since nothing is generated. */
    private const val SURVEY_RADIUS_CHUNKS = 64

    /**
     * Every Age and the recipe it is rebuilt from, one to a line — because a composed recipe is a
     * whole sentence now and no longer fits alongside eleven others on one.
     *
     * Options nobody recognises are called out rather than left silent, which is the only way a
     * misspelt knob is distinguishable from one that simply had no effect.
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
