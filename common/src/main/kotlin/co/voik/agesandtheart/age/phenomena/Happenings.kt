package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.ORDINARY_SPELL
import co.voik.agesandtheart.age.aspect.WeatherConditions
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Recurrence
import co.voik.agesandtheart.age.aspect.Rung
import net.minecraft.server.level.ServerLevel
import kotlin.math.roundToInt

/**
 * What is happening in an Age, every tick — the mod's first runtime behaviour (design §5.2).
 *
 * **Stateless on purpose**, which is what makes it a safe first one. §5.2 warns that phenomena mean an Age
 * has ongoing mutable state; a tempest has none — it reads the recipe, rolls, and forgets — so nothing here
 * has to be persisted, migrated or reconciled with a recipe that was rewritten underneath it.
 *
 * **Adding a phenomenon is a branch in [befall]**, which is exhaustive over [Phenomenon], so a new one
 * breaks the build until it is answered for.
 */
object Happenings {

    /**
     * Everything that befalls [level] this tick, and the weather they insist on.
     *
     * Called from `AgeTick.tick`, which owns the walk and the gate on who is watching. [spending] is handed
     * in rather than worked out here because the tearing done beside this reads it too, and it is one
     * answer for the whole Age.
     */
    fun befallAll(
        level: ServerLevel,
        composition: AgeComposition,
        happening: List<Claim>,
        spending: Spending,
        seed: Long,
    ) {
        // **Before the weather, because a phenomenon may now scale what it asks of it.** A blizzard's
        // whole axis is how much of the time it is blowing, and an *inflicted* one is absent from the
        // written claims — so asking the weather from those alone left instability unable to drive the
        // one register it buys.
        val befalls = befalling(happening, spending, asWrittenIn(composition, seed)).toMutableMap().apply {
            // A blizzard somebody asked for by hand happens here whether or not the book wrote one —
            // see [Blizzard.force]. Without this, `/age weather blizzard 3` in an ordinary Age sets a
            // fierceness nothing reads.
            if (Blizzard.forcedIn(level) != null) putIfAbsent(Phenomenon.BLIZZARD, Befalling.ORDINARY)
            // And a deluge likewise — see [Deluge.force].
            if (Deluge.isForcedIn(level)) putIfAbsent(Phenomenon.DELUGE, Befalling.ORDINARY)
        }
        AgeWeather.steer(level, wanted(composition, befalls, spending))
        Deluge.pours(level, Phenomenon.DELUGE in befalls)
        PhenomenaCeiling.holdTheWeather(level, befalls.keys)
        for ((phenomenon, befalling) in befalls) {
            befall(level, phenomenon, befalling, spending)
        }
        // What the client cannot work out for itself — see [BlizzardPayload]. Sent on a slow beat
        // rather than on change, because "changed" would need a memory per player and the message is
        // a dozen bytes.
        if (level.server.tickCount % TELLING_THE_CLIENT == 0) {
            Blizzard.tellTheClients(level, befalls, spending)
            Deluge.tellTheClients(level, befalls[Phenomenon.DELUGE]?.let { Deluge.risingOf(it.recurrence, spending, it.strength) })
            val fog = befalls[Phenomenon.FOG]
            Fog.tellTheClients(level, fog?.recurrence, FogDials.of(spending, fog?.strength ?: UNSAID_STRENGTH))
        }
    }

    /**
     * The claim by which [phenomenon] befalls [level], or null where it does not.
     *
     * What `AgeTick.tick` reads per Age, asked the other way about — for a phenomenon that has to answer
     * something the world did rather than the clock, as a tempest answers a bolt landing
     * ([Tempest.struck]). Every bolt in the game asks this, which is why it goes through
     * [Ages.recipeOf]'s namespace test.
     */
    fun claimFor(level: ServerLevel, phenomenon: Phenomenon): Claim? {
        val composition = Ages.recipeOf(level)?.composition ?: return null
        return claimsIn(composition).firstOrNull { it.value == phenomenon.key }
    }

    /**
     * What this Age's own instability bought — and nothing, for anywhere that is not an Age.
     *
     * The same answer `AgeTick.tick` works out for itself, offered to anything that wants to *imitate* what an Age
     * would do rather than wait for it. A debug command that raised storms at a fierceness the Age had
     * never bought was showing something the game does not contain.
     */
    fun spendingIn(level: ServerLevel): Spending {
        val recipe = Ages.recipeOf(level) ?: return Spending.NOTHING
        return Spending.of(level.server, recipe)
    }

