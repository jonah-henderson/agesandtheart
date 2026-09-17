package co.voik.agesandtheart.command

import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.phenomena.Happenings
import co.voik.agesandtheart.age.phenomena.MeteorStorm
import co.voik.agesandtheart.age.phenomena.Meteors
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.phenomena.Sandfall
import co.voik.agesandtheart.age.phenomena.Tempest
import co.voik.agesandtheart.age.aspect.WeatherConditions
import co.voik.agesandtheart.age.phenomena.AgeWeather
import co.voik.agesandtheart.age.phenomena.Blizzard
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntitySpawnReason

internal object PhenomenonInstruments {

    /** Forcing what an Age does to itself, so a walk does not have to wait for it. */
    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(strikeSubcommand())
            .then(sandfallSubcommand())
            .then(meteorsSubcommand())
            .then(weatherSubcommand())
    }

    private const val DISTANCE_ARGUMENT = "distance"

    /** Far enough that `/age strike` does not land on the caster, near enough to watch it land. */
    private const val DEFAULT_STRIKE_DISTANCE = 12

    private const val MAX_STRIKE_DISTANCE = 128

    /** Far enough out to watch one come, and inside what is loaded at an ordinary view distance. */
    private const val DEFAULT_SANDFALL_DISTANCE = 64

    /** Far enough that the sky reads as somewhere else, near enough to walk to before it falls. */
    private const val DEFAULT_STORM_DISTANCE = 90

    private const val MAX_STORM_DISTANCE = 512

    private const val LOOK_LITERAL = "look"

    /** Raises one as if nothing were drawing it, for telling a lure's doing from a storm's own. */
    private const val IGNORE_LURE_LITERAL = "ignore_lure"

    /**
     * How steeply a storm may be asked to come in, in degrees off the horizontal.
     *
     * Wider than the ten to forty-five a storm draws for itself, deliberately: this is the instrument for
     * judging whether that range is the right one, and it cannot answer that from inside it.
     */
    private const val DEGREES_ARGUMENT = "degrees"

    private const val SHALLOWEST_SLANT = 1

    private const val STEEPEST_SLANT = 90

    /** Which body's light `look` turns you to, and how far along its approach that light is. */
    private const val FIRST_BODY = 0

    private const val JUST_SIGHTED = 0.0f

    private const val MAX_SANDFALL_DISTANCE = 256

    private const val SECONDS_ARGUMENT = "seconds"

    /** `/age sandfall <distance> <seconds> <fury>` — how far into what instability could buy, as a percent. */
    private const val FURY_ARGUMENT = "fury"

    private const val NO_FURY = 0

    private const val ALL_FURY = 100

    private const val MOST_SANDFALL_SECONDS = 3600

    private const val TICKS_PER_SECOND = 20

    /** `/age weather blizzard <intensity>` — how hard, where one is ordinary and three is fully bought. */
    private const val INTENSITY_ARGUMENT = "intensity"

    /**
     * `/age weather <clear|rain|thunder>` — set the weather of **the Age you are standing in**.
     *
     * Vanilla's `/weather` cannot reach one. An Age owns its own `WeatherData` so that its rain is its own
     * (`ServerLevelMixin`), and the command writes the *overworld's* — so standing in a burning Age and
     * asking for rain changed the weather somewhere else entirely, which is how this went unwalked.
     *
     * A debug affordance rather than a mechanic, and deliberately not a mixin on `WeatherCommand`: the
     * ability to make it rain on demand is worth exactly as much as the walk that needs it, and vanilla's
     * command is not wrong about the world it was aimed at.
     */
    private fun weatherSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("weather").apply {
            for ((name, wants) in AgeWeather.asked()) {
                val branch = Commands.literal(name)
                    .executes { context -> runWeather(context, name, wants, hardness = null) }
                // **Only the blizzard takes a strength**, because it is the only phenomenon whose weather
                // and whose fierceness are two different dials — everything else either wants a condition
                // or does not. One is ordinary and three is everything instability can buy.
                if (name == Phenomenon.BLIZZARD.key) {
                    branch.then(
                        Commands.argument(INTENSITY_ARGUMENT, DoubleArgumentType.doubleArg(Rung.ORDINARY, Blizzard.HARDEST_FORCED))
                            .executes { context ->
                                runWeather(
                                    context,
                                    name,
                                    wants,
                                    DoubleArgumentType.getDouble(context, INTENSITY_ARGUMENT),
                                )
                            },
                    )
                }
                then(branch)
            }
        }

    private fun runWeather(
        context: CommandContext<CommandSourceStack>,
        name: String,
        wants: WeatherConditions,
        hardness: Double?,
    ): Int {
        val source = context.source
        val level = source.level
        val own = AgeWeather.of(level)
        if (own == null) {
            source.sendFailure(Component.translatable("commands.agesandtheart.weather.not_an_age"))
            return 0
        }
        // Asking for a plain blizzard puts the Age back on its own fierceness, so the override is never
        // something a walk can leave switched on without meaning to.
        if (name == Phenomenon.BLIZZARD.key) {
            if (hardness == null) Blizzard.release(level) else Blizzard.force(level, hardness)
        }
        AgeWeather.set(level, own, wants)
        source.sendSuccess({ Component.translatable("commands.agesandtheart.weather.set", name) }, true)
        return 1
    }

    /**
     * `/age strike [<distance>]` — call a bolt down where you are looking, to see what a tempest does to
     * it without standing in the rain waiting.
     *
     * Aimed through [Tempest.callDown], so it is the *same* strike a tempest gets — including the rod.
     * The first version built a bolt at the position it was looking at and added it to the world, which
     * cratered correctly and could not be grounded by any amount of copper: vanilla moves a strike onto a
     * rod in the spawner, before a bolt exists, so a bolt made directly has already refused.
     */
    private fun strikeSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("strike")
            .executes { context -> runStrike(context, DEFAULT_STRIKE_DISTANCE) }
            .then(
                Commands.argument(DISTANCE_ARGUMENT, IntegerArgumentType.integer(0, MAX_STRIKE_DISTANCE))
                    .executes { context ->
                        runStrike(context, IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT))
                    },
            )

    /**
     * `/age meteors [<distance>] [<seconds>] [<fury>] [look]` — gather a storm ahead of you, now.
     *
     * **A storm is thirty seconds of sky before it is anything at all**, which is exactly what makes it
     * unwalkable without this: waiting for one to happen by itself, in the right Age, close enough to see
     * and facing the right way, is several minutes a look. Here it is one line.
     *
     * `look` turns you to face it, which sounds like a convenience and is closer to the point: the
     * telegraph is *directional*, so a debug command that leaves you hunting the sky for it cannot show
     * you the thing it was written to show.
     *
     * `degrees` forces the angle a storm would otherwise draw for itself, which is the only way to put its
     * shallow and steep ends beside each other rather than waiting for the draw to offer them.
     */
    private fun meteorsSubcommand(): LiteralArgumentBuilder<CommandSourceStack> {
        fun runner(given: Boolean, slanted: Boolean) =
            { context: CommandContext<CommandSourceStack>, look: Boolean, ignoreLure: Boolean ->
                runMeteors(
                    context,
                    if (given) IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT) else null,
                    if (given) IntegerArgumentType.getInteger(context, SECONDS_ARGUMENT) else null,
                    if (given) IntegerArgumentType.getInteger(context, FURY_ARGUMENT) else null,
                    if (slanted) IntegerArgumentType.getInteger(context, DEGREES_ARGUMENT) else null,
                    look,
                    ignoreLure,
                )
            }

        val asked = Commands.argument(FURY_ARGUMENT, IntegerArgumentType.integer(0, ALL_FURY.toInt()))
        flagged(asked, runner(given = true, slanted = false))
        val slanted = Commands.argument(
            DEGREES_ARGUMENT,
            IntegerArgumentType.integer(SHALLOWEST_SLANT, STEEPEST_SLANT),
        )
        flagged(slanted, runner(given = true, slanted = true))
        asked.then(slanted)

        val meteors = Commands.literal("meteors")
        flagged(meteors, runner(given = false, slanted = false))
        return meteors.then(
            Commands.argument(DISTANCE_ARGUMENT, IntegerArgumentType.integer(0, MAX_STORM_DISTANCE)).then(
                Commands.argument(SECONDS_ARGUMENT, IntegerArgumentType.integer(1, MOST_SANDFALL_SECONDS))
                    .then(asked),
            ),
        )
    }

    /**
     * Hangs `look` and `ignore_lure` off a node, in either order and in any combination.
     *
     * Written out rather than as two literals because a flag a player has to remember the position of is a
     * flag they will get wrong, and this command already carries four positional arguments.
     */
    private fun flagged(
        node: ArgumentBuilder<CommandSourceStack, *>,
        run: (CommandContext<CommandSourceStack>, Boolean, Boolean) -> Int,
        look: Boolean = false,
        ignoreLure: Boolean = false,
    ) {
        node.executes { run(it, look, ignoreLure) }
        if (!look) {
            val next = Commands.literal(LOOK_LITERAL)
            flagged(next, run, look = true, ignoreLure = ignoreLure)
            node.then(next)
        }
        if (!ignoreLure) {
            val next = Commands.literal(IGNORE_LURE_LITERAL)
            flagged(next, run, look = look, ignoreLure = true)
            node.then(next)
        }
    }

    /**
     * Stands a storm up [distance] blocks ahead and, if asked, turns the caller to face it.
     *
     * The height is the storm's own rather than the caller's: a storm hangs well above the ground it
     * falls on, and one gathered at eye level would drop its bodies from under your feet.
     */
    private fun runMeteors(
        context: CommandContext<CommandSourceStack>,
        distance: Int?,
        seconds: Int?,
        furyPercent: Int?,
        degrees: Int?,
        look: Boolean,
        ignoreLure: Boolean,
    ): Int {
        val source = context.source
        val level = source.level
        // **A bare `/age meteors` imitates what this Age would raise on its own** (Jonah), rather than
        // inventing a storm the game does not contain: the rung its book claimed and the fury its
        // instability actually bought. Anything passed is an override on top of that.
        val density = Happenings.claimFor(level, Phenomenon.METEORS)?.density ?: Rung.ORDINARY
        // toDouble FIRST: ALL_FURY is an Int, so dividing without it makes every fury under a hundred nought.
        val fury = furyPercent?.let { it.toDouble() / ALL_FURY } ?: Happenings.furyIn(level, Phenomenon.METEORS)
        val facing = Vec3.directionFromRotation(source.rotation)
        val ahead = source.position.add(facing.scale((distance ?: DEFAULT_STORM_DISTANCE).toDouble()))
        val spot = BlockPos.containing(ahead.x, source.position.y, ahead.z)
        // Lures are consulted exactly as written weather consults them, unless asked not to — the whole
        // point of this command is that it does what a storm does.
        val drawn = if (ignoreLure) null else Meteors.drawnNear(level, source.position)
        val slant = degrees?.let { Math.toRadians(it.toDouble()) }
        val storm = Meteors.raise(level, spot, density, fury, drawn, slant, seconds?.times(TICKS_PER_SECOND))
        // Turned to the *light*, which is thousands of blocks out along the storm's entry line and nowhere
        // near the storm itself. Facing the storm left a walk staring at empty sky (Jonah, walked).
        if (look) {
            source.player?.lookAt(EntityAnchorArgument.Anchor.EYES, storm.seenFrom(storm.flightOf(FIRST_BODY), JUST_SIGHTED))
        }
        // Says what it actually raised rather than what was asked for, because most of this now comes from
        // the Age: an operator has to be able to see whether a bare call read anything at all.
        Report.prose(source).say {
            val drawnIn = drawn?.let { "drawn in by ${it.blocks} aloft, ${storm.reach.toInt()} wide" }
                ?: "${storm.reach.toInt()} wide, nothing drawing it"
            "A storm gathers, $drawnIn, ${(fury * ALL_FURY).toInt()}% fierce, " +
                "${storm.bodies} bodies over ${storm.falling / TICKS_PER_SECOND}s. " +
                "It falls in ${MeteorStorm.APPROACHING / TICKS_PER_SECOND}s."
        }
        return SUCCESS
    }

    /**
     * `/age sandfall [<distance>]` — stands a column of sand up out in front of you, headed at you.
     *
     * The debug trigger, exactly as `/age strike` is one, and for the same reason: a sandfall arrives on a
     * timer measured in minutes, so nothing about it is observable in a walk without a way to ask for one.
     *
     * **Aimed rather than sited**, which is the difference from a real one — [Sandfall] chooses a bearing
     * and a spread so that a column usually passes near you and sometimes passes wide, where this puts one
     * exactly where you are looking and turns it around to come back. That is what you want of a debug
     * trigger and emphatically not what you want of the phenomenon.
     *
     * **The optional life is what makes the trail measurable**, because the widening ramp is a share of it:
     * an ordinary column spends its first minute or two opening, so anything measured inside that window is
     * measuring a column that is not yet the width it will be. A short one is at full width in seconds.
     */
    private fun sandfallSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("sandfall")
            .executes { context -> runSandfall(context, DEFAULT_SANDFALL_DISTANCE, null, NO_FURY) }
            .then(
                Commands.argument(DISTANCE_ARGUMENT, IntegerArgumentType.integer(0, MAX_SANDFALL_DISTANCE))
                    .executes { context ->
                        runSandfall(
                            context,
                            IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT),
                            null,
                            NO_FURY,
                        )
                    }
                    .then(
                        Commands.argument(SECONDS_ARGUMENT, IntegerArgumentType.integer(1, MOST_SANDFALL_SECONDS))
                            .executes { context ->
                                runSandfall(
                                    context,
                                    IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT),
                                    IntegerArgumentType.getInteger(context, SECONDS_ARGUMENT),
                                    NO_FURY,
                                )
                            }
                            .then(
                                Commands.argument(FURY_ARGUMENT, IntegerArgumentType.integer(0, ALL_FURY))
                                    .executes { context ->
                                        runSandfall(
                                            context,
                                            IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT),
                                            IntegerArgumentType.getInteger(context, SECONDS_ARGUMENT),
                                            IntegerArgumentType.getInteger(context, FURY_ARGUMENT),
                                        )
                                    },
                            ),
                    ),
            )

    /**
     * Strikes the ground [distance] blocks along the caster's line of sight.
     *
     * The target is the surface under that point rather than the point itself, because a strike is a
     * column: aiming into the air would put the bolt in the air. `MOTION_BLOCKING` is the heightmap
     * vanilla's own targeting uses.
     */
    private fun runStrike(context: CommandContext<CommandSourceStack>, distance: Int): Int {
        val source = context.source
        val level = source.level
        val aimed = source.position.add(Vec3.directionFromRotation(source.rotation).scale(distance.toDouble()))
        val struck = Tempest.callDown(level, BlockPos.containing(aimed), EntitySpawnReason.COMMAND)
        source.sendSuccess({ Component.literal("Struck ${struck.x} ${struck.y} ${struck.z}") }, true)
        return SUCCESS
    }

    private fun runSandfall(
        context: CommandContext<CommandSourceStack>,
        distance: Int,
        seconds: Int?,
        furyPercent: Int,
    ): Int {
        val source = context.source
        val level = source.level
        val facing = Vec3.directionFromRotation(source.rotation)
        val at = source.position.add(facing.scale(distance.toDouble()))
        val column = Sandfall.raise(
            level = level,
            atX = at.x,
            atZ = at.z,
            // Turned around to walk back at you, so a column stood up ahead is one you then have to answer.
            headingDegrees = source.rotation.y + HALF_COMPASS,
            fury = furyPercent.toDouble() / ALL_FURY,
            lifetime = seconds?.times(TICKS_PER_SECOND),
        )
        if (column == null) {
            // Two causes, and saying which is the difference between a one-line fix and an afternoon: the
            // ground can only be asked of a chunk that is already there, so the usual answer is to stand
            // closer or to wait for the world to catch up.
            val ground = SectionPos.blockToSectionCoord(Mth.floor(at.x)) to
                SectionPos.blockToSectionCoord(Mth.floor(at.z))
            source.sendFailure(
                Component.literal("No column: chunk ${ground.first} ${ground.second} is not ticking entities"),
            )
            return FAILURE
        }
        source.sendSuccess(
            {
                Component.literal(
                    "A sandfall ${"%.0f".format(column.fullHalfWidth * 2)} across at " +
                        "${column.blockX} ${column.blockZ}, walking " +
                        "${compassPointFor(column.yRot + HALF_COMPASS)} for " +
                        "${column.lifetime / TICKS_PER_SECOND}s",
                )
            },
            true,
        )
        return SUCCESS
    }

}
