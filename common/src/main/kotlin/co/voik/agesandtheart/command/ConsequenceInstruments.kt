package co.voik.agesandtheart.command

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.compat.center
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.age.ReportFor
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.SpendingSetting
import co.voik.agesandtheart.age.consequence.Collapse
import co.voik.agesandtheart.age.consequence.Consequence
import co.voik.agesandtheart.age.consequence.StrangeGravity
import net.minecraft.server.MinecraftServer
import co.voik.agesandtheart.age.consequence.Wounds
import net.minecraft.world.Difficulty
import net.minecraft.world.DifficultyInstance
import co.voik.agesandtheart.age.consequence.Tearing
import co.voik.agesandtheart.age.reward.EarlyGameRareMaterials
import co.voik.agesandtheart.age.reward.Danger
import co.voik.agesandtheart.age.reward.DangerTable
import co.voik.agesandtheart.generation.AgeChunkGenerator
import co.voik.agesandtheart.generation.Skies
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.phenomena.Fog
import co.voik.agesandtheart.age.phenomena.FogDials
import co.voik.agesandtheart.age.phenomena.Happenings
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
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

    /**
     * `/age unstable` — everything about how an Age is coming apart, in one place: what its instability drew and
     * what shows now, forcing any manifestation by hand, its index and its age, and what its ground and its
     * recipe are worth.
     */
    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(unstableSubcommand())
    }

    /** `/age unstable here …` — the Age being stood in, and the ground underfoot. */
    private const val HERE_LITERAL = "here"

    /** `/age unstable <name> age <days>` — the literal, and how far back it may reach. */
    private const val AGED_LITERAL = "age"

    /** `/age unstable <name> index <n>` — the index, set by hand rather than earned. */
    private const val INDEX_LITERAL = "index"

    /** `/age unstable <name> score` — the recipe's worth, as against `here danger`'s worth of the ground. */
    private const val SCORE_LITERAL = "score"

    /** `/age unstable here danger` — what the ground underfoot is worth in vanilla's terms. */
    private const val DANGER_LITERAL = "danger"

    /** `/age unstable here wounds` — wanted against held, in this chunk. */
    private const val WOUNDS_LITERAL = "wounds"

    /** `/age unstable <name> force <manifestation> <steps>` — a manifestation set by hand, until a restart. */
    private const val FORCE_LITERAL = "force"

    /** `/age unstable <name> clear` — every forced manifestation back to what was drawn. */
    private const val CLEAR_LITERAL = "clear"

    /** How far `/age unstable here wounds` looks — a good deal further than a wound corrupts, so it can say "none". */
    private const val LOOKS_FOR_WOUNDS_WITHIN = 128.0

    /** Vanilla's chance that a mob arrives with anything on at all, before the multiplier scales it. */
    private const val ARMS_ANYTHING_AT_ALL = 0.15f

    private const val DAYS_ARGUMENT = "days"

    private const val MOST_DAYS = 100_000

    private const val INDEX_ARGUMENT = "index"

    private const val MOST_INSTABILITY = 10_000

    private const val MANIFESTATION_ARGUMENT = "manifestation"

    private const val STEPS_ARGUMENT = "steps"

    /** More steps than any ladder sells; a forced dial is held to what its own ladder does. */
    private const val MOST_STEPS = 64

    private fun unstableSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("unstable")
            .then(
                Commands.literal(HERE_LITERAL)
                    .executes { context -> runUnstableHere(context, Report.prose(context.source)) }
                    .then(
                        Commands.literal(DANGER_LITERAL)
                            .executes { context -> runDanger(context, Report.prose(context.source)) },
                    )
                    .then(
                        Commands.literal(WOUNDS_LITERAL)
                            .executes { context -> runWounds(context, Report.prose(context.source)) },
                    ),
            )
            .then(namedBranch { context -> Report.prose(context.source) })
            .then(
                Commands.literal(Report.STRUCTURED_LITERAL)
                    .then(namedBranch { context -> Report.structured(context.source) }),
            )

    /**
     * Everything asked of a named Age. Built twice, once per way of answering — see [reporting].
     *
     * `age <days>` exists because **without it none of the decay is observable**: worsening and collapse are
     * read against the Age's own age (design §5.4), so the mildest takes four of its days to open one more
     * wound per chunk. Backdating rewrites the one field the clock is measured from — no separate debug path,
     * and nothing that could disagree with the real one.
     */
    private fun namedBranch(reportFor: ReportFor) =
        Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
            .executes { context -> runUnstableNamed(context, reportFor(context)) }
            .then(
                Commands.literal(AGED_LITERAL).then(
                    Commands.argument(DAYS_ARGUMENT, IntegerArgumentType.integer(0, MOST_DAYS))
                        .executes { context ->
                            runBackdate(context, IntegerArgumentType.getInteger(context, DAYS_ARGUMENT), reportFor(context))
                        },
                ),
            )
            .then(
                Commands.literal(INDEX_LITERAL).then(
                    Commands.argument(INDEX_ARGUMENT, IntegerArgumentType.integer(0, MOST_INSTABILITY))
                        .executes { context ->
                            runForceInstability(
                                context,
                                IntegerArgumentType.getInteger(context, INDEX_ARGUMENT),
                                reportFor(context),
                            )
                        },
                ),
            )
            .then(Commands.literal(SCORE_LITERAL).executes { context -> runAgeDanger(context, reportFor(context)) })
            .then(
                Commands.literal(FORCE_LITERAL).then(
                    Commands.argument(MANIFESTATION_ARGUMENT, StringArgumentType.word())
                        .suggests { _, builder ->
                            SharedSuggestionProvider.suggest(Manifestation.entries.map { it.key }, builder)
                        }
                        .then(
                            Commands.argument(STEPS_ARGUMENT, IntegerArgumentType.integer(0, MOST_STEPS))
                                .executes { context -> runForce(context, reportFor(context)) },
                        ),
                ),
            )
            .then(Commands.literal(CLEAR_LITERAL).executes { context -> runClear(context, reportFor(context)) })

    private fun runUnstableHere(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val level = context.source.level
        val recipe = Ages.recipeOf(level) ?: return report.fail("Not standing in an Age — /age tp <name> first")
        return reportUnstable(context.source, level.dimension().identifier(), recipe, report)
    }

    private fun runUnstableNamed(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val (id, recipe) = namedAge(context.source, name, report) ?: return FAILURE
        return reportUnstable(context.source, id, recipe, report)
    }

    /**
     * How far an Age has come apart: what its instability drew, what is forced over that, what each
     * manifestation is doing now, and how holed it is by its age.
     */
    private fun reportUnstable(source: CommandSourceStack, id: Identifier, recipe: AgeRecipe, report: Report): Int {
        val server = source.server
        val drawn = Spending.of(server, recipe.copy(forced = emptyMap()))
        val spending = Spending.of(server, recipe)
        val setting = SpendingSetting.of(server, recipe.composition, recipe.template, recipe.seed)
        val days = recipe.ageAt(server) / Tearing.TICKS_PER_DAY
        val written = Tearing.writtenDensityAt(spending.bought(Manifestation.WOUNDS))
        val perDay = Tearing.woundsPerDayAt(spending.bought(Manifestation.WORSENING_WOUNDS))
        val density = Tearing.densityAt(written, perDay, days)
        val tears = Collapse.tearsPerCellAt(spending.bought(Manifestation.COLLAPSE))

        report.say { "${id.path}: instability ${recipe.instability.index}, $days days old" }
        report.fact("days", days) { "  days: $days" }
        report.fact("drawn", drawn.toString()) { "  drew: $drawn" }
        report.fact("forced", recipe.forced.mapKeys { it.key.key }) {
            if (recipe.forced.isEmpty()) "  nothing forced"
            else "  forced: " + recipe.forced.entries.joinToString { "${it.key.key}×${it.value}" } + " (until a restart)"
        }
        report.fact("has", setting.has.map { it.serializedName }) {
            "  has ${setting.has.joinToString { it.serializedName }.ifEmpty { "nothing" }} to show on"
        }
        val level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id))
        val showing = Manifestation.entries.filter { spending.bought(it) > 0 }
        if (showing.isEmpty()) report.say { "  shows nothing" }
        for (manifestation in showing) {
            val now = if (level == null) "not open, so nothing is happening" else nowIn(level, recipe, spending, manifestation)
            report.say { "  ${manifestation.key} ${stepsOf(spending, manifestation)}: $now" }
        }
        report.fact("woundsWritten", written) { "  wounds the book tore: %.3f per chunk".format(written) }
        report.fact("woundsPerDay", perDay) { "  worsening: %.3f more per chunk each day".format(perDay) }
        report.fact("woundsNow", density) { "  wounds now: %.3f per chunk".format(density) }
        report.fact("collapseTears", tears) {
            if (tears <= Collapse.NONE) "  no tears in the floor" else "  $tears tear(s) to every 96 blocks, widening"
        }
        // Where to walk. One tear to a 512-block cell is not something anybody finds by looking.
        if (tears > Collapse.NONE) {
            val opened = Ages.open(server, id)
            val here = BlockPos.containing(source.position)
            val (originX, originZ) = Collapse.nearestOriginTo(opened.seed, here.x, here.z, tears)
            report.say { "  nearest tear opened at $originX, $originZ" }
        }
        report.finish()
        return SUCCESS
    }

    private fun stepsOf(spending: Spending, manifestation: Manifestation): String =
        if (manifestation.dials.size == 1) "×${spending.bought(manifestation)}"
        else manifestation.dials.joinToString(prefix = "(", postfix = ")") { "$it×${spending.bought(manifestation, it)}" }

    /** What [manifestation] is doing in [level] at this moment, in a few words. */
    private fun nowIn(level: ServerLevel, recipe: AgeRecipe, spending: Spending, manifestation: Manifestation): String {
        val raining = if (level.isRaining) "raining now" else "not raining now"
        return when (manifestation) {
            Manifestation.TORN_SEAMS, Manifestation.STAMPED_SHAPES, Manifestation.LEAFLESS_TREES,
            Manifestation.ABANDONED_VILLAGES -> "in ground generated since it was bought"
            Manifestation.WOUNDS, Manifestation.WORSENING_WOUNDS, Manifestation.COLLAPSE -> "see the wound lines below"
            Manifestation.SKY_DRIFT, Manifestation.DAY_STARS, Manifestation.LOW_CLOUDS, Manifestation.UNCANNY_MOTES,
            Manifestation.MONOCHROME, Manifestation.SKY_TEARS -> "always, for anyone in it"
            Manifestation.STILL_CREATURES -> "on every creature as it loads"
            Manifestation.STRANGE_GRAVITY ->
                "everything falls at %.0f%% of vanilla's pull".format(StrangeGravity.factorFor(spending, recipe.seed) * PER_CENT)
            Manifestation.SLOW_RAIN -> "whenever it rains — $raining"
            Manifestation.BLIZZARD, Manifestation.DELUGE -> "with the weather — $raining"
            Manifestation.TEMPEST -> if (level.isThundering) "thundering now" else "not thundering now"
            Manifestation.INFERNO -> if (level.isBrightOutside) "under open light — burning now" else "dark now, so not burning"
            Manifestation.SANDFALL, Manifestation.METEORS, Manifestation.TECTONICS -> "comes and goes on its own clock"
            Manifestation.FOG -> {
                val composition = recipe.composition
                val written = if (composition == null) emptyList() else Happenings.claimsIn(composition)
                val density = Happenings.befalling(written, spending)[Phenomenon.FOG] ?: Rung.ORDINARY
                val lying = Fog.lyingAt(level, density, FogDials.of(spending))
                if (lying <= 0.0) "no bank lying now" else "a bank %.0f%% in now".format(lying * PER_CENT)
            }
        }
    }

    /** Set one manifestation by hand, over the draw, until the server restarts. */
    private fun runForce(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val key = StringArgumentType.getString(context, MANIFESTATION_ARGUMENT)
        val steps = IntegerArgumentType.getInteger(context, STEPS_ARGUMENT)
        val manifestation = Manifestation.entries.firstOrNull { it.key == key }
            ?: return report.fail("No manifestation '$key' — one of ${Manifestation.entries.joinToString { it.key }}")
        val (id, recipe) = namedAge(source, name, report) ?: return FAILURE
        val forced = recipe.copy(forced = recipe.forced + (manifestation to steps))
        retell(source.server, id, forced)
        report.say { "Age '$name' now shows ${manifestation.key} at $steps step(s) whatever it drew, until a restart." }
        report.say { "  it buys ${Spending.of(source.server, forced)}" }
        report.say { "  what shapes the ground shows only in ground generated from now; a creature takes it as it loads." }
        return SUCCESS
    }

    private fun runClear(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val (id, recipe) = namedAge(source, name, report) ?: return FAILURE
        val cleared = recipe.copy(forced = emptyMap())
        retell(source.server, id, cleared)
        report.say { "Age '$name' shows what it drew again: ${Spending.of(source.server, cleared)}" }
        return SUCCESS
    }

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
        // **And the live generator, or nothing changes until the Age is reopened** (walked 2026-08-09).
        // A generator is built once at open and keeps its own copy of the clock, so rewriting the recipe
        // alone left an Age reporting a month and generating as though it were new.
        retell(source.server, id, aged)
        report.say { "Age '$name' now reads as $days days old." }
        report.say { "  ground it has not generated comes out at the new age; ground that exists catches up as it reloads, if the Age worsens." }
        return SUCCESS
    }

    /**
     * File [recipe] as [id]'s and tell the running Age, so generation and its look stop answering from the
     * recipe it opened with.
     *
     * Without this a rewritten recipe reaches every *report* and no *chunk*, which is exactly the shape of
     * bug that had collapse announcing a radius of 240 and generating a solid world (walked 2026-08-09).
     */
    private fun retell(server: MinecraftServer, id: Identifier, recipe: AgeRecipe) {
        AgeSavedData.get(server).add(id, recipe)
        val level = Ages.open(server, id)
        (level.chunkSource.generator as? AgeChunkGenerator)?.rewriteConsequence(Consequence.of(server, recipe))
        Skies.describe(level)
    }

    /**
     * Set an Age's instability outright, so the consequence registers can be tested without writing a book
     * that earns them.
     *
     * Reaching the worsening honestly takes an index past a hundred and collapse two hundred, which is a
     * great deal of fighting the vocabulary to exercise arithmetic the vocabulary has nothing to do with. The
     * index goes through the real price list and the Age's own seed from here, so what it buys is exactly
     * what a book of that index would have bought in that Age.
     */
    private fun runForceInstability(context: CommandContext<CommandSourceStack>, index: Int, report: Report): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val (id, recipe) = namedAge(source, name, report) ?: return FAILURE
        val forced = recipe.copy(instability = Instability.forced(index))
        retell(source.server, id, forced)
        val spending = Spending.of(source.server, forced)
        report.say { "Age '$name' is now instability $index, which buys $spending." }
        val setting = SpendingSetting.of(source.server, forced.composition, forced.template, forced.seed)
        report.say { "  it has ${setting.has.joinToString { it.serializedName }.ifEmpty { "nothing" }} to show on." }
        report.say { "  walk to ground it has not generated yet. What exists catches up as it reloads if this bought worsening wounds; without them, existing ground keeps what it was made with." }
        return SUCCESS
    }

}