    /**
     * Whether [phenomenon] befalls [level] at all — **written, or inflicted by its instability** — for
     * something that has to answer what the world did rather than the clock, as a bolt landing does.
     */
    fun befalls(level: ServerLevel, phenomenon: Phenomenon): Boolean =
        claimFor(level, phenomenon) != null || furyOf(spendingIn(level), phenomenon) > NOTHING_INFLICTED

    /**
     * Everything that befalls the Age and how hard, from **both** directions (design §7.7).
     *
     * A phenomenon is here because a book named it, because the Age's instability inflicted it, or both —
     * and where both, they **compound** (Jonah, 2026-08-31): `teeming sandfall` written into an Age that is
     * also coming apart is worse than either alone. The two are told apart without anything recording it,
     * which is what settles [Phenomena]'s open question: what was *written* is a claim in the composition,
     * and what was *inflicted* is derived from the index.
     *
     * A phenomenon instability inflicted comes at [Rung.ORDINARY], so its fury is the only thing making it
     * fierce; one that was written keeps whatever rung its writer gave it. Either comes as often and as hard
     * as [asWritten] says the book wrote it to, given the rung.
     */
    fun befalling(
        written: List<Claim>,
        spending: Spending,
        asWritten: (Phenomenon, Double) -> Befalling = { _, amount -> Befalling(amount, Recurrence.ORDINARY) },
    ): Map<Phenomenon, Befalling> = buildMap {
        for (claim in written) Phenomenon.named(claim.value)?.let { put(it, asWritten(it, claim.density)) }
        for (phenomenon in Phenomenon.entries) {
            if (furyOf(spending, phenomenon) <= NOTHING_INFLICTED) continue
            putIfAbsent(phenomenon, asWritten(phenomenon, Rung.ORDINARY))
        }
    }

    /** Everything that befalls an Age written as [composition] with [seed], from both directions. */
    fun befallingIn(composition: AgeComposition, seed: Long, spending: Spending): Map<Phenomenon, Befalling> =
        befalling(claimsIn(composition), spending, asWrittenIn(composition, seed))

    /** How often and how hard [composition] wrote each phenomenon, at the rung it came with. */
    private fun asWrittenIn(composition: AgeComposition, seed: Long): (Phenomenon, Double) -> Befalling {
        val options = composition.optionsFor(Aspect.PHENOMENA, 0)
        return { phenomenon, amount ->
            Befalling(
                amount,
                Phenomena.recurrenceOf(options, phenomenon, seed),
                Phenomena.strengthOf(options, phenomenon, seed),
            )
        }
    }

    /**
     * How far the Age's instability reached into [phenomenon], from nothing to everything it could buy.
     *
     * Zero for a phenomenon instability has no manifestation for, which is every one of them but the
     * sandfall so far.
     */
    fun furyOf(spending: Spending, phenomenon: Phenomenon): Double {
        val manifestation = phenomenon.inflictedBy ?: return NOTHING_INFLICTED
        return spending.reach(manifestation)
    }

    private const val NOTHING_INFLICTED = 0.0

    /** What the Age says befalls it, as claims — empty for one that says nothing. */
    fun claimsIn(composition: AgeComposition): List<Claim> =
        Phenomena.claimsIn(composition.optionsFor(Aspect.PHENOMENA, 0))

    /**
     * The weather an Age is asking for: its own parameters, raised by anything befalling it that needs more.
     *
     * **A floor and never a setting**, so the two can be written together without one silently erasing the
     * other — a tempest in an Age already written as drenched is exactly as wet as the wetter of the two.
     */
    private fun wanted(
        composition: AgeComposition,
        befalls: Map<Phenomenon, Befalling>,
        spending: Spending,
    ): WeatherConditions {
        val air = composition.optionsFor(Aspect.WEATHER, 0)
        fun asked(parameter: Parameter) = Atmosphere.weatherAsked(air, parameter)
        val dialled = WeatherConditions(asked(Atmosphere.RAINFALL), asked(Atmosphere.THUNDER))
        return befalls.entries.fold(dialled) { wants, (phenomenon, befalling) ->
            wants.atLeast(phenomenon.insistsAt(befalling, spending))
        }
    }

