package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.sky.Skies
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.sky.SkySpec
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.generation.TerminalKind
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.age.word.grammar.Scope
import co.voik.agesandtheart.age.word.grammar.Sentence
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.random.Random
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.levelgen.Heightmap

/**
 * The `/age` debug command, until the player-facing books exist. Brigadier is vanilla, so the tree
 * lives in `common`; each loader hands us its [CommandDispatcher]. The preset named by `create` is an
 * [AgePreset].
 *
 * **Fenced because it is a usage synopsis**, where `[seed]` means an optional argument — outside a fence
 * KDoc reads every one of those as a link and reports it unresolved.
 *
 * ```
 * /age create <name> [seed]           — author a new Age (Spire preset) and persist it
 * /age create <preset> <name> [seed]  — the same, from any preset: hills, caverns, …
 * /age compose <name> [seed] <spec>   — author one out of aspects: terrain=hills sea=water
 * /age write <name> [seed] <words>    — author one out of *words*: beautiful floating riddled
 * /age words                          — the vocabulary the Art currently knows
 * /age tp <name>                      — travel to an Age
 * /age delete <name>|all              — discard an Age (or every Age), chunks and all
 * /age gen <name>                     — force-generate the spawn chunk and report what it made
 * /age bench <name> [radius]          — time generating the chunks around the origin (ms/chunk)
 * /age biomes <name> [radius]         — what share of the surface each biome covers (for weight tuning)
 * /age locate <name> <preset>         — how far to the nearest territory of that terrain, from where you stand
 * /age book [seed]                    — a book the Art could have written, for reading rather than using
 * /age draft <grammar> [seed]         — one expansion of a generation grammar: book, name, …
 * /age compare <a> <b> [radius]       — do two Ages generate the same world, block for block?
 * /age sky <name> [<spec>]            — read an Age's suns and moons, or preview different ones in it
 * /age list                           — list known Ages (with their recipe)
 * ```
 */
object AgeCommand {
    /**
     * Level 2, as it always was — but a named check now rather than an integer. Vanilla replaced numeric
     * permission levels with a [net.minecraft.server.permissions.PermissionSet], and `LEVEL_GAMEMASTERS`
     * is the one that used to be spelled `hasPermission(2)`.
     */
    private val OPERATOR_PERMISSION = Commands.hasPermission<CommandSourceStack>(Commands.LEVEL_GAMEMASTERS)
    private const val NAME_ARGUMENT = "name"
    private const val RADIUS_ARGUMENT = "radius"
    private const val SEED_ARGUMENT = "seed"
    private const val SPECIFICATION_ARGUMENT = "spec"
    private const val SENTENCE_ARGUMENT = "words"

    /** The generation grammar a found book is written from — `art/generation/book.json`. */
    private const val BOOK_GRAMMAR = "book"

    /** Vanilla's End arrival platform, which is where a portal would have put you. */
    private const val END_PLATFORM_X = 100.5
    private const val END_PLATFORM_Y = 49.0
    private const val END_PLATFORM_Z = 0.5
    private const val FIRST_ARGUMENT = "first"
    private const val PRESET_ARGUMENT = "preset"
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

    /** What `/age sky` prefixes the Age's clock reading with, and what `SkyClockCheck` looks for. */
    const val CLOCK_LABEL = "clock"

    /**
     * How far `/age locate` looks, and how finely. The stride is far below the smallest territory a share
     * can produce, so it cannot step over one.
     */
    private const val LOCATE_RADIUS_BLOCKS = 20_000
    private const val LOCATE_STRIDE_BLOCKS = 64

    /** A terrain to satisfy `AgeComposition.parse`, which refuses a composition without one. Read by nothing. */
    private const val PREVIEW_SCAFFOLD = "terrain=hills"

    // Brigadier command result codes.
    private const val SUCCESS = 1
    internal const val FAILURE = 0

