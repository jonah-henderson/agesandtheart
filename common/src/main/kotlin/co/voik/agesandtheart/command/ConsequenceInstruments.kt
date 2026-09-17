package co.voik.agesandtheart.command

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.age.ReportFor
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.consequence.Collapse
import co.voik.agesandtheart.age.consequence.Consequence
import net.minecraft.server.MinecraftServer
import co.voik.agesandtheart.age.consequence.Wounds
import net.minecraft.world.Difficulty
import net.minecraft.world.DifficultyInstance
import co.voik.agesandtheart.age.consequence.Tearing
import co.voik.agesandtheart.age.reward.EarlyGameRareMaterials
import co.voik.agesandtheart.age.reward.Danger
import co.voik.agesandtheart.generation.AgeChunkGenerator
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel

internal object ConsequenceInstruments {

    /** What an Age's instability has bought it, and what it will have bought by a given day. */
    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(decaySubcommand())
            .then(dangerSubcommand())
            .then(woundsSubcommand())
    }

    /** `/age decay <name> age <days>` — the literal, and how far back it may reach. */
    private const val AGED_LITERAL = "age"

    /** `/age danger here` — a literal rather than a bare executable, so the tree stays uniform. */
    private const val HERE_LITERAL = "here"

    /** `/age danger score <name>` — the recipe's worth, as against [HERE_LITERAL]'s worth of the ground. */
    private const val SCORE_LITERAL = "score"

    /** How far `/age wounds here` looks — a good deal further than a wound corrupts, so it can say "none". */
    private const val LOOKS_FOR_WOUNDS_WITHIN = 128.0

    /** Vanilla's chance that a mob arrives with anything on at all, before the multiplier scales it. */
    private const val ARMS_ANYTHING_AT_ALL = 0.15f

    private const val DAYS_ARGUMENT = "days"

    private const val MOST_DAYS = 100_000

    /** `/age decay <name> unstable <n>` — the index, set by hand rather than earned. */
    private const val UNSTABLE_LITERAL = "unstable"

    private const val INDEX_ARGUMENT = "index"

    private const val MOST_INSTABILITY = 10_000

    /**
     * `/age decay <name>` — how far an Age has come apart, and `/age decay <name> age <days>` to make it
     * older than it is.
     *
     * **Without the second form none of this is observable.** Worsening and collapse are read against the
     * Age's own age (design §5.4), so the mildest of them takes four of its days to open one more wound per
     * chunk and the gentlest collapse a fortnight to be worth looking at. Backdating rewrites the one field
     * the clock is measured from, which is the whole of what "wait a month" means to everything downstream
     * — no separate debug path, and nothing that could disagree with the real one.
     */
    private fun decaySubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("decay").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runDecay(context, Report.prose(context.source)) }
                .then(
                    Commands.literal(AGED_LITERAL).then(
                        Commands.argument(DAYS_ARGUMENT, IntegerArgumentType.integer(0, MOST_DAYS))
                            .executes { context ->
                                runBackdate(
                                    context,
                                    IntegerArgumentType.getInteger(context, DAYS_ARGUMENT),
                                    Report.prose(context.source),
                                )
                            },
                    ),
                )
                .then(
                    Commands.literal(UNSTABLE_LITERAL).then(
                        Commands.argument(INDEX_ARGUMENT, IntegerArgumentType.integer(0, MOST_INSTABILITY))
                            .executes { context ->
                                runForceInstability(
                                    context,
                                    IntegerArgumentType.getInteger(context, INDEX_ARGUMENT),
                                    Report.prose(context.source),
                                )
                            },
                    ),
                ),
        )

    /**
     * **Two different questions that are both "how dangerous is this", kept in one branch** because a
     * reader looking for either would look here.
     *
     * `/age danger here` — what the ground you are standing on is worth, in vanilla's own terms.
     *
     * **Written because the register is otherwise unobservable** (Jonah, 2026-08-09, walked): a wound arms
     * what comes out of it by ageing the ground (§5.1), which is `DifficultyInstance` doing the work, and
     * `getSpecialMultiplier` is a probability rather than a visible state. A walk that sees no armoured
     * skeleton has learned nothing — the chance is a few per cent a mob — so this says the number instead.
     *
     * **F3 will not show this.** The debug screen computes local difficulty from the *client's* level, and
     * the seam a wound raises is on `ServerLevel.getCurrentDifficultyAt`, so the two legitimately disagree
     * and the client's is the one that is wrong.
     *
     * `/age danger score <name>` — what a written *recipe* is worth to §7.7's rewards, scored before the
     * Age exists rather than measured in it. That is what the geologic survey will read at the desk, and
     * what decides whether anything pays out at all.
     *
     * **Numbers, because this is an operator's tool.** §3.2 forbids showing a number to the *player*, and
     * the survey obeys that by saying "a trace" against "a great deal". Calibrating [DangerTable] means
     * seeing the arithmetic, so here it is spelled out.
     */
    private fun dangerSubcommand(): LiteralArgumentBuilder<CommandSourceStack> {
        fun scoreBranch(reportFor: ReportFor) = Commands.literal(SCORE_LITERAL).then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes { context -> runAgeDanger(context, reportFor(context)) },
        )
        return Commands.literal("danger")
            .then(
                Commands.literal(HERE_LITERAL)
                    .executes { context -> runDanger(context, Report.prose(context.source)) },
            )
            .then(scoreBranch { context -> Report.prose(context.source) })
            .then(
                Commands.literal(Report.STRUCTURED_LITERAL)
                    .then(scoreBranch { context -> Report.structured(context.source) }),
            )
    }

    /**
     * `/age wounds here` — **what this ground should hold against what it does.**
     *
     * The one question a walk cannot answer by looking: a landscape with no wounds in it is either an Age
     * that never bought any, ground that has not caught up, or placement putting them somewhere nobody
     * goes — and those look identical from a hilltop. Wanted against held tells the three apart in a line.
     */
    private fun woundsSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("wounds").then(
            Commands.literal(HERE_LITERAL).executes { context -> runWounds(context, Report.prose(context.source)) },
        )

    private fun runWounds(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val level = source.level
        val at = BlockPos.containing(source.position)
        val id = level.dimension().identifier()
        val recipe = Ages.recipeOf(level) ?: return report.fail("Not standing in an Age — /age tp <name> first")
        val spending = Spending.of(source.server, recipe)
        val days = recipe.ageAt(source.server) / Tearing.TICKS_PER_DAY
        val written = Tearing.writtenDensityAt(spending.bought(Manifestation.WOUNDS))
        val perDay = Tearing.woundsPerDayAt(spending.bought(Manifestation.WORSENING_WOUNDS))
        val density = Tearing.densityAt(written, perDay, days)
        val here = ChunkPos(at.x shr CHUNK_BITS, at.z shr CHUNK_BITS)
        val wanted = Tearing.wantedIn(here, level.seed, density)
        val holds = Wounds.countIn(level, here)

        var within = 0
        var nearest: BlockPos? = null
        var nearestAway = Double.MAX_VALUE
        Wounds.eachNear(level, at.center, LOOKS_FOR_WOUNDS_WITHIN) { wound ->
            within++
            val away = at.center.distanceTo(wound.center)
            if (away < nearestAway) {
                nearestAway = away
                nearest = wound
            }
        }

        report.say { "Standing in $id at ${at.x}, ${at.y}, ${at.z}, $days days on:" }
        report.fact("density", density) { "  the Age wants %.3f to a chunk".format(density) }
        report.fact("wantedHere", wanted) { "  this chunk wants $wanted" }
        report.fact("holdsHere", holds) { "  this chunk holds $holds" }
        report.fact("within", within) {
            "  $within within ${LOOKS_FOR_WOUNDS_WITHIN.toInt()} blocks"
        }
        val found = nearest
        if (found == null) {
            report.say { "  no wound in reach" }
        } else {
            report.fact("nearestAway", nearestAway) {
                "  nearest at ${found.x}, ${found.y}, ${found.z} — %.1f blocks".format(nearestAway)
            }
        }
        // The three readings a walk needs told apart, said outright rather than left to arithmetic.
        report.say {
            when {
                density <= Tearing.NONE -> "  → this Age bought no wounds at all"
                holds >= wanted -> "  → this chunk is up to date"
                perDay <= Tearing.NONE -> "  → behind, and nothing will fix it: it does not worsen, so nothing catches up"
                else -> "  → behind by ${wanted - holds}; leave until it unloads and return, or stand here for the creep"
            }
        }
        return SUCCESS
    }

    private fun runDanger(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val level = source.level
        val at = BlockPos.containing(source.position)
        // Vanilla's own answer, taken before ours can raise it — `Hostility` reads the wound and returns a
        // floor, so the pair is what says whether the register did anything here at all.
        val plain = DifficultyInstance(
            level.difficulty,
            level.overworldClockTime,
            level.getChunk(at).inhabitedTime,
            level.getMoonBrightness(at),
        )
        val raised = level.getCurrentDifficultyAt(at)
        val corruption = Wounds.corruptionAt(level, at.center)

        report.say { "Standing at ${at.x}, ${at.y}, ${at.z} in ${level.dimension().identifier()}:" }
        report.fact("difficulty", level.difficulty.serializedName) { "  world difficulty: ${level.difficulty.serializedName}" }
        report.fact("corruption", corruption) { "  corruption here: %.3f".format(corruption) }
        report.fact("inhabited", level.getChunk(at).inhabitedTime) {
            "  chunk really lived in for ${level.getChunk(at).inhabitedTime} ticks"
        }
        report.fact("effectiveWithout", plain.effectiveDifficulty) {
            "  effective difficulty without the wound: %.2f".format(plain.effectiveDifficulty)
        }
        report.fact("effectiveWith", raised.effectiveDifficulty) {
            "  effective difficulty as the Age has it: %.2f".format(raised.effectiveDifficulty)
        }
        report.fact("specialMultiplier", raised.specialMultiplier) {
            "  special multiplier: %.2f".format(raised.specialMultiplier)
        }
        // The whole point of the readout: vanilla refuses to arm anything below 2.0, and on Easy the ceiling
        // is 1.5 however lived-in the ground is — so the register cannot show there at all.
        if (raised.specialMultiplier <= 0.0f) {
            report.say {
                "  → nothing will spawn armed here: vanilla arms nothing below effective 2.0" +
                    if (level.difficulty == Difficulty.EASY) ", and Easy tops out at 1.5" else ""
            }
        } else {
            val chance = ARMS_ANYTHING_AT_ALL * raised.specialMultiplier
            report.say { "  → about %.0f%% of what spawns here should arrive armed".format(chance * PER_CENT) }
        }
        return SUCCESS
    }

    /**
     * What a written Age is worth to §7.7, contributor by contributor.
     *
     * **The parts as well as the total**, because one number cannot be calibrated: the question a tuning
     * session asks is *why* an Age scored what it did, and the answer is always which contributor carried
     * it. Reading the four beside the score is what turns `art/danger.json` from a guess into a dial.
     */
    private fun runAgeDanger(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val recipe = namedAge(source, name, report)?.recipe ?: return FAILURE
        val danger = Danger.of(source.server, recipe)

        report.say { "Age '$name':" }
        report.fact("materials", danger.materials) { "  what it is made of: %.3f".format(danger.materials) }
        report.fact("spawns", danger.spawns) { "  what lives in it:    %.3f".format(danger.spawns) }
        report.fact("phenomena", danger.phenomena) { "  what happens in it:  %.3f".format(danger.phenomena) }
        report.fact("lighting", danger.lighting) { "  how dark it is:      %.3f".format(danger.lighting) }
        report.fact("features", danger.features) { "  what is placed in it: %.3f".format(danger.features) }
        report.fact("score", danger.score) { "  → danger %.3f".format(danger.score) }
        report.fact("authored", danger.authored) {
            if (danger.authored) "  written by a player" else "  not written by a player, so it can never pay"
        }
        report.fact("paysOut", danger.paysOut) {
            if (danger.paysOut) "  pays out" else "  pays nothing"
        }
        report.fact("allowsRuins", danger.allowsRuins) {
            if (danger.allowsRuins) "  quiet enough to hold ruins" else "  too dangerous to hold ruins"
        }
        // Exposed rather than folded into the score: §7.7's terminal multiplier is still an open question.
        report.fact("terminal", danger.terminal) {
            if (danger.terminal > 0.0) "  and it will not last: collapse reach %.2f".format(danger.terminal) else ""
        }
        // **The other half of what a recipe pays out** (design §7.7), and here because it is the same
        // question asked of the same recipe: the desk's survey answers it before a book is written, and
        // there was nowhere to ask it of an Age that already exists. Which matters most for the ones that
        // are hard to see — arc crystal is a hundred and twenty blocks up before it is anything at all.
        recipe.composition?.let { composition ->
            val grown = EarlyGameRareMaterials.grownIn(composition, recipe.seed, Spending.of(source.server, recipe))
            report.fact("grows", grown.map { it.name.lowercase() }) {
                if (grown.isEmpty()) "  grows none of the early materials"
                else "  grows " + grown.joinToString(", ") { it.name.lowercase() }
            }
        }
        report.finish()
        return SUCCESS
    }

    private fun runDecay(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val (id, recipe) = namedAge(source, name, report) ?: return FAILURE
        val spending = Spending.of(source.server, recipe)
        val days = recipe.ageAt(source.server) / Tearing.TICKS_PER_DAY
        val written = Tearing.writtenDensityAt(spending.bought(Manifestation.WOUNDS))
        val perDay = Tearing.woundsPerDayAt(spending.bought(Manifestation.WORSENING_WOUNDS))
        val density = Tearing.densityAt(written, perDay, days)
        val tears = Collapse.tearsPerCellAt(spending.bought(Manifestation.COLLAPSE))

        report.say { "Age '$name' has stood $days days (instability ${recipe.instability.index}):" }
        report.fact("days", days) { "  days: $days" }
        report.fact("spending", spending.toString()) { "  bought: $spending" }
        report.fact("woundsWritten", written) { "  wounds the book tore: %.3f per chunk".format(written) }
        report.fact("woundsPerDay", perDay) { "  worsening: %.3f more per chunk each day".format(perDay) }
        report.fact("woundsNow", density) { "  wounds now: %.3f per chunk".format(density) }
        report.fact("collapseTears", tears) {
            if (tears <= Collapse.NONE) "  no tears in the floor" else "  $tears tear(s) to every 96 blocks, widening"
        }
        // Where to walk. One tear to a 512-block cell is not something anybody finds by looking.
        if (tears > Collapse.NONE) {
            val level = Ages.open(source.server, id)
            val here = BlockPos.containing(source.position)
            val (originX, originZ) = Collapse.nearestOriginTo(level.seed, here.x, here.z, tears)
            report.say { "  nearest tear opened at $originX, $originZ — /age tp $name then go there" }
        }
        return SUCCESS
    }

    /**
     * Make an Age older than it is, by moving the tick it was written on backwards.
     *
     * Generation reads the clock per chunk, so unvisited ground comes out at the new age immediately.
     * Ground that already exists is brought up by `Worsening`'s catch-up as its chunks reload — so unloading
     * and returning is what makes it agree, and an Age that does not worsen has no catch-up and keeps
     * what it was made with.
     */
    private fun runBackdate(context: CommandContext<CommandSourceStack>, days: Int, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val (id, recipe) = namedAge(source, name, report) ?: return FAILURE
        val now = source.server.overworld().gameTime
        val aged = recipe.copy(writtenAt = now - days * Tearing.TICKS_PER_DAY)
        AgeSavedData.get(source.server).add(id, aged)
        // **And the live generator, or nothing changes until the Age is reopened** (walked 2026-08-09).
        // A generator is built once at open and keeps its own copy of the clock, so rewriting the recipe
        // alone left an Age reporting a month and generating as though it were new.
        retellTheGenerator(source.server, id, aged)
        report.say { "Age '$name' now reads as $days days old." }
        report.say { "  ground it has not generated comes out at the new age; ground that exists catches up as it reloads, if the Age worsens." }
        return SUCCESS
    }

    /**
     * Tell a running Age that its recipe changed, so generation stops answering from the one it opened with.
     *
     * Without this a rewritten recipe reaches every *report* and no *chunk*, which is exactly the shape of
     * bug that had collapse announcing a radius of 240 and generating a solid world (walked 2026-08-09).
     */
    private fun retellTheGenerator(server: MinecraftServer, id: Identifier, recipe: AgeRecipe) {
        val level = Ages.open(server, id)
        val generator = level.chunkSource.generator as? AgeChunkGenerator ?: return
        generator.rewriteConsequence(Consequence.of(server, recipe))
    }

    /**
     * Set an Age's instability outright, so the consequence registers can be tested without writing a book
     * that earns them.
     *
     * Reaching the worsening honestly takes an index near sixty-five and collapse near a hundred, which is two dozen
     * pages opposing two dozen different things — a great deal of fighting the vocabulary to exercise
     * arithmetic the vocabulary has nothing to do with. The index goes through the real price list from
     * here, so what it buys is exactly what a book of that index would have bought.
     */
    private fun runForceInstability(context: CommandContext<CommandSourceStack>, index: Int, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val (id, recipe) = namedAge(source, name, report) ?: return FAILURE
        val forced = recipe.copy(instability = Instability.forced(index))
        AgeSavedData.get(source.server).add(id, forced)
        retellTheGenerator(source.server, id, forced)
        val spending = Spending.of(source.server, forced)
        report.say { "Age '$name' is now instability $index, which buys $spending." }
        report.say { "  walk to ground it has not generated yet. What exists catches up as it reloads if this bought worsening wounds; without them, existing ground keeps what it was made with." }
        return SUCCESS
    }

}