    /**
     * The weather [this] insists on when it comes this often.
     *
     * **Only a blizzard, a deluge and a tempest have anything to say here.** Every other phenomenon wants a
     * condition or does not, and wanting it *more* means nothing — a bow needs the rain to thin however often
     * it comes. A blizzard's whole axis is how much of the time it is happening, a deluge's downpour is one
     * of its three dials, and a constant tempest is a storm that never breaks.
     *
     * **Here rather than on the enum**, which is what lets [Phenomenon] stop importing the runtime that
     * obeys it: a vocabulary word should not carry one implementation's formula.
     */
    private fun Phenomenon.insistsAt(befalling: Befalling, spending: Spending): WeatherConditions {
        val recurrence = befalling.recurrence
        return when (this) {
            Phenomenon.BLIZZARD -> {
                val dials = BlizzardDials.of(spending, befalling.strength)
                WeatherConditions(
                    rainfall = Blizzard.shareOfTheTime(recurrence, dials.often),
                    spellLength = asIfTeeming(ORDINARY_SPELL, dials.long),
                )
            }
            Phenomenon.DELUGE ->
                WeatherConditions(rainfall = Deluge.risingOf(recurrence, spending, befalling.strength).rainShare)
            Phenomenon.TEMPEST -> if (recurrence.isConstant) NEVER_BREAKING else insistsOn
            Phenomenon.INFERNO, Phenomenon.AURORA, Phenomenon.RAINBOW,
            Phenomenon.SANDFALL, Phenomenon.METEORS, Phenomenon.TECTONICS, Phenomenon.FOG,
            Phenomenon.OVERGROWTH -> insistsOn
        }
    }

    /**
     * One phenomenon, once.
     *
     * How often it comes is the book's `frequency` for it; the rung counts it in space, and only a
     * phenomenon that can be counted there ([Phenomenon.countsInSpace]) is written with one.
     */
    private fun befall(level: ServerLevel, phenomenon: Phenomenon, befalling: Befalling, spending: Spending) {
        val recurrence = befalling.recurrence
        val written = befalling.strength
        when (phenomenon) {
            Phenomenon.TEMPEST -> Tempest.strike(level, recurrence.often, TempestDials.of(spending, written))
            Phenomenon.INFERNO -> Inferno.burn(level, befalling.amount, InfernoDials.of(spending, written))
            // **Nothing, deliberately.** An aurora is seen rather than done: it is drawn on the client from
            // arithmetic every client does for itself, so there is no tick of it to run here and no state
            // for one to keep. See [Phenomenon.AURORA].
            Phenomenon.AURORA -> Unit
            // Nothing either, and for the same reason — with one part done already: what a bow needs of
            // the weather is `Phenomenon.RAINBOW.insistsOn`, which the Age was built with rather than
            // something to arrange here. See [Phenomenon.RAINBOW].
            Phenomenon.RAINBOW -> Unit
            Phenomenon.SANDFALL -> Sandfall.wander(level, befalling, SandfallDials.of(spending, written))
            Phenomenon.TECTONICS -> CaveIns.stir(level, recurrence.often, TectonicsDials.of(spending, written))
            Phenomenon.BLIZZARD -> Blizzard.blow(level, BlizzardDials.of(spending, written))
            Phenomenon.METEORS -> Meteors.fall(level, recurrence, MeteorDials.of(spending, written))
            // **The rise is not here**, and that is the one thing to know about this phenomenon's shape:
            // the sea's level is a counted number advanced in `AgeTick.tick`, where these two are the
            // near-player block work that makes it visible. See [Deluge].
            Phenomenon.DELUGE -> {
                Deluge.raise(level)
                Deluge.pool(level)
            }
            // Nothing: a fog is seen rather than done, and what the client is told is sent with the rest below.
            Phenomenon.FOG -> Unit
            // Only the seeding. The roots spread on their own random ticks. See [Overgrowth].
            Phenomenon.OVERGROWTH -> Overgrowth.seed(level, befalling.amount)
        }
    }

    /** A storm that never breaks: rain and thunder at the top of their dials, which [AgeWeather] keeps up. */
    private val NEVER_BREAKING = WeatherConditions(rainfall = ALWAYS, thunder = ALWAYS)
    private const val ALWAYS = 1.0
    private const val UNSAID_STRENGTH = 0.0

    /** How often a client is reminded what the weather here is, in ticks. */
    private const val TELLING_THE_CLIENT = 20


    /** How many times over an ordinary claim asks for something. A rung multiplies it. */
    fun timesFor(density: Double, ordinary: Int): Int =
        (ordinary * (density / Rung.ORDINARY)).roundToInt().coerceAtLeast(1)
}
