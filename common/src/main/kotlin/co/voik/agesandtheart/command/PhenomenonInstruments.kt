package co.voik.agesandtheart.command

import co.voik.agesandtheart.content.PlasmaField
import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.phenomena.Happenings
import co.voik.agesandtheart.age.phenomena.MeteorDials
import co.voik.agesandtheart.age.phenomena.MeteorStorm
import co.voik.agesandtheart.age.phenomena.Meteors
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.phenomena.Sandfall
import co.voik.agesandtheart.age.phenomena.SandfallDials
import co.voik.agesandtheart.age.phenomena.Tempest
import co.voik.agesandtheart.age.aspect.WeatherConditions
import co.voik.agesandtheart.age.phenomena.AgeWeather
import co.voik.agesandtheart.age.phenomena.Blizzard
import co.voik.agesandtheart.age.phenomena.Deluge
import co.voik.agesandtheart.age.phenomena.Tide
import co.voik.agesandtheart.generation.Ages
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
import net.minecraft.world.level.ChunkPos
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
            .then(tideSubcommand())
            .then(plasmaSubcommand())
    }

    /**
     * `/age plasma pass` — one whole pass of a plasma sea's heat over the chunks around where it is run, as
     * a tick does round a player: how a headless check drives a field nobody stands in.
     */
    private fun plasmaSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("plasma").then(
            Commands.literal(PASS).executes { context ->
                val source = context.source
                val at = ChunkPos.containing(BlockPos.containing(source.position))
                PlasmaField.passAround(source.level, at, PASS_RADIUS)
                source.sendSuccess({ Component.literal("The plasma's heat passed over ${PASS_RADIUS * 2 + 1}² chunks") }, false)
                SUCCESS
            },
        )

    /** `/age tide moon` — the moons' tide again, after a stage was pinned. */
    private const val FOLLOW_THE_MOONS = "moon"

    private const val PASS = "pass"

    /** How far `/age tide pass` reaches, in chunks — about what a player sees at a short view distance. */
    private const val PASS_RADIUS = 3

    /**
     * `/age tide <low|mid|high|moon>` — a tide running in the Age you stand in, pinned at one stage or left
     * to the moons.
     *
     * **The whole of the tide, not its level alone**: it runs the same flood and ebb a written one does, so
     * pinning it high floods the band and pinning it low drains it, which is how a paper tree's roots are
     * walked without waiting out a moon. `/age weather` asked for anything else ends it, as it ends a deluge.
     */
    private fun tideSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("tide").apply {
            for (stage in Tide.Stage.entries) {
                then(Commands.literal(stage.name.lowercase()).executes { context -> runTide(context, stage) })
            }
            then(Commands.literal(FOLLOW_THE_MOONS).executes { context -> runTide(context, pinned = null) })
            then(Commands.literal(PASS).executes(::runTidePass))
        }

    /**
     * `/age tide pass` — one whole pass of the tide over the chunks around where it is run, as a tick does
     * around a player: how a headless check drives a tide nobody stands in.
     */
    private fun runTidePass(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val recipe = Ages.recipeOf(level)
        val pulls = recipe?.composition?.let { Tide.pullsIn(it, recipe.seed) }.orEmpty()
        val at = ChunkPos.containing(BlockPos.containing(source.position))
        Tide.passAround(level, pulls, at, PASS_RADIUS)
        source.sendSuccess({ Component.literal("The tide passed over ${PASS_RADIUS * 2 + 1}² chunks") }, false)
        return SUCCESS
    }

    private fun runTide(context: CommandContext<CommandSourceStack>, pinned: Tide.Stage?): Int {
        val source = context.source
        val level = source.level
        val mid = Tide.midIn(level) ?: return FAILURE.also {
            source.sendFailure(Component.literal("This Age has no sea for a tide to move"))
        }
        Tide.force(level, pinned)
        val now = Tide.stageIn(level)
        val said = if (now == null) "no moon to raise it" else "at ${now.name.lowercase()}, ${mid + now.offset}"
        source.sendSuccess({ Component.literal("A tide runs here, mid at $mid, $said") }, true)
        return SUCCESS
    }

    private const val DISTANCE_ARGUMENT = "distance"

    /** Far enough that `/age strike` does not land on the caster, near enough to watch it land. */
    private const val DEFAULT_STRIKE_DISTANCE = 12

    private const val MAX_STRIKE_DISTANCE = 128

    /** Far enough out to watch one come, and inside what is loaded at an ordinary view distance. */
    private const val DEFAULT_SANDFALL_DISTANCE = 64

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

    /** `/age sandfall <distance> <seconds> <size> [<depth>]` — how far into each dial, as a percent. */
    private const val SIZE_ARGUMENT = "size"
    private const val DEPTH_ARGUMENT = "depth"

    /** `/age meteors <distance> <seconds> <power>` — how far into the storms' `power` dial, as a percent. */
    private const val POWER_ARGUMENT = "power"

    private const val NONE_OF_A_DIAL = 0

    private const val ALL_OF_A_DIAL = 100

    private const val MOST_SANDFALL_SECONDS = 3600

    private const val TICKS_PER_SECOND = 20

    /**
     * `/age weather blizzard <visibility> [<frostbite>]` — how thick it blows and how fast it bites, where one
     * is ordinary and three is fully bought. Frostbite left out follows visibility.
     */
    private const val VISIBILITY_ARGUMENT = "visibility"
    private const val FROSTBITE_ARGUMENT = "frostbite"

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
                    .executes { context -> runWeather(context, name, wants, forced = null) }
                // **Only the blizzard takes a strength**, because it is the only phenomenon whose weather
                // and whose fierceness are different dials — everything else either wants a condition or
                // does not. One is ordinary and three is everything instability can buy.
                if (name == Phenomenon.BLIZZARD.key) {
                    fun hardness() = DoubleArgumentType.doubleArg(Rung.ORDINARY, Blizzard.HARDEST_FORCED)
                    branch.then(
                        Commands.argument(VISIBILITY_ARGUMENT, hardness())
                            .executes { context ->
                                val visibility = DoubleArgumentType.getDouble(context, VISIBILITY_ARGUMENT)
                                runWeather(context, name, wants, Blizzard.Forced(visibility, visibility))
                            }
                            .then(
                                Commands.argument(FROSTBITE_ARGUMENT, hardness()).executes { context ->
                                    val forced = Blizzard.Forced(
                                        visibility = DoubleArgumentType.getDouble(context, VISIBILITY_ARGUMENT),
                                        frostbite = DoubleArgumentType.getDouble(context, FROSTBITE_ARGUMENT),
                                    )
                                    runWeather(context, name, wants, forced)
                                },
                            ),
                    )
                }
                then(branch)
            }
        }

    private fun runWeather(
        context: CommandContext<CommandSourceStack>,
        name: String,
        wants: WeatherConditions,
        forced: Blizzard.Forced?,
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
            if (forced == null) Blizzard.release(level) else Blizzard.force(level, forced.visibility, forced.frostbite)
        }
        // A deluge asked for pools its rain whatever the Age was written with; any other weather ends that.
        if (name == Phenomenon.DELUGE.key) Deluge.force(level) else Deluge.release(level)
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
     * `/age meteors [<distance>] [<seconds>] [<power>] [look]` — gather a storm ahead of you, now.
     *
     * Each of a storm's dials has its lever here: `seconds` is how long it lasts and `power` how hard its
     * bodies land, and either left out is what this Age's own instability bought. How often one gathers has
     * none, a storm raised now being the answer to that.
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
                    if (given) IntegerArgumentType.getInteger(context, POWER_ARGUMENT) else null,
                    if (slanted) IntegerArgumentType.getInteger(context, DEGREES_ARGUMENT) else null,
                    look,
                    ignoreLure,
                )
            }

        val asked = Commands.argument(POWER_ARGUMENT, IntegerArgumentType.integer(0, ALL_OF_A_DIAL))
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
        powerPercent: Int?,
        degrees: Int?,
        look: Boolean,
        ignoreLure: Boolean,
    ): Int {
        val source = context.source
        val level = source.level
        // **A bare `/age meteors` imitates what this Age would raise on its own** (Jonah), rather than
        // inventing a storm the game does not contain: the rung its book claimed and the dials its
        // instability actually bought. Anything passed is an override on top of that.
        val density = Happenings.claimFor(level, Phenomenon.METEORS)?.density ?: Rung.ORDINARY
        val bought = MeteorDials.of(Happenings.spendingIn(level))
        // toDouble FIRST: ALL_OF_A_DIAL is an Int, so dividing without it makes every power under a hundred nought.
        val dials = powerPercent?.let { bought.copy(power = it.toDouble() / ALL_OF_A_DIAL) } ?: bought
        val facing = Vec3.directionFromRotation(source.rotation)
        val ahead = source.position.add(facing.scale(distance?.toDouble() ?: Meteors.gathersAway(level)))
        val spot = BlockPos.containing(ahead.x, source.position.y, ahead.z)
        // Lures are consulted exactly as written weather consults them, unless asked not to — the whole
        // point of this command is that it does what a storm does.
        val drawn = if (ignoreLure) null else Meteors.drawnNear(level, source.position)
        val slant = degrees?.let { Math.toRadians(it.toDouble()) }
        val storm = Meteors.raise(level, spot, density, dials, drawn, slant, seconds?.times(TICKS_PER_SECOND))
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
            "A storm gathers, $drawnIn, ${(dials.power * ALL_OF_A_DIAL).toInt()}% power, " +
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
            .executes { context -> runSandfall(context, DEFAULT_SANDFALL_DISTANCE, null, NONE_OF_A_DIAL) }
            .then(
                Commands.argument(DISTANCE_ARGUMENT, IntegerArgumentType.integer(0, MAX_SANDFALL_DISTANCE))
                    .executes { context ->
                        runSandfall(
                            context,
                            IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT),
                            null,
                            NONE_OF_A_DIAL,
                        )
                    }
                    .then(
                        Commands.argument(SECONDS_ARGUMENT, IntegerArgumentType.integer(1, MOST_SANDFALL_SECONDS))
                            .executes { context ->
                                runSandfall(
                                    context,
                                    IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT),
                                    IntegerArgumentType.getInteger(context, SECONDS_ARGUMENT),
                                    NONE_OF_A_DIAL,
                                )
                            }
                            .then(
                                Commands.argument(SIZE_ARGUMENT, IntegerArgumentType.integer(0, ALL_OF_A_DIAL))
                                    .executes { context ->
                                        runSandfall(
                                            context,
                                            IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT),
                                            IntegerArgumentType.getInteger(context, SECONDS_ARGUMENT),
                                            IntegerArgumentType.getInteger(context, SIZE_ARGUMENT),
                                        )
                                    }
                                    .then(
                                        Commands.argument(DEPTH_ARGUMENT, IntegerArgumentType.integer(0, ALL_OF_A_DIAL))
                                            .executes { context ->
                                                runSandfall(
                                                    context,
                                                    IntegerArgumentType.getInteger(context, DISTANCE_ARGUMENT),
                                                    IntegerArgumentType.getInteger(context, SECONDS_ARGUMENT),
                                                    IntegerArgumentType.getInteger(context, SIZE_ARGUMENT),
                                                    IntegerArgumentType.getInteger(context, DEPTH_ARGUMENT),
                                                )
                                            },
                                    ),
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
        sizePercent: Int,
        depthPercent: Int = NONE_OF_A_DIAL,
    ): Int {
        val source = context.source
        val level = source.level
        // toDouble FIRST: ALL_OF_A_DIAL is an Int, so dividing without it makes every percent under a hundred nought.
        val dials = SandfallDials.NONE.copy(
            size = sizePercent.toDouble() / ALL_OF_A_DIAL,
            depth = depthPercent.toDouble() / ALL_OF_A_DIAL,
        )
        val facing = Vec3.directionFromRotation(source.rotation)
        val at = source.position.add(facing.scale(distance.toDouble()))
        val column = Sandfall.raise(
            level = level,
            atX = at.x,
            atZ = at.z,
            // Turned around to walk back at you, so a column stood up ahead is one you then have to answer.
            headingDegrees = source.rotation.y + HALF_COMPASS,
            dials = dials,
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