    /**
     * A subcommand that can answer either way: as prose, or — written `/age <name> json …` — as one
     * structured document (see [Report]).
     *
     * **The literal goes immediately after the subcommand, not at the end**, and `/age write` is why: its
     * sentence is a greedy string, so anything after it is swallowed as another word. One position for all
     * of them beats a rule with an exception in it.
     *
     * [arguments] is called *twice* to build two independent subtrees. That is the point of taking a
     * builder rather than a node: a Brigadier node cannot be hung in two places, and writing the tree out
     * twice is the duplication that would drift.
     *
     * **Only commands that really build a document get one**, so a `json` that parses always means
     * structure exists behind it.
     */
    private fun reporting(
        name: String,
        arguments: (ReportFor) -> ArgumentBuilder<CommandSourceStack, *>,
    ): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name)
            .then(arguments { context -> Report.prose(context.source) })
            .then(
                Commands.literal(Report.STRUCTURED_LITERAL)
                    .then(arguments { context -> Report.structured(context.source) }),
            )

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("age")
                .requires(OPERATOR_PERMISSION)
                .then(createSubcommand())
                .then(composeSubcommand())
                .then(writeSubcommand())
                .then(vocabularySubcommand())
                .then(teleportSubcommand())
                .then(deleteSubcommand())
                .then(generateSubcommand())
                .then(locateSubcommand())
                .then(bookSubcommand())
                .then(draftSubcommand())
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
        reporting("write") { reportFor ->
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .then(
                    Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg()).then(
                        Commands.argument(SENTENCE_ARGUMENT, StringArgumentType.greedyString())
                            .executes { context ->
                                val seed = LongArgumentType.getLong(context, SEED_ARGUMENT)
                                runWrite(context, seed, reportFor(context))
                            },
                    ),
                )
                .then(
                    Commands.argument(SENTENCE_ARGUMENT, StringArgumentType.greedyString())
                        .executes { context -> runWrite(context, seed = null, report = reportFor(context)) },
                )
        }

    private fun vocabularySubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("words")
            .executes { context -> runVocabulary(context, Report.prose(context.source)) }
            .then(
                Commands.literal(Report.STRUCTURED_LITERAL)
                    .executes { context -> runVocabulary(context, Report.structured(context.source)) },
            )

    private fun teleportSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("tp")
            // Literals resolve before arguments, so these never shadow an Age that happens to share a
            // name — and they tab-complete, which is the whole point of having them.
            .then(Commands.literal("overworld").executes { runVanillaTeleport(it, Level.OVERWORLD) })
            .then(Commands.literal("nether").executes { runVanillaTeleport(it, Level.NETHER) })
            .then(Commands.literal("end").executes { runVanillaTeleport(it, Level.END) })
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runTeleport))

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

    private fun locateSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("locate").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).then(
                Commands.argument(PRESET_ARGUMENT, StringArgumentType.word()).executes(::runLocate),
            ),
        )

    private fun bookSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("book")
            .executes { context -> runBook(context, seed = context.source.level.gameTime) }
            .then(
                Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg())
                    .executes { context -> runBook(context, LongArgumentType.getLong(context, SEED_ARGUMENT)) },
            )

    private fun draftSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("draft").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runDraft(context, seed = context.source.level.gameTime) }
                .then(
                    Commands.argument(SEED_ARGUMENT, LongArgumentType.longArg())
                        .executes { context -> runDraft(context, LongArgumentType.getLong(context, SEED_ARGUMENT)) },
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
        reporting("compare") { reportFor ->
            Commands.argument(FIRST_ARGUMENT, StringArgumentType.word()).then(
                Commands.argument(SECOND_ARGUMENT, StringArgumentType.word())
                    .executes { context -> runCompare(context, DEFAULT_COMPARE_RADIUS, reportFor(context)) }
                    .then(
                        Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(0, MAX_COMPARE_RADIUS))
                            .executes { context ->
                                val radius = IntegerArgumentType.getInteger(context, RADIUS_ARGUMENT)
                                runCompare(context, radius, reportFor(context))
                            },
                    ),
            )
        }

    /** `all` is a literal rather than a name, so it cannot collide with an Age actually called "all". */
    private fun deleteSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("delete")
            .then(Commands.literal("all").executes(::runDeleteAll))
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runDelete))

    private fun listSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("list")
            .executes { context -> runList(context, Report.prose(context.source)) }
            .then(
                Commands.literal(Report.STRUCTURED_LITERAL)
                    .executes { context -> runList(context, Report.structured(context.source)) },
            )

    private fun ageId(name: String): Identifier =
        Identifier.fromNamespaceAndPath(Constants.MOD_ID, name.lowercase())

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
        val report = Report.prose(source)
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (!canWrite(source, name, id, report)) return FAILURE
        val recipe = AgeRecipe.written(source.server, world, seed ?: AgeRecipe.seedFor(id))
        return open(source, name, id, recipe, report)
    }

    /** Whether an Age called [name] can be written here at all — having said why, if not. */
    private fun canWrite(source: CommandSourceStack, name: String, id: Identifier, report: Report): Boolean {
        if (!Ages.isSupported()) {
            report.fail("Runtime Ages aren't supported on this loader yet (NeoForge backend pending)")
            return false
        }
        if (id in AgeSavedData.get(source.server).ages) {
            report.fail("Age '$name' already exists")
            return false
        }
        return true
    }

    /** Persists a recipe and opens its dimension — the last step of every way of authoring an Age. */
    private fun open(source: CommandSourceStack, name: String, id: Identifier, recipe: AgeRecipe, report: Report): Int {
        val level = Ages.create(source.server, id, recipe)
        if (level == null) return report.fail("Could not create Age '$name'")
        report.say { "Created Age '$name' [$recipe] ($id). Travel with /age tp $name" }
        return SUCCESS
    }

    /**
     * `/age write <name> [<seed>] <words…>` — a sentence in, an Age out. [Resolver] does the work; this
     * reads the words and reports the composition, the cost, and every flaw with its reason.
     */
    private fun runWrite(context: CommandContext<CommandSourceStack>, seed: Long?, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (!canWrite(source, name, id, report)) return FAILURE

        val vocabulary = Vocabulary.of(source.server)
        reportProblems(report, vocabulary)
        val pages = StringArgumentType.getString(context, SENTENCE_ARGUMENT)
            .split(' ').filter(String::isNotBlank)
        // **A page nobody recognises is not a thing that happens in play**: a player assembles a book from
        // pages, and every page carries a real word. Typing one here is a typo, so it fails the command
        // rather than quietly making a vaguer Age out of the rest.
        val unknown = pages.filter { vocabulary.word(it) == null && vocabulary.grammarWord(it) == null }
        if (unknown.isNotEmpty()) {
            return report.fail("The Art has never heard of ${unknown.joinToString(" ")}")
        }
        val read = Grammar.read(vocabulary, pages)
        if (read.isEmpty) return report.fail("An Age needs at least one word the Art can read")
        reportParse(report, read)

        val chosenSeed = seed ?: AgeRecipe.seedFor(id)
        val resolution = Resolver.resolve(vocabulary, read, chosenSeed)
        val recipe = AgeRecipe.written(source.server, resolution, chosenSeed)
        val result = open(source, name, id, recipe, report)
        if (result == FAILURE) return FAILURE

        report.only("age", id)
        report.only("recipe", recipe.world)
        report.only("seed", chosenSeed)
        report.fact("cost", resolution.cost) { "  cost ${resolution.cost}, ${resolution.instability}" }
        report.only("instability", resolution.instability.index)
        for (flaw in resolution.instability.flaws) {
            val fields = mapOf(
                "register" to flaw.register.key,
                "words" to flaw.words,
                "aspect" to flaw.aspect?.key,
                "severity" to flaw.severity,
            )
            report.entry("flaws", fields) { "  ! $flaw" }
        }
        report.finish()
        return SUCCESS
    }

    /**
     * What the Art made of the book, said out loud before the Age is opened — the stand-in for the desk's
     * scratch mode (design §7.5) until it exists, and the only way the grammar's attachment is visible at
     * all, since a book carries no punctuation to show it.
     *
     * The prose is the readout proper; the lines under it are this command's own, because `/age write` is
     * a debug tool and *where* a word reaches is what an author of the vocabulary needs to see. A desk
     * shows the prose alone.
     */
    private fun reportParse(report: Report, read: Sentence) {
        report.fact("readout", Readout.of(read)) { "  “${Readout.of(read)}”" }
        for (said in read.constraints) {
            val aimed = when (val scope = said.scope) {
                is Scope.Everywhere ->
                    if (scope.emphasised.isEmpty()) "everywhere"
                    else "everywhere, most of all ${scope.emphasised.joinToString(" ") { it.key }}"
                is Scope.Confined -> scope.aspects.joinToString(" ") { it.key }
            }
            val joined = said.group?.let { " (joined)" } ?: ""
            // Marked rather than hidden: the Age is built from the Art's own pages too, so a reader owed a
            // diagnosis has to see them — and they were never in the book, so they must not read as though
            // the writer had laid them.
            val whose = when {
                said.latent -> " (the Art's own)"
                said.rehomed -> " (moved here)"
                else -> ""
            }
            val fields = mapOf(
                "word" to said.word.name,
                "reaches" to said.scope.reaches(emptyList()).map { it.key },
                "polarity" to said.polarity.name.lowercase(),
                "density" to said.density.key,
                "joined" to (said.group != null),
                "latent" to said.latent,
                "rehomed" to said.rehomed,
            )
            report.entry("said", fields) { "    ${said.word.name} → $aimed$joined$whose" }
        }
        // A book that was not a sentence is repaired against one the Art draws for itself, and what it drew
        // is the natural course of a world nobody described that far. The book never shows it, so this is
        // the only place a writer can be told.
        val supplied = read.constraints.filter { it.latent }.map { it.word.name }
        report.only("supplied", supplied)
        if (supplied.isNotEmpty()) {
            report.styled {
                Component.literal("  your book was not a sentence, so the Art wrote the rest of it: ")
                    .append(Component.literal(supplied.joinToString(" ")).withStyle(ChatFormatting.GRAY))
            }
        }
        // The two ways a page can fail to be in the reading, said apart because they cost different things
        // (§4.3): one makes the Age vaguer and is charged nothing, the other is charged and charged dearly.
        // Both are struck through and said plainly, since the readout above renders only what parsed and
        // prose that quietly omitted a page would read as though it had worked (§4.3.1).
        report.only("unreadable", read.unreadable)
        if (read.unreadable.isNotEmpty()) {
            report.styled {
                val unread = Component.literal(read.unreadable.joinToString(" "))
                    .withStyle(ChatFormatting.STRIKETHROUGH)
                Component.literal("  unread, so the Age comes out vaguer: ").append(unread)
            }
        }
        report.only("impossible", read.impossible)
        if (read.impossible.isNotEmpty()) {
            report.styled {
                val nowhere = Component.literal(read.impossible.joinToString(" "))
                    .withStyle(ChatFormatting.STRIKETHROUGH)
                Component.literal("  no sentence has a place for: ").append(nowhere)
            }
        }
    }

    /** `/age words` — the whole vocabulary, with each word's tier and the aspects it may fill. */
    private fun runVocabulary(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val vocabulary = Vocabulary.of(source.server)
        reportProblems(report, vocabulary)
        if (vocabulary.words.isEmpty()) {
            return report.fail("The Art knows no words at all — is the mod's data pack loaded?")
        }
        // Authored words are listed; derived ones are counted, since there is one per block in the pack.
        val authored = vocabulary.words.filter { it.id.namespace == Constants.MOD_ID }
        report.fact("words", vocabulary.words.size) { "The Art knows ${vocabulary.words.size} words." }
        report.fact("authored", authored.size) { "${authored.size} written by hand:" }
        for (word in authored) {
            val about = if (word.aspects.isEmpty()) "anywhere" else word.aspects.joinToString(" ") { it.key }
            val asks = word.query.entries.sortedBy { it.key }
                .joinToString(" ") { (tag, weight) -> if (weight < 0) "-$tag" else tag }
            val fields = mapOf(
                "word" to word.name,
                "tier" to word.tier.key,
                "aspects" to word.aspects.map { it.key },
                "aims" to word.aims,
            )
            report.entry("authoredWords", fields) { "  ${word.name} — ${word.tier.key}, $about: $asks" }
        }
        val structural = vocabulary.grammarWords
        report.only("structural", structural.map { it.name })
        if (structural.isNotEmpty()) {
            report.say { "${structural.size} structural: ${structural.joinToString(" ") { it.name }}" }
        }
        // Per namespace, which says at a glance whether a mod's content reached the vocabulary (§8).
        val derivedByPack = vocabulary.words.filter { it.id.namespace != Constants.MOD_ID }
            .groupingBy { it.id.namespace }.eachCount().entries.sortedByDescending { it.value }
        report.only("derived", vocabulary.words.size - authored.size)
        if (derivedByPack.isNotEmpty()) {
            val counts = derivedByPack.joinToString(", ") { (pack, many) -> "$pack $many" }
            report.say { "${vocabulary.words.size - authored.size} derived — $counts" }
            report.say { "  say any block, biome or structure by name, e.g. 'copper_block', 'mansion'" }
        }
        report.finish()
        return SUCCESS
    }

    /**
     * Anything wrong with the loaded vocabulary. A word that could not be read must be reported, never
     * silently absent (design §3.3).
     */
    private fun reportProblems(report: Report, vocabulary: Vocabulary) {
        report.only("problems", vocabulary.problems)
        for (problem in vocabulary.problems) {
            report.say { "Vocabulary problem: $problem" }
        }
    }

    /**
     * Generates the same square of chunks in two Ages and compares them block for block — the instrument
     * for "an Age is its recipe". Write two with a shared seed and this says whether they agree.
     *
     * Answers for the generator, not the save format: both are generated in one server run. Reads every
     * block rather than sampling, because the differences worth catching are small.
     */
    private fun runCompare(context: CommandContext<CommandSourceStack>, radius: Int, report: Report): Int {
        val source = context.source
        val saved = AgeSavedData.get(source.server)
        val firstName = StringArgumentType.getString(context, FIRST_ARGUMENT)
        val secondName = StringArgumentType.getString(context, SECOND_ARGUMENT)

        val first = openNamedAge(source, firstName, report) ?: return FAILURE
        val second = openNamedAge(source, secondName, report) ?: return FAILURE

        val firstRecipe = saved.recipe(ageId(firstName))
        val secondRecipe = saved.recipe(ageId(secondName))
        report.say { "Comparing '$firstName' [$firstRecipe] with '$secondName' [$secondRecipe]" }

        // Each world generated whole before the other is touched, so this asks whether the recipe
        // reproduces rather than whether two interleaved worlds happen to agree.
        val chunks = (-radius..radius).flatMap { chunkX -> (-radius..radius).map { chunkZ -> chunkX to chunkZ } }
        for ((chunkX, chunkZ) in chunks) first.getChunk(chunkX, chunkZ)
        for ((chunkX, chunkZ) in chunks) second.getChunk(chunkX, chunkZ)

        val differences = chunks.map { (chunkX, chunkZ) -> compareChunk(first, second, chunkX, chunkZ) }
        val differingBlocks = differences.sumOf { it.blocks }
        val differingChunks = differences.count { it.blocks > 0 }

        // The prose says one of two sentences and the document always says the same three numbers: a
        // reader should not have to notice that "identical" is where the zero went.
        report.fact("differingBlocks", differingBlocks) {
            val verdict = if (differingBlocks == 0) {
                "identical: ${chunks.size} chunks agree block for block"
            } else {
                "$differingBlocks block(s) differ across $differingChunks of ${chunks.size} chunks"
            }
            "  $verdict"
        }
        report.only("differingChunks", differingChunks)
        report.only("chunks", chunks.size)
        report.only("identical", differingBlocks == 0)
        for (example in differences.flatMap { it.examples }.take(MAX_REPORTED_DIFFERENCES)) {
            report.entry("examples", mapOf("at" to example)) { "  $example" }
        }
        report.finish()
        return SUCCESS
    }

    /** The Age called [name], opened — or null, having already said why. */
    private fun openNamedAge(source: CommandSourceStack, name: String, report: Report): ServerLevel? {
        val id = ageId(name)
        if (id !in AgeSavedData.get(source.server).ages) {
            report.fail("No Age named '$name' — create it with /age create $name")
            return null
        }
        val level = Ages.open(source.server, id)
        if (level == null) report.fail("Could not open Age '$name'")
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
                for (y in here.minY..here.maxY) {
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
        val level = openNamedAge(source, name, Report.prose(source)) ?: return FAILURE
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
        // The Age's own clock — what every moving thing above reads, and the only way to tell an Age
        // following the overworld from one frozen at dawn.
        source.sendSuccess({ Component.literal("  $CLOCK_LABEL ${level.defaultClockTime}") }, false)
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
        val level = openNamedAge(source, name, Report.prose(source)) ?: return FAILURE
        Ages.teleport(player, level)
        source.sendSuccess({ Component.literal("Travelled to Age '$name'") }, true)
        return SUCCESS
    }

    /**
     * `/age tp overworld|nether|end` — back out of an Age without hunting for a book.
     *
     * A testing convenience, and it lands differently per dimension because one rule would be wrong
     * somewhere: the Overworld has a respawn point worth using, the Nether's surface heightmap finds the
     * bedrock roof, and the End has no terrain at all until you reach an island.
     */
    private fun runVanillaTeleport(
        context: CommandContext<CommandSourceStack>,
        target: ResourceKey<Level>,
    ): Int {
        val source = context.source
        val player = source.playerOrException
        val level = source.server.getLevel(target)
            ?: return Report.prose(source).fail("This world has no ${target.identifier().path}")

        val landing = when (target) {
            Level.OVERWORLD -> {
                val respawn = source.server.respawnData.globalPos().pos()
                Vec3(respawn.x + 0.5, respawn.y.toDouble(), respawn.z + 0.5)
            }
            // The obsidian platform, which is where a portal would have put you.
            Level.END -> Vec3(END_PLATFORM_X, END_PLATFORM_Y, END_PLATFORM_Z)
            else -> standingRoom(level, player.blockX, player.blockZ)
        }
        player.teleportTo(
            level, landing.x, landing.y, landing.z,
            emptySet(), player.yRot, player.xRot, true,
        )
        source.sendSuccess({ Component.literal("Travelled to ${target.identifier().path}") }, true)
        return SUCCESS
    }

    /**
     * The lowest gap with solid ground under it, searched downward.
     *
     * Downward rather than off the heightmap because the Nether's ceiling *is* its surface — the
     * heightmap would land you on top of the world.
     */
    private fun standingRoom(level: ServerLevel, x: Int, z: Int): Vec3 {
        level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
        val cursor = BlockPos.MutableBlockPos()
        for (y in level.maxY - 1 downTo level.minY + 1) {
            cursor.set(x, y, z)
            val head = level.getBlockState(cursor).isAir
            val feet = level.getBlockState(cursor.setY(y - 1)).isAir
            val floor = !level.getBlockState(cursor.setY(y - 2)).isAir
            if (head && feet && floor) return Vec3(x + 0.5, (y - 1).toDouble(), z + 0.5)
        }
        return Vec3(x + 0.5, (level.minY + 1).toDouble(), z + 0.5)
    }

    /**
     * Force-generates the Age's spawn chunk by the real chunk-gen path and reports what it made — a
     * headless sanity check needing no player.
     */
    private fun runGenerate(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name, Report.prose(source)) ?: return FAILURE
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
        val level = openNamedAge(source, name, Report.prose(source)) ?: return FAILURE
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
    /**
     * `/age locate <name> <preset>` — how far to the nearest territory of that terrain.
     *
     * The instrument for "is a rare share findable". Answered from [RegionMap] alone, which is a pure
     * function of the column, so this searches far beyond a `/locate` and generates nothing.
     */
    private fun runLocate(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val wanted = StringArgumentType.getString(context, PRESET_ARGUMENT)
        val recipe = AgeSavedData.get(source.server).recipe(ageId(name))

        val world = recipe.world
        if (world !is AgeWorld.Composed) {
            source.sendFailure(Component.literal("'$name' is a bespoke Age, so it has no territories to find."))
            return FAILURE
        }
        val composition = world.composition
        val member = composition.terrains.indexOfFirst { it.key == wanted }
        if (member < 0) {
            val present = composition.terrains.joinToString(" ") { it.key }
            source.sendFailure(Component.literal("'$name' has no '$wanted' territory. It has: $present"))
            return FAILURE
        }
        if (composition.terrains.size <= 1) {
            source.sendFailure(Component.literal("'$name' is undivided, so '$wanted' is everywhere."))
            return FAILURE
        }

        val map = recipe.character.mapFor(Aspect.TERRAIN, composition.sharesOf(Aspect.TERRAIN), recipe.seed)
        val from = BlockPos.containing(source.position)
        val found = nearestColumnOf(map, member, from.x, from.z)
        if (found == null) {
            source.sendFailure(
                Component.literal(
                    "No '$wanted' column within $LOCATE_RADIUS_BLOCKS blocks of you. That is a real answer " +
                        "about its share, not a failure to look.",
                ),
            )
            return FAILURE
        }
        val distance = Math.sqrt(
            ((found.x - from.x).toDouble() * (found.x - from.x) + (found.z - from.z).toDouble() * (found.z - from.z)),
        )
        source.sendSuccess({
            Component.literal(
                "Nearest '$wanted' in '$name': ${found.x}, ${found.z} — about %.0f blocks away".format(distance),
            )
        }, false)
        return SUCCESS
    }

    /**
     * The nearest column belonging to [member], or null within [LOCATE_RADIUS_BLOCKS].
     *
     * Rings outward on a stride rather than testing every column: a territory is hundreds of blocks
     * across, so a stride far below that cannot step over one, and it is the difference between a
     * millisecond and a minute.
     */
    private fun nearestColumnOf(map: RegionMap, member: Int, fromX: Int, fromZ: Int): BlockPos? {
        if (map.memberAt(fromX, fromZ) == member) return BlockPos(fromX, 0, fromZ)
        var ring = LOCATE_STRIDE_BLOCKS
        while (ring <= LOCATE_RADIUS_BLOCKS) {
            var nearest: BlockPos? = null
            var nearestDistance = Double.MAX_VALUE
            for (step in -ring..ring step LOCATE_STRIDE_BLOCKS) {
                for (candidate in ringColumnsAt(fromX, fromZ, ring, step)) {
                    if (map.memberAt(candidate.x, candidate.z) != member) continue
                    val away = (candidate.x - fromX).toDouble() * (candidate.x - fromX) +
                        (candidate.z - fromZ).toDouble() * (candidate.z - fromZ)
                    if (away < nearestDistance) {
                        nearestDistance = away
                        nearest = candidate
                    }
                }
            }
            if (nearest != null) return nearest
            ring += LOCATE_STRIDE_BLOCKS
        }
        return null
    }

    /** The four columns [step] along each side of the square ring at [ring] blocks out. */
    private fun ringColumnsAt(fromX: Int, fromZ: Int, ring: Int, step: Int): List<BlockPos> = listOf(
        BlockPos(fromX + step, 0, fromZ - ring),
        BlockPos(fromX + step, 0, fromZ + ring),
        BlockPos(fromX - ring, 0, fromZ + step),
        BlockPos(fromX + ring, 0, fromZ + step),
    )

    /** `/age book [<seed>]` — a book the Art could have written, run through the same parser a player's is. */
    private fun runBook(context: CommandContext<CommandSourceStack>, seed: Long): Int {
        val source = context.source
        val vocabulary = Vocabulary.of(source.server)
        val grammar = vocabulary.generation.grammar(BOOK_GRAMMAR)
        if (grammar == null) {
            source.sendFailure(Component.literal("This pack ships no '$BOOK_GRAMMAR' grammar, so the Art writes none"))
            return FAILURE
        }
        val pages = grammar.expand(Random(seed))
        source.sendSuccess({ Component.literal("A book at seed $seed, ${pages.size} pages:") }, false)
        source.sendSuccess({ Component.literal("  ${pages.joinToString(" ")}") }, false)
        // Said back through the readout, so what it *means* is visible beside what it says — which is the
        // only way to judge whether a generated book is a good one.
        val sentence = Grammar.read(vocabulary, pages)
        source.sendSuccess({ Component.literal("  reads as: ${Readout.of(sentence)}") }, false)
        source.sendSuccess({
            Component.literal("  write it with: /age write book$seed $seed ${pages.joinToString(" ")}")
        }, false)
        return SUCCESS
    }

    /**
     * `/age draft <grammar> [<seed>]` — one expansion of a generation grammar, for authoring them against.
     * `/reload` picks a rewritten grammar up, so this is the whole edit loop.
     */
    private fun runDraft(context: CommandContext<CommandSourceStack>, seed: Long): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val vocabulary = Vocabulary.of(source.server)
        val grammar = vocabulary.generation.grammar(name)
        if (grammar == null) {
            val known = vocabulary.generation.names.joinToString(" ").ifEmpty { "none" }
            source.sendFailure(Component.literal("No generation grammar called '$name'. Known: $known"))
            return FAILURE
        }
        val produced = grammar.expand(Random(seed))
        source.sendSuccess({ Component.literal("'$name' at seed $seed, ${produced.size} terminals:") }, false)
        source.sendSuccess({ Component.literal("  ${produced.joinToString(" ")}") }, false)
        // A word grammar is meant to produce a book, so it is judged the way a book is: by what the parser
        // makes of it, not by whether the words look plausible in a row.
        if (grammar.terminals == TerminalKind.WORD) {
            val sentence = Grammar.read(vocabulary, produced)
            source.sendSuccess({ Component.literal("  reads as: ${Readout.of(sentence)}") }, false)
            if (sentence.dropped.isNotEmpty()) {
                source.sendSuccess({
                    Component.literal("  unread: ${sentence.dropped.joinToString(" ")}").withStyle(ChatFormatting.RED)
                }, false)
            }
        }
        return SUCCESS
    }

    private fun runBiomeCensus(context: CommandContext<CommandSourceStack>, radiusChunks: Int): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val level = openNamedAge(source, name, Report.prose(source)) ?: return FAILURE

        val biomes = level.chunkSource.generator.biomeSource
        val climate = level.chunkSource.randomState().sampler()
        val surfaceQuartY = QuartPos.fromBlock(level.maxY)
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
        val lowestQuartY = QuartPos.fromBlock(level.minY)
        val highestQuartY = QuartPos.fromBlock(level.maxY)

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
            .map { it.identifier().toString() }
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
    private fun runList(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val saved = AgeSavedData.get(source.server)
        val ages = saved.ages
        report.only("count", ages.size)
        if (ages.isEmpty()) {
            report.say { "No Ages yet — write one with /age create <name>" }
            report.finish()
            return SUCCESS
        }
        report.say { "Ages (${ages.size}):" }
        for (id in ages) {
            val recipe = saved.recipe(id)
            val unknown = recipe.composition?.unknownOptions.orEmpty()
            val fields = mapOf(
                "age" to id,
                "recipe" to recipe.world,
                "seed" to recipe.seed,
                "sentence" to recipe.words.joinToString(" "),
                "instability" to recipe.instability.index,
                "unrecognised" to unknown,
            )
            report.entry("ages", fields) { "  $id — $recipe" }
            if (unknown.isNotEmpty()) {
                report.say { "    (ignored, unrecognised: ${unknown.joinToString(" ")})" }
            }
        }
        report.finish()
        return SUCCESS
    }
}
