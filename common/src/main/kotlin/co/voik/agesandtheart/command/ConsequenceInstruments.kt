package co.voik.agesandtheart.command

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.compat.center
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Ladder
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
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel

/** Which Age a command asks about — or null, having already said why there is none. */
private typealias AgeAskedAbout = (CommandContext<CommandSourceStack>, Report) -> NamedAge?

internal object ConsequenceInstruments {

    /**
     * `/age instability [<name>] …` — everything about how an Age is coming apart: what its instability drew
     * and what shows now, any manifestation forced by hand, its index and its age, its wounds and its danger.
     * Without a name it is the Age being stood in.
     */
    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(instabilitySubcommand())
    }

    private const val INSTABILITY_LITERAL = "instability"

    /** `… age <days>` — the Age made older by hand. */
    private const val AGED_LITERAL = "age"

    /** `… index <n>` — the index, set by hand rather than earned. */
    private const val INDEX_LITERAL = "index"

    /** `… danger` — what the recipe is worth, and the ground underfoot where it is the Age stood in. */
    private const val DANGER_LITERAL = "danger"

    /** `… wounds` — how holed the Age is by now, and this chunk's wanted against held where it is stood in. */
    private const val WOUNDS_LITERAL = "wounds"

    /** `… force <manifestation> <steps>` — a manifestation set by hand, until a restart. */
    private const val FORCE_LITERAL = "force"

    /** `… gravity <pull>` — the Age's pull set outright, as a share of vanilla's, until a restart. */
    private const val GRAVITY_LITERAL = "gravity"

    private const val PULL_ARGUMENT = "pull"

    /** A tenth of vanilla's pull to four times it — past what any step buys either way. */
    private const val LIGHTEST_PULL = 0.1
    private const val HEAVIEST_PULL = 4.0

    /** `… clear` — every forced manifestation and pull back to what was drawn. */
    private const val CLEAR_LITERAL = "clear"

    /** How far `… wounds` looks — a good deal further than a wound corrupts, so it can say "none". */
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

    private fun instabilitySubcommand(): LiteralArgumentBuilder<CommandSourceStack> {
        val prose: ReportFor = { context -> Report.prose(context.source) }
        val structured: ReportFor = { context -> Report.structured(context.source) }
        return Commands.literal(INSTABILITY_LITERAL)
            .askingAbout(::theAgeStoodIn, prose)
            .then(ageNameArgument().askingAbout(::theAgeNamed, prose))
            .then(
                Commands.literal(Report.STRUCTURED_LITERAL)
                    .askingAbout(::theAgeStoodIn, structured)
                    .then(ageNameArgument().askingAbout(::theAgeNamed, structured)),
            )
    }

    private fun theAgeStoodIn(context: CommandContext<CommandSourceStack>, report: Report): NamedAge? {
        val level = context.source.level
        val recipe = Ages.recipeOf(level)
        if (recipe == null) {
            report.fail("Not standing in an Age — name one, or /age tp <name> first")
            return null
        }
        return NamedAge(level.dimension().identifier(), recipe)
    }

    private fun theAgeNamed(context: CommandContext<CommandSourceStack>, report: Report): NamedAge? =
        namedAge(context.source, StringArgumentType.getString(context, NAME_ARGUMENT), report)

    /**
     * Everything that can be asked of one Age, hung under whatever names it. Built once per way of naming
     * and once per way of answering, because a Brigadier node cannot be hung in two places.
     */
    private fun <T : ArgumentBuilder<CommandSourceStack, T>> T.askingAbout(
        asked: AgeAskedAbout,
        reportFor: ReportFor,
    ): T {
        fun answering(run: (CommandContext<CommandSourceStack>, NamedAge, Report) -> Int) =
            { context: CommandContext<CommandSourceStack> ->
                val report = reportFor(context)
                asked(context, report)?.let { age -> run(context, age, report) } ?: FAILURE
            }
        return executes(answering(::runReport))
            .then(Commands.literal(WOUNDS_LITERAL).executes(answering(::runWounds)))
            .then(Commands.literal(DANGER_LITERAL).executes(answering(::runDanger)))
            .then(
                Commands.literal(FORCE_LITERAL).then(
                    Commands.argument(MANIFESTATION_ARGUMENT, StringArgumentType.word())
                        .suggests { _, builder ->
                            SharedSuggestionProvider.suggest(Manifestation.entries.map { it.key }, builder)
                        }
                        .then(
                            Commands.argument(STEPS_ARGUMENT, IntegerArgumentType.integer(0, MOST_STEPS))
                                .executes(answering(::runForce)),
                        ),
                ),
            )
            .then(Commands.literal(CLEAR_LITERAL).executes(answering(::runClear)))
            .then(
                Commands.literal(GRAVITY_LITERAL).then(
                    Commands.argument(PULL_ARGUMENT, DoubleArgumentType.doubleArg(LIGHTEST_PULL, HEAVIEST_PULL))
                        .executes(answering(::runGravity)),
                ),
            )
            .then(
                Commands.literal(INDEX_LITERAL).then(
                    Commands.argument(INDEX_ARGUMENT, IntegerArgumentType.integer(0, MOST_INSTABILITY))
                        .executes(answering(::runSetIndex)),
                ),
            )
            .then(
                Commands.literal(AGED_LITERAL).then(
                    Commands.argument(DAYS_ARGUMENT, IntegerArgumentType.integer(0, MOST_DAYS))
                        .executes(answering(::runBackdate)),
                ),
            )
    }

    /** What an Age's instability drew, what is forced over that, and what each manifestation is doing now. */
    private fun runReport(context: CommandContext<CommandSourceStack>, age: NamedAge, report: Report): Int {
        val (id, recipe) = age
        val server = context.source.server
        val drawn = Spending.of(server, recipe.copy(forced = emptyMap(), forcedPull = null))
        val spending = Spending.of(server, recipe)
        val setting = SpendingSetting.of(server, recipe.composition, recipe.template, recipe.seed)
        val days = recipe.ageAt(server) / Tearing.TICKS_PER_DAY

        report.say { "${id.path}: instability ${recipe.instability.index}, $days days old" }
        report.fact("days", days) { "  days: $days" }
        report.fact("drawn", drawn.toString()) { "  drew: $drawn" }
        report.fact("forced", recipe.forced.mapKeys { it.key.key }) {
            if (recipe.forced.isEmpty()) "  nothing forced"
            else "  forced: " + recipe.forced.entries.joinToString { "${it.key.key}×${it.value}" } + " (until a restart)"
        }
        recipe.forcedPull?.let { pull ->
            report.fact("forcedPull", pull) { "  gravity set to %.0f%% of vanilla's (until a restart)".format(pull * PER_CENT) }
        }
        report.fact("has", setting.has.map { it.serializedName }) {
            "  has ${setting.has.joinToString { it.serializedName }.ifEmpty { "nothing" }} to show on"
        }
        val level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id))
        val showing = Manifestation.entries.filter { spending.bought(it) > 0 }
        report.fact("showing", showing.map { it.key }) { if (showing.isEmpty()) "  shows nothing" else "  shows:" }
        for (manifestation in showing) {
            val now = if (level == null) "not open, so nothing is happening" else nowIn(level, recipe, spending, manifestation)
            report.say { "    ${manifestation.key} ${stepsOf(spending, manifestation)}: $now" }
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
            Manifestation.TORN_SEAMS, Manifestation.FOREIGN_GROUND, Manifestation.OVERGROWN_GROUND, Manifestation.STAMPED_SHAPES, Manifestation.LEAFLESS_TREES,
            Manifestation.ABANDONED_VILLAGES -> "in ground generated since it was bought"
            Manifestation.WOUNDS, Manifestation.WORSENING_WOUNDS, Manifestation.COLLAPSE ->
                "see /age $INSTABILITY_LITERAL $WOUNDS_LITERAL"
            Manifestation.SKY_DRIFT, Manifestation.DAY_STARS, Manifestation.LOW_CLOUDS, Manifestation.UNCANNY_MOTES,
            Manifestation.MONOCHROME, Manifestation.SKY_TEARS -> "always, for anyone in it"
            Manifestation.STILL_CREATURES -> "on every creature as it loads"
            Manifestation.STRANGE_GRAVITY ->
                "everything falls at %.0f%% of vanilla's pull".format(StrangeGravity.pullOf(recipe, spending) * PER_CENT)
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
    private fun runForce(context: CommandContext<CommandSourceStack>, age: NamedAge, report: Report): Int {
        val (id, recipe) = age
        val server = context.source.server
        val key = StringArgumentType.getString(context, MANIFESTATION_ARGUMENT)
        val steps = IntegerArgumentType.getInteger(context, STEPS_ARGUMENT)
        val manifestation = Manifestation.entries.firstOrNull { it.key == key }
            ?: return report.fail("No manifestation '$key' — one of ${Manifestation.entries.joinToString { it.key }}")
        val forced = recipe.copy(forced = recipe.forced + (manifestation to steps))
        retell(server, id, forced)

        val spending = Spending.of(server, forced)
        val bought = stepsOf(spending, manifestation)
        report.fact("bought", spending.bought(manifestation)) {
            if (steps <= 0) "${id.path} no longer shows ${manifestation.key}, until a restart"
            else "${id.path} now shows ${manifestation.key} $bought whatever it drew, until a restart"
        }
        val needs = (Ladder.list(server)[manifestation] ?: Ladder.ordinaryFor(manifestation)).needs
        val setting = SpendingSetting.of(server, forced.composition, forced.template, forced.seed)
        val missing = needs - setting.has
        if (steps > 0 && missing.isNotEmpty()) {
            report.say { "  but it has no ${missing.joinToString { it.serializedName }} to show it on, so expect nothing" }
        }
        sayWhenNotStoodIn(context, id, report)
        report.finish()
        return SUCCESS
    }

    /** Set the Age's pull outright, whatever its instability bought, until the server restarts. */
    private fun runGravity(context: CommandContext<CommandSourceStack>, age: NamedAge, report: Report): Int {
        val (id, recipe) = age
        val pull = DoubleArgumentType.getDouble(context, PULL_ARGUMENT)
        retell(context.source.server, id, recipe.copy(forcedPull = pull))
        report.fact("pull", pull) {
            "${id.path} now pulls at %.0f%% of vanilla's, until a restart or clear".format(pull * PER_CENT)
        }
        sayWhenNotStoodIn(context, id, report)
        report.finish()
        return SUCCESS
    }

    private fun runClear(context: CommandContext<CommandSourceStack>, age: NamedAge, report: Report): Int {
        val (id, recipe) = age
        val cleared = recipe.copy(forced = emptyMap(), forcedPull = null)
        retell(context.source.server, id, cleared)
        report.fact("drawn", Spending.of(context.source.server, cleared).toString()) {
            "${id.path} shows what it drew again: ${Spending.of(context.source.server, cleared)}"
        }
        report.finish()
        return SUCCESS
    }

    /** A reminder that a named Age is not the one around you, which is easy to forget while looking for a change. */
    private fun sayWhenNotStoodIn(context: CommandContext<CommandSourceStack>, id: Identifier, report: Report) {
        val standingIn = context.source.level.dimension().identifier()
        if (standingIn != id) report.say { "  (you are in ${standingIn.path}, not ${id.path})" }
    }

    /**
     * How holed an Age is by now — what the book tore, how fast it worsens, and where the floor gives way —
     * and, where it is the Age stood in, what this chunk wants against what it holds.
     */
    private fun runWounds(context: CommandContext<CommandSourceStack>, age: NamedAge, report: Report): Int {
        val (id, recipe) = age
        val source = context.source
        val server = source.server
        val spending = Spending.of(server, recipe)
        val days = recipe.ageAt(server) / Tearing.TICKS_PER_DAY
        val written = Tearing.writtenDensityAt(spending.bought(Manifestation.WOUNDS))
        val perDay = Tearing.woundsPerDayAt(spending.bought(Manifestation.WORSENING_WOUNDS))
        val density = Tearing.densityAt(written, perDay, days)
        val tears = Collapse.tearsPerCellAt(spending.bought(Manifestation.COLLAPSE))

        report.say { "${id.path}, $days days old:" }
        report.fact("woundsWritten", written) { "  wounds the book tore: %.3f per chunk".format(written) }
        report.fact("woundsPerDay", perDay) { "  worsening: %.3f more per chunk each day".format(perDay) }
        report.fact("density", density) { "  wounds now: %.3f per chunk".format(density) }
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

        val level = source.level
        if (level.dimension().identifier() == id) {
            reportChunk(level, BlockPos.containing(source.position), density, perDay, report)
        } else {
            report.say { "  stand in it for what this chunk holds" }
        }
        report.finish()
        return SUCCESS
    }

    private fun reportChunk(level: ServerLevel, at: BlockPos, density: Double, perDay: Double, report: Report) {
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

        report.say { "  at ${at.x}, ${at.y}, ${at.z}:" }
        report.fact("wantedHere", wanted) { "    this chunk wants $wanted" }
        report.fact("holdsHere", holds) { "    this chunk holds $holds" }
        report.fact("within", within) { "    $within within ${LOOKS_FOR_WOUNDS_WITHIN.toInt()} blocks" }
        val found = nearest
        if (found == null) {
            report.say { "    no wound in reach" }
        } else {
            report.fact("nearestAway", nearestAway) {
                "    nearest at ${found.x}, ${found.y}, ${found.z} — %.1f blocks".format(nearestAway)
            }
        }
        // The three readings a walk needs told apart, said outright rather than left to arithmetic.
        report.say {
            when {
                density <= Tearing.NONE -> "    → this Age bought no wounds at all"
                holds >= wanted -> "    → this chunk is up to date"
                perDay <= Tearing.NONE -> "    → behind, and nothing will fix it: it does not worsen, so nothing catches up"
                else -> "    → behind by ${wanted - holds}; leave until it unloads and return, or stand here for the creep"
            }
        }
    }

    /**
     * What a written Age is worth to §7.7, contributor by contributor, and — where it is the Age stood in —
     * what the ground underfoot is worth in vanilla's terms.
     *
     * **The parts as well as the total**, because one number cannot be calibrated: the question a tuning
     * session asks is *why* an Age scored what it did, and the answer is always which contributor carried
     * it. Reading the four beside the score is what turns `art/danger.json` from a guess into a dial.
     */
    private fun runDanger(context: CommandContext<CommandSourceStack>, age: NamedAge, report: Report): Int {
        val (id, recipe) = age
        val source = context.source
        val danger = Danger.of(source.server, recipe)

        report.say { "${id.path}:" }
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
        if (danger.terminal > 0.0) {
            report.fact("terminal", danger.terminal) { "  and it will not last: collapse reach %.2f".format(danger.terminal) }
        }
        // **The other half of what a recipe pays out** (design §7.7): the desk's survey answers it before a
        // book is written, and this is where it is asked of an Age that already exists.
        recipe.composition?.let { composition ->
            val grown = EarlyGameRareMaterials.grownIn(composition, recipe.seed, Spending.of(source.server, recipe))
            report.fact("grows", grown.map { it.name.lowercase() }) {
                if (grown.isEmpty()) "  grows none of the early materials"
                else "  grows " + grown.joinToString(", ") { it.name.lowercase() }
            }
        }

        val level = source.level
        if (level.dimension().identifier() == id) reportGround(level, BlockPos.containing(source.position), report)
        report.finish()
        return SUCCESS
    }

    private fun reportGround(level: ServerLevel, at: BlockPos, report: Report) {
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

        report.say { "  the ground at ${at.x}, ${at.y}, ${at.z}:" }
        report.fact("difficulty", level.difficulty.serializedName) { "    world difficulty: ${level.difficulty.serializedName}" }
        report.fact("corruption", corruption) { "    corruption here: %.3f".format(corruption) }
        report.fact("inhabited", level.getChunk(at).inhabitedTime) {
            "    chunk really lived in for ${level.getChunk(at).inhabitedTime} ticks"
        }
        report.fact("effectiveWithout", plain.effectiveDifficulty) {
            "    effective difficulty without the wound: %.2f".format(plain.effectiveDifficulty)
        }
        report.fact("effectiveWith", raised.effectiveDifficulty) {
            "    effective difficulty as the Age has it: %.2f".format(raised.effectiveDifficulty)
        }
        report.fact("specialMultiplier", raised.specialMultiplier) {
            "    special multiplier: %.2f".format(raised.specialMultiplier)
        }
        // Vanilla refuses to arm anything below 2.0, and on Easy the ceiling is 1.5 however lived-in the
        // ground is — so the register cannot show there at all.
        if (raised.specialMultiplier <= 0.0f) {
            report.say {
                "    → nothing will spawn armed here: vanilla arms nothing below effective 2.0" +
                    if (level.difficulty == Difficulty.EASY) ", and Easy tops out at 1.5" else ""
            }
        } else {
            val chance = ARMS_ANYTHING_AT_ALL * raised.specialMultiplier
            report.say { "    → about %.0f%% of what spawns here should arrive armed".format(chance * PER_CENT) }
        }
    }

    /**
     * Make an Age older than it is, by moving the tick it was written on backwards.
     *
     * Generation reads the clock per chunk, so unvisited ground comes out at the new age immediately.
     * Ground that already exists is brought up by `Worsening`'s catch-up as its chunks reload — so unloading
     * and returning is what makes it agree, and an Age that does not worsen has no catch-up and keeps
     * what it was made with.
     */
    private fun runBackdate(context: CommandContext<CommandSourceStack>, age: NamedAge, report: Report): Int {
        val (id, recipe) = age
        val days = IntegerArgumentType.getInteger(context, DAYS_ARGUMENT)
        val now = context.source.server.overworld().gameTime
        retell(context.source.server, id, recipe.copy(writtenAt = now - days * Tearing.TICKS_PER_DAY))
        report.fact("days", days) { "${id.path} now reads as $days days old." }
        report.say { "  ground it has not generated comes out at the new age; ground that exists catches up as it reloads, if the Age worsens." }
        sayWhenNotStoodIn(context, id, report)
        report.finish()
        return SUCCESS
    }

    /**
     * Set an Age's instability outright, so the consequence registers can be tested without writing a book
     * that earns them. The index goes through the real ladders and the Age's own seed, so what it buys is
     * exactly what a book of that index would have bought in that Age.
     */
    private fun runSetIndex(context: CommandContext<CommandSourceStack>, age: NamedAge, report: Report): Int {
        val (id, recipe) = age
        val server = context.source.server
        val index = IntegerArgumentType.getInteger(context, INDEX_ARGUMENT)
        val forced = recipe.copy(instability = Instability.forced(index))
        retell(server, id, forced)
        val spending = Spending.of(server, forced)
        report.fact("buys", spending.toString()) { "${id.path} is now instability $index, which buys $spending." }
        val setting = SpendingSetting.of(server, forced.composition, forced.template, forced.seed)
        report.say { "  it has ${setting.has.joinToString { it.serializedName }.ifEmpty { "nothing" }} to show on." }
        report.say { "  walk to ground it has not generated yet. What exists catches up as it reloads if this bought worsening wounds; without them, existing ground keeps what it was made with." }
        sayWhenNotStoodIn(context, id, report)
        report.finish()
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
}
