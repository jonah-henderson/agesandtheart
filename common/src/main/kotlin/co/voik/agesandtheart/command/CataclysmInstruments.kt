package co.voik.agesandtheart.command

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.age.cataclysm.Cataclysms
import co.voik.agesandtheart.age.cataclysm.Doom
import co.voik.agesandtheart.age.cataclysm.ImpactPhase
import co.voik.agesandtheart.age.cataclysm.ImpactTimeline
import co.voik.agesandtheart.age.cataclysm.Scorch
import co.voik.agesandtheart.generation.Ages
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos

/**
 * `/age cataclysm` — an Age's end set going by hand, in the Age stood in, until a restart (design §5.3).
 *
 * - `/age cataclysm` says what end is coming and where it has got to.
 * - `/age cataclysm asteroid` starts an asteroid's approach now; `/age cataclysm asteroid <phase>` starts it so
 *   that phase begins now, to look at one stage without waiting through the others.
 * - `/age cataclysm clear` calls it off, the Age's links included.
 * - `/age cataclysm scorch` scorches the chunks around you as the firestorm would.
 */
object CataclysmInstruments {

    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(
            Commands.literal(CATACLYSM_LITERAL)
                .executes(asking(::runReport))
                .then(
                    Commands.literal(ASTEROID_LITERAL)
                        .executes(asking { context, report -> runAsteroid(context, report, ImpactPhase.APPROACH) })
                        .then(
                            Commands.argument(PHASE_ARGUMENT, StringArgumentType.word())
                                .suggests { _, builder ->
                                    SharedSuggestionProvider.suggest(ImpactPhase.entries.map { it.key }, builder)
                                }
                                .executes(asking(::runAsteroidFrom)),
                        ),
                )
                .then(Commands.literal(CLEAR_LITERAL).executes(asking(::runClear)))
                .then(Commands.literal(SCORCH_LITERAL).executes(asking(::runScorch))),
        )
    }

    private fun asking(run: (CommandContext<CommandSourceStack>, Report) -> Int) =
        { context: CommandContext<CommandSourceStack> -> run(context, Report.prose(context.source)) }

    private fun runReport(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val (id, recipe) = theAgeStoodIn(context, report) ?: return FAILURE
        val doom = Cataclysms.of(recipe)
        if (doom == null) {
            report.say { "${id.path} has no end coming" }
            report.finish()
            return SUCCESS
        }
        sayWhereItHasGot(context.source.level, doom, report)
        report.finish()
        return SUCCESS
    }

    private fun runAsteroidFrom(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val key = StringArgumentType.getString(context, PHASE_ARGUMENT)
        val phase = ImpactPhase.entries.firstOrNull { it.key == key }
            ?: return report.fail("No phase '$key' — one of ${ImpactPhase.entries.joinToString { it.key }}")
        return runAsteroid(context, report, phase)
    }

    private fun runAsteroid(context: CommandContext<CommandSourceStack>, report: Report, from: ImpactPhase): Int {
        val (id, recipe) = theAgeStoodIn(context, report) ?: return FAILURE
        val level = context.source.level
        val timeline = ImpactTimeline.PROTOTYPE
        val startsAt = level.gameTime - timeline.startOf(from)
        val doom = Doom.asteroid(recipe.seed, startsAt, timeline)
        retellTheEnd(level, recipe, doom)
        report.say { "An asteroid is coming to ${id.path}, from the bearing %.0f°, until a restart".format(doom.bearingDegrees) }
        sayWhereItHasGot(level, doom, report)
        report.finish()
        return SUCCESS
    }

    private fun runClear(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val (id, recipe) = theAgeStoodIn(context, report) ?: return FAILURE
        retellTheEnd(context.source.level, recipe, doom = null)
        report.say { "${id.path} has no end coming any more, and its links hold" }
        report.finish()
        return SUCCESS
    }

    /** Scorches the loaded chunks around the command's position as the firestorm would, to look at without waiting. */
    private fun runScorch(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val (id, _) = theAgeStoodIn(context, report) ?: return FAILURE
        val level = context.source.level
        val here = BlockPos.containing(context.source.position)
        val centre = ChunkPos(SectionPos.blockToSectionCoord(here.x), SectionPos.blockToSectionCoord(here.z))
        val around = (centre.x - SCORCHED_AROUND..centre.x + SCORCHED_AROUND).flatMap { x ->
            (centre.z - SCORCHED_AROUND..centre.z + SCORCHED_AROUND).mapNotNull { z -> level.chunkSource.getChunkNow(x, z) }
        }
        around.forEach { Scorch.scorch(level, it) }
        report.fact("scorched", around.size) { "Scorched ${around.size} chunks of ${id.path}" }
        report.finish()
        return SUCCESS
    }

    private fun retellTheEnd(level: ServerLevel, recipe: AgeRecipe, doom: Doom?) {
        Ages.retell(level.server, level.dimension().identifier(), recipe.copy(forcedCataclysm = doom))
        Cataclysms.tellTheClients(level, doom)
    }

    private fun sayWhereItHasGot(level: ServerLevel, doom: Doom, report: Report) {
        val now = level.gameTime
        val phase = doom.phaseAt(now)
        report.fact("phase", phase?.key) { "  now: ${phase?.key ?: "not begun"}" }
        for (each in ImpactPhase.entries) {
            val inSeconds = (doom.startOf(each) - now) / TICKS_PER_SECOND
            report.say { "  ${each.key} ${if (inSeconds > 0) "in ${inSeconds}s" else "began ${-inSeconds}s ago"}" }
        }
        val ended = doom.hasStruckBy(now)
        report.fact("ended", ended) { if (ended) "  its links fail" else "  its links hold until the impact" }
    }

    private const val CATACLYSM_LITERAL = "cataclysm"
    private const val ASTEROID_LITERAL = "asteroid"
    private const val CLEAR_LITERAL = "clear"
    private const val SCORCH_LITERAL = "scorch"
    private const val SCORCHED_AROUND = 1
    private const val PHASE_ARGUMENT = "phase"
    private const val TICKS_PER_SECOND = ImpactTimeline.TICKS_PER_SECOND
}
