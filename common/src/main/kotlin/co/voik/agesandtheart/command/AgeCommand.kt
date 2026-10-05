package co.voik.agesandtheart.command

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.generation.AgeGeneration
import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.age.CompositionSpelling
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.platform.Services
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.age.word.grammar.Sentence
import co.voik.agesandtheart.worldgen.ruins.CavernRuins
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
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
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel

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
 * /age compose <name> [seed] <spec>   — author one out of aspects: landmass=gentle sea=water
 * /age write <name> [seed] <words>    — author one out of *words*: beautiful floating riddled
 * /age words                          — the vocabulary the Art currently knows
 * /age tp <name>                      — travel to an Age
 * /age delete <name>|all              — discard an Age (or every Age), chunks and all
 * /age list                           — list known Ages (with their recipe)
 * ```
 *
 * The instruments that measure or force something — `/age probe`, `/age compare`, `/age sky` and the
 * rest, with the debug affordances `/age weather`, `/age bind`, `/age pages` and `/age forget` — are
 * [AgeInstruments], which [register] adds to the tree only in a development environment.
 */
object AgeCommand {
    /**
     * Level 2, as it always was — but a named check now rather than an integer. Vanilla replaced numeric
     * permission levels with a [net.minecraft.server.permissions.PermissionSet], and `LEVEL_GAMEMASTERS`
     * is the one that used to be spelled `hasPermission(2)`.
     */
    private val OPERATOR_PERMISSION = Commands.hasPermission<CommandSourceStack>(Commands.LEVEL_GAMEMASTERS)
    private const val SENTENCE_ARGUMENT = "words"

    /** Vanilla's End arrival platform, which is where a portal would have put you. */
    private const val END_PLATFORM_X = 100.5
    private const val END_PLATFORM_Y = 49.0
    private const val END_PLATFORM_Z = 0.5

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val age = Commands.literal("age")
            .requires(OPERATOR_PERMISSION)
            .then(createSubcommand())
            .then(composeSubcommand())
            .then(writeSubcommand())
            .then(vocabularySubcommand())
            .then(teleportSubcommand())
            .then(deleteSubcommand())
            .then(renameSubcommand())
            .then(listSubcommand())
        if (Services.PLATFORM.isDevelopment) AgeInstruments.addTo(age)
        dispatcher.register(age)
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

    /** `all` is a literal rather than a name, so it cannot collide with an Age actually called "all". */
    private fun deleteSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("delete")
            .then(Commands.literal("all").executes(::runDeleteAll))
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runDelete))

    /**
     * `/age rename <name> <new name>` — the move a bound book makes of the crystal viewer's preview, by hand,
     * so the one path that moves a world's files can be driven and checked.
     */
    private fun renameSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("rename").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).then(
                Commands.argument(NEW_NAME_ARGUMENT, StringArgumentType.word()).executes(::runRename),
            ),
        )

    private fun runRename(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val newName = StringArgumentType.getString(context, NEW_NAME_ARGUMENT)
        if (!Ages.rename(source.server, ageId(name), ageId(newName))) {
            source.sendFailure(Component.literal("Could not rename Age '$name' — no such Age, '$newName' is taken, or someone is in it"))
            return FAILURE
        }
        source.sendSuccess({ Component.literal("Renamed Age '$name' to '$newName'") }, true)
        return SUCCESS
    }

    private fun listSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("list")
            .executes { context -> runList(context, Report.prose(context.source)) }
            .then(
                Commands.literal(Report.STRUCTURED_LITERAL)
                    .executes { context -> runList(context, Report.structured(context.source)) },
            )

    private fun runCreate(context: CommandContext<CommandSourceStack>, preset: AgePreset, seed: Long?): Int =
        write(context, AgeRecipe.worldFor(preset), seed)

    /** `/age compose <name> [<seed>] <spec>` — writes an Age out of aspects instead of naming a preset. */
    private fun runCompose(context: CommandContext<CommandSourceStack>, seed: Long?): Int {
        val specification = StringArgumentType.getString(context, SPECIFICATION_ARGUMENT)
        val written = CompositionSpelling.read(specification).getOrElse { problem ->
            context.source.sendFailure(Component.literal(problem.message ?: "Could not read '$specification'"))
            return FAILURE
        }
        return write(context, AgeWorld.Composed(written.composition), seed, written.template, written.instability)
    }

    /** Writes an Age down and opens it — the tail `create` and `compose` share. */
    private fun write(
        context: CommandContext<CommandSourceStack>,
        world: AgeWorld,
        seed: Long?,
        template: AgeTemplate = AgeTemplate.ORDINARY,
        instability: Instability = Instability.NONE,
    ): Int {
        val source = context.source
        val report = Report.prose(source)
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (!canWrite(source, name, id, report)) return FAILURE
        val recipe = AgeRecipe.written(
            source.server,
            world,
            // As `runWrite`: no seed means roll one, and the report names it.
            seed ?: AgeRecipe.freshSeed(),
            template,
            instability = instability,
        )
        return open(source, name, id, recipe, report)
    }

    /** Whether an Age called [name] can be written here at all — having said why, if not. */
    private fun canWrite(source: CommandSourceStack, name: String, id: Identifier, report: Report): Boolean {
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
        // The one refusal in the Art (§4.3.1): name the thing you are making, or there is no book. Without
        // it an empty book would be a free reroll on a random Age, which is what repair would hand back.
        val read = Grammar.read(vocabulary, pages)
            ?: return report.fail("A book opens with the Age page — write 'age' first, then what it is like")
        if (read.isEmpty) return report.fail("An Age needs at least one word the Art can read")
        reportParse(report, read)

        // **An omitted seed is a roll, not the name's hash.** See `AgeRecipe.freshSeed`; the seed is
        // printed with the Age, so a world worth keeping can be pinned by writing it back.
        val chosenSeed = seed ?: AgeRecipe.freshSeed()
        val resolution = Resolver.resolve(vocabulary, read, chosenSeed)
        val recipe = AgeRecipe.written(source.server, resolution, pages, chosenSeed)
        val result = open(source, name, id, recipe, report)
        if (result == FAILURE) return FAILURE

        report.only("age", id)
        report.only("recipe", recipe.world)
        report.only("seed", chosenSeed)
        report.only("cavernRuins", recipe.composition?.let(CavernRuins::qualifies) == true)
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
            val aimed =
                if (said.aimedAt.isNotEmpty()) said.aimedAt.joinToString(" ") { it.page } else "everywhere"
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
                "reaches" to said.aimedAt.sortedBy { it.ordinal }.map { it.page },
                "polarity" to said.polarity.name.lowercase(),
                "density" to Rung.spelled(said.density),
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
            val about = if (word.aspects.isEmpty()) "anywhere" else word.aspects.joinToString(" ") { it.page }
            // Every tag it asks for, wherever it asks — a global tilt and a keyed one read the same here.
            val asks = (word.wanted.sorted() + word.unwanted.sorted().map { "-$it" }).joinToString(" ")
            val fields = mapOf(
                "word" to word.name,
                "firmness" to word.firmness.key,
                "aspects" to word.aspects.map { it.page },
                "aims" to word.aims,
            )
            report.entry("authoredWords", fields) { "  ${word.name} — ${word.firmness.key}, $about: $asks" }
        }
        val structural = vocabulary.grammarWords
        report.only("structural", structural.map { it.name })
        if (structural.isNotEmpty()) {
            report.say { "${structural.size} structural: ${structural.joinToString(" ") { it.name }}" }
        }
        // **What a vague word can actually reach**, per aspect — the tag layer measured where its tags are
        // bound, which offline is exactly where they are not (`notes/the-tag-layer.md` §4). A pool far
        // larger than the hand-authored table is the derivation having fired.
        for (aspect in Aspect.entries) {
            val reachable = vocabulary.availableToBroadWordsIn(aspect).size
            if (reachable == 0) continue
            report.entry("reach", mapOf("aspect" to aspect.page, "reachable" to reachable)) {
                "  ${aspect.page}: $reachable reachable by description"
            }
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
     * Every Age and the recipe it is rebuilt from, one to a line. Unrecognised options are called out,
     * so a misspelt parameter is distinguishable from one that had no effect.
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
            val recipe = saved.recipe(id) ?: continue
            val unknown = recipe.composition?.unknownOptions.orEmpty()
            val fields = mapOf(
                "age" to id,
                "recipe" to recipe.world,
                "seed" to recipe.seed,
                "sentence" to recipe.words.joinToString(" "),
                "instability" to recipe.instability.index,
                // Which of the four pre-authored types it wears — invisible in game until you notice the
                // world is not dark, and the one thing `/age list` could not answer.
                "dimensionType" to AgeGeneration.dimensionType(recipe),
                "unrecognised" to unknown,
                // Told apart from the above: this one is spelled right and cannot be honoured here.
                "unhonoured" to recipe.unhonoured,
            )
            report.entry("ages", fields) { "  $id — $recipe" }
            if (unknown.isNotEmpty()) {
                report.say { "    (ignored, unrecognised: ${unknown.joinToString(" ")})" }
            }
            // Said apart from the above, because the remedy is: that one is misspelled, this one is spelled
            // perfectly and asks the world it was written over for something it has no way to give.
            for (limit in recipe.unhonoured) report.say { "    (the world it was written over: $limit)" }
        }
        report.finish()
        return SUCCESS
    }
}
