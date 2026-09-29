package co.voik.agesandtheart.command

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.age.ReportFor
import co.voik.agesandtheart.age.SkyParameters
import co.voik.agesandtheart.age.phenomena.Happenings
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.ephemeris.sky.Aurora
import co.voik.ephemeris.sky.AuroraGroundRule
import co.voik.ephemeris.sky.Blending
import co.voik.ephemeris.sky.CelestialBody
import co.voik.ephemeris.sky.Rainbow
import co.voik.ephemeris.sky.Daylight
import co.voik.ephemeris.sky.LevelDaylight
import co.voik.ephemeris.debug.LevelLookPreview
import co.voik.agesandtheart.generation.Skies
import net.minecraft.core.Direction
import co.voik.ephemeris.sky.LevelAppearance
import co.voik.ephemeris.sky.LevelClock
import co.voik.ephemeris.sky.LevelLook
import co.voik.ephemeris.sky.SkySpec
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel

internal object SkyInstruments {

    /** What is overhead, and why a curtain or a bow is or is not in it. */
    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(showingSubcommand())
            .then(skySubcommand())
    }

    /** What one `/age showing <what>` branch is called — "stop waiting for it". */
    private const val NOW_LITERAL = "now"

    /** The one subcommand every phenomenon that is *seen rather than done* answers under. See [Seen]. */
    private const val SHOWING_LITERAL = "showing"

    /** How far ahead the report looks for the next night a curtain comes. */
    private const val NIGHTS_LOOKED_AHEAD = 60L

    /** Half a chunk out, which is the ring the client samples the ground over. */
    private const val GROUND_RING_BLOCKS = 24

    /** Below this nothing is on the screen — the same floor `AuroraPainter` declines to draw at. */
    private const val NOTHING_SHOWING = 0.0f

    private const val EVERY_NIGHT = 1.0f

    /**
     * How far vanilla darkens its sky between noon and midnight — `skyDarken` is `15 - skyLightLevel`, and
     * the overworld runs 15 by day to 4 by night.
     */
    private const val FULLY_DARKENED = 11.0f

    /** What `/age sky`'s preview spec may name, and the prefix its parameters carry. */
    private const val SKY_ASPECT = "sky"

    /** What `/age sky` prefixes the Age's clock reading with, and what `SkyClockCheck` looks for. */
    const val CLOCK_LABEL = "clock"

    /** What `/age sky` prefixes the *mapped* hour with — the one every colour in the sky is keyed to. */
    const val LIT_AS_LABEL = "lit as"

    private const val VANILLA_DAY = 24000L

    /**
     * A landform to satisfy `AgeComposition.parse`, which refuses a composition without one. Read by
     * nothing.
     *
     * **Built from the aspect's own key** rather than spelled, because it was spelled and outlived the name
     * it spelled: renaming the aspect left this saying `terrain=hills` to a parser that had stopped knowing
     * the word, and every sky-preview check failed with it.
     */
    private val PREVIEW_SCAFFOLD = "${Aspect.TERRAIN.page}=${Terrain.HILLS.key}"

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

    /**
     * `/age showing aurora` — **why there is or is not a curtain in the sky right now**, and
     * `/age showing aurora now` to stop waiting for one.
     *
     * Written because an aurora is the first thing here that can be *correct and invisible*. Everything
     * about it is decided on the client from arithmetic, so nothing is logged, nothing is stored, and a
     * walk that sees no curtain cannot tell an Age that has none from a night it does not come from a
     * renderer that is broken. Three very different faults with one symptom, and no way to separate them
     * (Jonah, 2026-08-30, walked: "unable to see any auroras, even after waiting multiple days").
     *
     * **The server can answer all of it but the last.** Which nights a curtain comes is a pure function of
     * the spec, and the ground rule is a biome lookup — so this recomputes exactly what the client will,
     * from the same numbers, and says which factor is the one at nought. If it says a curtain should be
     * overhead and the sky is empty, the fault is in the drawing and nowhere else.
     */
    private fun showingSubcommand(): LiteralArgumentBuilder<CommandSourceStack> {
        fun branchesUnder(parent: LiteralArgumentBuilder<CommandSourceStack>, reportFor: ReportFor) =
            Seen.entries.fold(parent) { tree, seen ->
                tree.then(
                    Commands.literal(seen.key)
                        .executes { context -> seen.reportInto(context, reportFor(context)) }
                        .then(Commands.literal(NOW_LITERAL).executes { context -> seen.bringOn(context) }),
                )
            }
        val prose = branchesUnder(Commands.literal(SHOWING_LITERAL)) { context -> Report.prose(context.source) }
        val structured = branchesUnder(Commands.literal(Report.STRUCTURED_LITERAL)) { context ->
            Report.structured(context.source)
        }
        return prose.then(structured)
    }

    /**
     * The phenomena that are **seen rather than done** — the ones `Happenings.befall` has nothing to run
     * for, because everything they do happens on the client from arithmetic it does for itself.
     *
     * That is exactly the set that needs a command like this, and the reason there is one command rather
     * than one per phenomenon. Each of these can be *correct and invisible*, for several different reasons
     * at once, and none of them logs anything: a walk that sees nothing cannot tell an Age that has none
     * from a day it does not come from a renderer that is broken. So the server recomputes what the client
     * will and says which factor is the one at nought.
     *
     * **Adding a phenomenon adds a constant here and nothing else.** `/age` gains no subcommand, the tree
     * is built by folding over these, and the third one to be seen rather than done costs a `key` and two
     * methods (Jonah, 2026-08-30: "we're not cluttering the `/age` namespace with every new phenomenon").
     */
    private enum class Seen(val key: String) {
        AURORA("aurora") {
            override fun reportInto(context: CommandContext<CommandSourceStack>, report: Report) =
                runAurora(context, report)

            override fun bringOn(context: CommandContext<CommandSourceStack>) = runAuroraNow(context)
        },

        RAINBOW("rainbow") {
            override fun reportInto(context: CommandContext<CommandSourceStack>, report: Report) =
                runRainbow(context, report)

            override fun bringOn(context: CommandContext<CommandSourceStack>) = runRainbowNow(context)
        },
        ;

        /** Why it is or is not in the sky right now, factor by factor. */
        abstract fun reportInto(context: CommandContext<CommandSourceStack>, report: Report): Int

        /** The Age's own, with whatever makes it *wait* taken off — a preview, so there is nothing to undo. */
        abstract fun bringOn(context: CommandContext<CommandSourceStack>): Int
    }

    private fun runAurora(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val level = source.level
        val aurora = auroraOf(level)
        if (aurora == null) {
            report.say { "Nothing hangs a curtain in ${level.dimension().identifier()}." }
            report.fact("hasAurora", false) {
                "  Write one with `auroral`, or describe one — `green aurora`. `beautiful` sometimes does too."
            }
            report.finish()
            return SUCCESS
        }

        val at = BlockPos.containing(source.position)
        val night = level.defaultClockTime / VANILLA_DAY
        val tonight = aurora.strengthOn(night)
        val starlit = starlitnessIn(level)
        val ground = groundShareFor(aurora, level, at)
        val raining = level.getRainLevel(1.0f)
        val showing = tonight * starlit * (1.0f - raining) * aurora.glow * ground

        report.say { "The curtain over ${level.dimension().identifier()}, on night $night:" }
        report.fact("colours", aurora.colours.size) {
            "  burns ${aurora.colours.size} colour(s), crown first, over ${groundSaid(aurora)}"
        }
        report.fact("frequency", aurora.frequency) {
            "  comes on %.0f%% of nights, at glow %.2f, breadth %.2f, height %.2f"
                .format(aurora.frequency * PER_CENT, aurora.glow, aurora.breadth, aurora.height)
        }
        report.fact("tonight", tonight) {
            if (tonight > NOTHING_SHOWING) "  tonight is one of its nights, at %.2f".format(tonight)
            else "  tonight is not one of its nights"
        }
        report.fact("nextNight", nextNightAfter(aurora, night)) {
            "  the next night it comes is ${nextNightAfter(aurora, night) ?: "further off than $NIGHTS_LOOKED_AHEAD nights"}"
        }
        report.fact("starlit", starlit) { "  the sky is %.2f of the way to its darkest".format(starlit) }
        report.fact("ground", ground) { "  the ground within sight of you is %.2f cold enough".format(ground) }
        report.fact("rain", raining) { "  weather is hiding %.2f of it".format(raining) }
        report.fact("showing", showing) { "  so a client should be drawing it at %.3f".format(showing) }
        report.say { "  ${whyNotOf(tonight, starlit, ground, raining, showing)}" }
        report.finish()
        return SUCCESS
    }

    /** The one factor at nought, named — or what to conclude when none of them is. */
    private fun whyNotOf(tonight: Float, starlit: Float, ground: Float, raining: Float, showing: Float): String =
        when {
            showing > NOTHING_SHOWING ->
                "It is up. If the sky is empty, the fault is in the drawing — say so, it is not this."
            tonight <= NOTHING_SHOWING -> "Not tonight. `/age $SHOWING_LITERAL aurora $NOW_LITERAL` stops you waiting for it."
            starlit <= NOTHING_SHOWING -> "Too light. It keeps the hours its stars keep."
            ground <= NOTHING_SHOWING -> "Nowhere cold enough within sight. It stands where the snow lies."
            raining >= 1.0f -> "The weather has it."
            else -> "Every factor is above nothing but the product is not, which should not happen."
        }

    /**
     * `/age showing aurora now` — tonight's curtain, over any ground, until you walk out and back in.
     *
     * **The Age's own curtain rather than a demonstration one**, which is the whole difference from
     * `/age sky <name> aurora=ordinary`: what you see is what the book actually wrote, with the two things
     * that make it *wait* taken off. A preview is shown and never given ([LevelLookPreview]), so there is
     * no state to undo and nothing on the server believes any of it.
     */
    private fun runAuroraNow(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val look = Ages.recipeOf(level)?.let(Skies::lookOf)
        val aurora = look?.sky?.aurora
        if (look == null || aurora == null) {
            // **Says what to do about it**, because this is the command somebody reaches for when they
            // cannot find the curtain they think they wrote — and "there isn't one" is the one answer that
            // reads as a fault in the command rather than in the book.
            source.sendFailure(Component.literal(whyThereIsNoCurtain(level, look == null)))
            return FAILURE
        }
        val insisted = aurora.copy(frequency = EVERY_NIGHT, warmestGround = null)
        LevelLookPreview.show(level, look.copy(sky = look.sky.copy(aurora = insisted)))
        source.sendSuccess(
            { Component.literal("Tonight, and over any ground. Still needs darkness — it keeps its stars' hours.") },
            false,
        )
        // **Which way to look, because a band is not the whole sky.** It crosses about a hundred degrees
        // either side of its bearing, so a curtain that is up and behind you is indistinguishable from one
        // that is not up at all — which is exactly the confusion this command exists to end.
        source.sendSuccess({ Component.literal("  ${facingFor(insisted.bearingDegrees)}") }, false)
        return SUCCESS
    }

    /**
     * Why there is nothing to bring on, and how to get one.
     *
     * Two different answers: a level that is not an Age at all has no book to have written a curtain, and
     * an Age whose recipe simply did not ask for one needs a word that does.
     */
    private fun whyThereIsNoCurtain(level: ServerLevel, notAnAge: Boolean): String {
        val where = level.dimension().identifier()
        if (notAnAge) return "$where is not an Age, so no book wrote a curtain over it."
        return "Nothing hangs a curtain over $where. Write one with `auroral phenomena`, or describe one " +
            "and mean it — `green and red aurora`. Then `/age showing aurora` says why it is or is not up."
    }

    /** Where to stand looking, given a curtain crossing the sky at [bearingDegrees]. */
    private fun facingFor(bearingDegrees: Float): String =
        "It crosses the sky about ${compassPointFor(bearingDegrees)} — " +
            "face that way and look well up, not at the horizon."

    /**
     * `/age showing rainbow` — **why there is or is not a bow in the sky right now**, and
     * `/age showing rainbow now` to stop waiting for one.
     *
     * The aurora's problem, with one more way to have it. A bow can be correct and invisible because today
     * is not one of its days, because nothing has fallen, *or* because every light in the sky stands higher
     * than its own arc — and that last has no counterpart in a curtain and no symptom of its own.
     *
     * **The server can answer all of it but the wetness.** Which days a bow comes and where its light
     * stands are pure functions of the spec and the clock, so this recomputes exactly what the client will.
     * How long ago it rained is the one thing only the client remembers, so this says what is falling *now*
     * and names the gap rather than guessing across it.
     */
    private fun runRainbow(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val source = context.source
        val level = source.level
        val look = Ages.recipeOf(level)?.let(Skies::lookOf)
        val rainbow = look?.sky?.rainbow
        if (look == null || rainbow == null) {
            report.say { "Nothing writes a bow into ${level.dimension().identifier()}." }
            report.fact("hasRainbow", false) {
                "  Write one with `rainbows`, describe one — `red and yellow rainbow` — or `prismatic`."
            }
            report.finish()
            return SUCCESS
        }

        val clock = level.defaultClockTime
        val day = clock / VANILLA_DAY
        val today = rainbow.strengthOn(day)
        val raining = level.getRainLevel(1.0f)
        val lights = lightsIn(look)
        val highest = lights.maxOfOrNull { it.path.altitudeAt(clock) } ?: BELOW_EVERYTHING
        val cast = lights.maxOfOrNull { rainbow.castAt(it.path.altitudeAt(clock)) } ?: NOTHING_SHOWING

        report.say { "The bow over ${level.dimension().identifier()}, on day $day:" }
        report.fact("colours", rainbow.colours.size) {
            "  burns ${rainbow.colours.size} colour(s), outermost first, at radius %.0f°%s"
                .format(rainbow.radiusDegrees, if (rainbow.secondary) " with a second arc" else "")
        }
        report.fact("frequency", rainbow.frequency) {
            "  comes on %.0f%% of days, at glow %.2f, wanting %.0f%% rain"
                .format(rainbow.frequency * PER_CENT, rainbow.glow, rainbow.needsRain * PER_CENT)
        }
        report.fact("today", today) {
            if (today > NOTHING_SHOWING) "  today is one of its days, at %.2f".format(today)
            else "  today is not one of its days"
        }
        report.fact("nextDay", nextDayAfter(rainbow, day)) {
            "  the next day it comes is ${nextDayAfter(rainbow, day) ?: "further off than $NIGHTS_LOOKED_AHEAD days"}"
        }
        report.fact("lights", lights.size) { "  ${lights.size} light(s) in this sky could cast one" }
        report.fact("highest", highest) {
            "  the highest of them stands at %.0f°, and a bow needs one under %.0f°"
                .format(highest, rainbow.radiusDegrees)
        }
        report.fact("cast", cast) { "  so the geometry allows %.2f of a bow".format(cast) }
        report.fact("nextLow", nextLowEnough(rainbow, lights, clock)) {
            "  the next time a light is low enough is " +
                (nextLowEnough(rainbow, lights, clock)?.let { "in $it ticks" } ?: "not within a day")
        }
        report.fact("rain", raining) { "  %.2f is falling right now".format(raining) }
        report.fact("showing", today * cast) {
            "  so what the server can see comes to %.3f, before the air's own wetness".format(today * cast)
        }
        report.say { "  ${whyNoBow(rainbow, today, cast, highest, raining)}" }
        report.finish()
        return SUCCESS
    }

    /**
     * The one factor at nought, named — or what to conclude when none of them is.
     *
     * **Wetness is deliberately never the answer here**, because the server does not have it: the client
     * remembers the last of the rain for a couple of minutes and this cannot see that. So a dry reading is
     * reported as a maybe rather than as a cause, which is the honest thing and stops this command
     * confidently blaming the one factor it cannot measure.
     */
    private fun whyNoBow(
        rainbow: Rainbow,
        today: Float,
        cast: Float,
        highest: Float,
        raining: Float,
    ): String = when {
        today <= NOTHING_SHOWING -> "Not today. `/age $SHOWING_LITERAL rainbow $NOW_LITERAL` stops you waiting for it."
        cast <= NOTHING_SHOWING && highest >= rainbow.radiusDegrees ->
            "Every light is too high. A bow is a circle about the point opposite one, so a light above " +
                "%.0f° puts the whole arc underground. Wait for evening, or `/age $SHOWING_LITERAL rainbow $NOW_LITERAL`."
                    .format(rainbow.radiusDegrees)
        cast <= NOTHING_SHOWING -> "Nothing is up to light one."
        rainbow.needsRain > NOTHING_SHOWING && raining <= NOTHING_SHOWING ->
            "It should be up if it has rained in the last couple of minutes — the client remembers that " +
                "and this cannot. If it has not, that is why. `/age $SHOWING_LITERAL rainbow $NOW_LITERAL` takes the rain off."
        else -> "It is up. If the sky is empty, the fault is in the drawing — say so, it is not this."
    }

    /**
     * `/age showing rainbow now` — the Age's own bow, every day, needing no rain, and opened out far enough that
     * the light currently in the sky actually clears it.
     *
     * **The Age's own bow rather than a demonstration one**, which is the difference from
     * `/age sky <name> rainbow=ordinary`: what you see is what the book wrote, with the things that make it
     * *wait* taken off. A preview is shown and never given ([LevelLookPreview]).
     *
     * The radius is the part that cannot simply be insisted upon. A light standing above the arc's own
     * radius leaves no arc above the ground at all, and no amount of forcing the day or the weather changes
     * that — so this opens the radius until the crown clears, and says that it did.
     */
    private fun runRainbowNow(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val look = Ages.recipeOf(level)?.let(Skies::lookOf)
        val rainbow = look?.sky?.rainbow
        if (look == null || rainbow == null) {
            source.sendFailure(Component.literal("Nothing writes a bow here to bring on"))
            return FAILURE
        }
        val clock = level.defaultClockTime
        val lights = lightsIn(look)
        val leading = lights.maxByOrNull { it.path.altitudeAt(clock) }
        if (leading == null) {
            source.sendFailure(Component.literal("Nothing in this sky gives light, so nothing can cast a bow"))
            return FAILURE
        }
        val highest = leading.path.altitudeAt(clock)
        if (highest < BELOW_THE_HORIZON) {
            source.sendFailure(
                Component.literal("Every light here has set. A bow is bent sunlight; come back when one is up."),
            )
            return FAILURE
        }
        val opened = highest >= rainbow.radiusDegrees
        val insisted = rainbow.copy(
            frequency = EVERY_NIGHT,
            needsRain = NO_RAIN_WANTED,
            radiusDegrees = if (opened) (highest + CLEARS_THE_GROUND).coerceAtMost(WIDEST_FORCED) else rainbow.radiusDegrees,
        )
        LevelLookPreview.show(level, look.copy(sky = look.sky.copy(rainbow = insisted)))
        source.sendSuccess({ Component.literal("Today, and needing no rain. Until you walk out and back in.") }, false)
        if (opened) {
            source.sendSuccess(
                {
                    Component.literal(
                        "  Its light stands at %.0f°, over its own %.0f° arc, so the radius is opened to %.0f° to clear the ground. Not the Age's own shape."
                            .format(highest, rainbow.radiusDegrees, insisted.radiusDegrees),
                    )
                },
                false,
            )
        }
        // **Which way to look, because a bow is nowhere near its light.** It is a circle about the point
        // exactly opposite, so the one reliable instruction is to put the light at your back — and a bow
        // behind you is indistinguishable from one that is not there, which is what this command is for.
        val away = (leading.path.bearingAt(clock) + HALF_COMPASS) % WHOLE_COMPASS
        source.sendSuccess({ Component.literal("  ${lookingAwayFrom(away)}") }, false)
        return SUCCESS
    }

    /** Where to stand looking, given a bow centred on [bearingDegrees]. */
    private fun lookingAwayFrom(bearingDegrees: Float): String =
        "Put the light at your back and face ${compassPointFor(bearingDegrees)} — " +
            "the bow is centred there, low down."

    /** The bodies in [look] that give light, which are the ones that can cast a bow. */
    private fun lightsIn(look: LevelLook): List<CelestialBody> =
        look.sky.bodies.filter { it.blending == Blending.ADDS }

    /** The next day after [day] that [rainbow] comes, or null within [NIGHTS_LOOKED_AHEAD]. */
    private fun nextDayAfter(rainbow: Rainbow, day: Long): Long? =
        (day + 1..day + NIGHTS_LOOKED_AHEAD).firstOrNull { rainbow.strengthOn(it) > NOTHING_SHOWING }

    /**
     * How many ticks until some light is low enough to cast a bow, or null within a day.
     *
     * Walked rather than solved, for `CelestialPath.swing`'s reason: a light's altitude is a stack of
     * rotations and the tick it crosses a given angle has no closed form worth reading.
     */
    private fun nextLowEnough(rainbow: Rainbow, lights: List<CelestialBody>, clock: Long): Long? {
        if (lights.isEmpty()) return null
        var ahead = 0L
        while (ahead < VANILLA_DAY) {
            val at = clock + ahead
            if (lights.any { rainbow.castAt(it.path.altitudeAt(at)) > NOTHING_SHOWING }) return ahead
            ahead += LOOKING_AHEAD_STEP
        }
        return null
    }

    /** Nothing is up at all, in degrees — below any real altitude, so a sky with no lights sorts last. */
    private const val BELOW_EVERYTHING = -90.0f

    /** How far under the horizon still counts as a light that has set rather than one about to rise. */
    private const val BELOW_THE_HORIZON = -1.5f

    private const val NO_RAIN_WANTED = 0.0f

    /** How far above the horizon a forced bow's crown is put, so it is unmistakably there. */
    private const val CLEARS_THE_GROUND = 12.0f

    /** Past this the antisolar axis is near vertical and a bow stops being a shape anybody recognises. */
    private const val WIDEST_FORCED = 85.0f

    /** Coarse enough to be cheap and fine enough to be worth reading — a bow moves slowly. */
    private const val LOOKING_AHEAD_STEP = 100L

    /** The curtain this level wears, or null where it wears none. */
    private fun auroraOf(level: ServerLevel): Aurora? =
        Ages.recipeOf(level)?.let(Skies::lookOf)?.sky?.aurora

    /**
     * How dark the sky has gone, as the client will read it — the level's own suns where it has any of its
     * own, and vanilla's curve where it does not.
     */
    private fun starlitnessIn(level: ServerLevel): Float {
        val look = Ages.recipeOf(level)?.let(Skies::lookOf) ?: return NOTHING_SHOWING
        look.air.starBrightness?.let { return it }
        if (!look.sky.isOrdinary && look.rules.daylight != Daylight.VANILLA_CLOCK) {
            return LevelDaylight.starlitnessOf(look.readAt(level.defaultClockTime), look.rules)
        }
        // Vanilla's own curve, read off the light rather than off the stars: `getStarBrightness` is the
        // client's and this is the same fact from the side the server has. `skyDarken` runs 0 by day to
        // [FULLY_DARKENED] by night, which is the scale the client's nightliness is already on.
        return (level.skyDarken.toFloat() / FULLY_DARKENED).coerceIn(NOTHING_SHOWING, 1.0f)
    }

    /**
     * What share of the ground around [at] answers the curtain's rule.
     *
     * The same ring the client samples, asked of the server's own level — so a disagreement between this
     * and the window is a disagreement about *drawing* and never about the rule.
     */
    private fun groundShareFor(aurora: Aurora, level: ServerLevel, at: BlockPos): Float {
        val warmest = aurora.warmestGround ?: return 1.0f
        val around = listOf(at) + Direction.Plane.HORIZONTAL.map { at.relative(it, GROUND_RING_BLOCKS) }
        val cool = around.count { AuroraGroundRule.isCoolEnough(level, it, warmest) }
        return cool.toFloat() / around.size
    }

    /** What ground this curtain admits, in words — the ceiling read back against vanilla's own snow line. */
    private fun groundSaid(aurora: Aurora): String = when (val warmest = aurora.warmestGround) {
        null -> "any ground at all"
        Aurora.SNOW_LINE -> "ground where the snow lies"
        else -> "ground up to %.2f".format(warmest)
    }

    /** The next night the curtain comes, or null where none of the next [NIGHTS_LOOKED_AHEAD] is one. */
    private fun nextNightAfter(aurora: Aurora, night: Long): Long? =
        (night + 1..night + NIGHTS_LOOKED_AHEAD).firstOrNull { aurora.strengthOn(it) > NOTHING_SHOWING }

    /**
     * Prints an Age's sky, and when [preview] is given, shows that one instead.
     *
     * **What clients were told, not what the recipe would say**, which is the only reading that can catch the
     * pipeline having gone quiet — a look never given is a sky nobody sees, and recomputing it here would
     * report one anyway.
     */
    private fun runSkyReport(context: CommandContext<CommandSourceStack>, preview: String?): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val age = namedAge(source, name, Report.prose(source)) ?: return FAILURE
        val level = Ages.open(source.server, age.id)
        val recipe = age.recipe

        // The Art's own words and the library's parameters are told apart by name, so one line can carry both:
        // `sun.size=0.9..1.0 path=epicycle` reads as a sky the words describe with one thing turned.
        val said = preview?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
        val (parameters, words) = said.partition { SkyParameters.offers(it.substringBefore('=')) }

        // **A line with no words turns the Age's own sky**, and that is the only way a parameter reaches a sky
        // the words made: a preview spec resolves one sun and one moon whatever it says, bodies being
        // minted by clauses that a `sky.…` line cannot carry. Naming any word restates the sky in full, as
        // it always did — so `path=polar` alone tips the Age's own suns and `sky path=polar` tips one, a bare
        // `sky` being the sky as nothing describes it.
        val asWritten = if (words.isEmpty()) {
            LevelAppearance.of(level.dimension())?.sky ?: run {
                source.sendFailure(Component.literal("Nothing has said what '$name' looks like"))
                return FAILURE
            }
        } else {
            previewSpec(source, words.joinToString(" "), recipe.seed) ?: return FAILURE
        }

        // **Described after the parameters, not before.** Reporting the sky as written while showing the client
        // the sky as turned is the one thing this instrument must not do: you would read an unchanged
        // description, look up at a changed sky, and conclude the feature was broken.
        val shown = if (preview == null) {
            null
        } else {
            SkyParameters.applyTo(asWritten, parameters).getOrElse { problem ->
                source.sendFailure(Component.literal(problem.message ?: "Could not read a parameter"))
                return FAILURE
            }
        }
        shown?.let { LevelLookPreview.show(level, it) }

        val heading = if (preview == null) "Age '$name' sky" else "Previewing in '$name' (reverts on re-entry)"
        source.sendSuccess({ Component.literal(heading) }, false)
        if (parameters.isNotEmpty()) {
            source.sendSuccess({ Component.literal("  turned ${parameters.joinToString(" ")}") }, false)
        }
        for (line in (shown?.sky ?: asWritten).described()) {
            source.sendSuccess({ Component.literal("  $line") }, false)
        }
        // The Age's own clock — what every moving thing above reads, and the only way to tell an Age
        // following the overworld from one frozen at dawn.
        source.sendSuccess({ Component.literal("  $CLOCK_LABEL ${level.defaultClockTime}") }, false)
        // **And the hour it is *lit* as**, which is a different number and the one that decides every
        // colour: an Age's own suns move the whole timeline, and a sky with none is held at midnight. Not
        // having this said cost a walk — the sky was reported correct while the client showed a sunset
        // (Jonah, 2026-08-27).
        val showing = shown ?: LevelAppearance.of(level.dimension())
        val litAs = showing?.let { LevelClock.vanillaEquivalent(it, level.defaultClockTime) }
        if (litAs != null) {
            source.sendSuccess({ Component.literal("  $LIT_AS_LABEL ${Math.floorMod(litAs, VANILLA_DAY)}") }, false)
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
                        "Write it as `sky.size=0.6..1.0`, and use `/age compose` for the rest. " +
                        "The library's own parameters are ${SkyParameters.describeOffered()}.",
                ),
            )
            return null
        }
        // An unknown option value is refused here, where `/age compose` keeps it: a composition is a save
        // and must keep saying what it said, but an instrument that silently ignores `orbits=wilde` and
        // shows the default is worse than one that refuses.
        // **Everything overhead, not the vault alone.** The bodies are the sun's, the moon's and the
        // stars' aspects now, and a preview line still spells them all `sky.…` because it is one
        // instrument over one picture.
        // Which of the four owns each parameter, **first of them wins**: `rising` is the sun's and the moon's
        // alike, and a preview line naming one body means the sun. The moon's is reachable through
        // `/age compose moon.rising=…`, which spells the aspect out.
        val overhead = listOf(Aspect.SKY, Aspect.SUN, Aspect.MOON, Aspect.STARS)
        val ownerOfParameter = overhead
            .flatMap { aspect -> aspect.parameters.map { it.name to aspect } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, owners) -> owners.first() }
        val skyParameters = overhead.flatMap { it.parameters }.associateBy { parameter -> parameter.name }
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

        // **Aimed at the aspect that owns each parameter before it is read.** A preview spells everything
        // overhead `sky.…` because it is one instrument over one picture, but the bodies are the sun's, the
        // moon's and the stars' aspects — so a `sky.colour` left as written is stored on the vault and
        // looked for on the sun, which is to say accepted and then ignored.
        val aimed = preview.split(' ').filter { it.isNotBlank() && it != SKY_ASPECT }.joinToString(" ") { token ->
            val name = token.substringBefore('=').removePrefix("$SKY_ASPECT.")
            val owner = ownerOfParameter[name]
            if (!token.startsWith("$SKY_ASPECT.") || owner == null) token
            else "${owner.page}.$name=${token.substringAfter('=')}"
        }
        // The terrain here is scaffolding for the parser and is read by nothing.
        val composition = AgeComposition.parse("$PREVIEW_SCAFFOLD $aimed").getOrElse { problem ->
            source.sendFailure(Component.literal(problem.message ?: "Could not read '$preview'"))
            return null
        }
        // The Age's own seed, so a preview differs from the real sky only where the words differ.
        return Sky.specFor(composition, seed)
    }

}
