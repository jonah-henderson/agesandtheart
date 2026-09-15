package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.consequence.Worsening
import co.voik.agesandtheart.age.consequence.Hostility
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Price
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Skew
import co.voik.agesandtheart.content.DeepWater
import co.voik.agesandtheart.age.aspect.Rung
import net.minecraft.server.MinecraftServer
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
     * One tick of every Age that has something happening in it.
     *
     * Called from both loaders' server-tick event, the same shape as `SERVER_STARTED` calling
     * `Ages.reloadSaved` — there is no shared entry point for a tick, and inventing a service for one
     * method would fragment `PlatformHelper` for a one-off (`CLAUDE.md`).
     *
     * **Only levels that are loaded and have someone in them.** An Age nobody is standing in has no
     * lightning worth spending a tick on, and the check is what keeps this from scaling with how many Ages
     * have ever been written.
     */
    fun tick(server: MinecraftServer) {
        val saved = AgeSavedData.get(server)
        if (saved.ages.isEmpty()) return
        for (level in server.allLevels) {
            val age = level.dimension().identifier()
            val recipe = saved.takeIf { age in it.ages }?.recipe(age) ?: continue
            val composition = recipe.composition ?: continue
            val happening = claimsIn(composition)
            // **The sea's level is settled before the emptiness check, and the counter after it.** Where
            // the sea *stands* has to be right whenever a chunk is made, and a chunk can be made in an Age
            // nobody is in — a forceload, a teleport arriving, a neighbouring player's view. How far it has
            // *got* may only advance while somebody is there, which is the whole of what the counted
            // register was chosen for. See [Deluge].
            val drowning = happening.any { it.value == Phenomenon.DELUGE.key }
            Deluge.stand(level, drowning, saved.presenceIn(age))
            if (level.players().isEmpty()) continue
            if (drowning) saved.spendATickIn(age)
            // What the Age could not hold, and what that bought. Derived rather than stored, so it comes
            // out the same on every open — see [Spending].
            val spending = Spending.of(server, recipe)
            val prices = Price.list(server)
            // **Before the weather, because a phenomenon may now scale what it asks of it.** A blizzard's
            // whole axis is how much of the time it is blowing, and an *inflicted* one is absent from the
            // written claims — so asking the weather from those alone left instability unable to drive the
            // one register it buys.
            val befalls = befalling(happening, spending, prices).toMutableMap().apply {
                // A blizzard somebody asked for by hand happens here whether or not the book wrote one —
                // see [Blizzard.force]. Without this, `/age weather blizzard 3` in an ordinary Age sets a
                // fierceness nothing reads.
                if (Blizzard.forcedIn(level) != null) putIfAbsent(Phenomenon.BLIZZARD, Rung.ORDINARY)
            }
            AgeWeather.steer(level, wanted(composition, befalls, spending, prices))
            for ((phenomenon, density) in befalls) {
                befall(level, phenomenon, density, furyOf(spending, prices, phenomenon))
            }
            // What the client cannot work out for itself — see [BlizzardPayload]. Sent on a slow beat
            // rather than on change, because "changed" would need a memory per player and the message is
            // a dozen bytes.
            if (server.tickCount % TELLING_THE_CLIENT == 0) {
                Blizzard.tellTheClients(level, befalls, spending, prices)
            }
            // Not a phenomenon — a wound is what the Age could not hold rather than something it does — but
            // it wants the same walk, and the walk is the expensive part.
            Hostility.stir(level)
            // Nor is this one: an Age goes on tearing — the ground that came back while nobody was
            // looking, and then the hole opening in front of somebody. See [Worsening].
            Worsening.advance(level, recipe, spending)
            // And a dragon an Age was written with needs telling where it is, or it flies to the world
            // origin to hold its pattern — see [Dragons].
            Dragons.findTheirOwnGround(level)
            // Nor is this: water deep enough to be an abyss becomes one, wherever it came from. **Here and
            // not beside `ChargedMetal.stir` on purpose** — that walks every level so crystal carried home
            // still works, where this must reach no Overworld and no End (Jonah, 2026-09-10). Walking the
            // Ages *is* the carve-out, and a positive one rather than a list to keep extended.
            DeepWater.seep(level)
        }
    }

    /**
     * The claim by which [phenomenon] befalls [level], or null where it does not.
     *
     * What [tick] reads per Age, asked the other way about — for a phenomenon that has to answer something
     * the world did rather than the clock, as a tempest answers a bolt landing ([Tempest.struck]). The
     * namespace test comes first because every bolt in the game asks this, and lightning outside an Age
     * should cost one string comparison.
     */
    fun claimFor(level: ServerLevel, phenomenon: Phenomenon): Claim? {
        val age = level.dimension().identifier()
        if (age.namespace != Constants.MOD_ID) return null
        val saved = AgeSavedData.get(level.server)
        if (age !in saved.ages) return null
        val composition = saved.recipe(age).composition ?: return null
        return claimsIn(composition).firstOrNull { it.value == phenomenon.key }
    }

    /**
     * What this Age's own instability makes of [phenomenon], nought to one — and nought for anywhere that
     * is not an Age.
     *
     * The same answer [tick] works out for itself, offered to anything that wants to *imitate* what an Age
     * would do rather than wait for it. A debug command that raised storms at a fierceness the Age had
     * never bought was showing something the game does not contain.
     */
    fun furyIn(level: ServerLevel, phenomenon: Phenomenon): Double {
        val age = level.dimension().identifier()
        if (age.namespace != Constants.MOD_ID) return NOTHING_INFLICTED
        val saved = AgeSavedData.get(level.server)
        if (age !in saved.ages) return NOTHING_INFLICTED
        val recipe = saved.recipe(age)
        return furyOf(Spending.of(level.server, recipe), Price.list(level.server), phenomenon)
    }

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
     * fierce; one that was written keeps whatever rung its writer gave it.
     */
    fun befalling(
        written: List<Claim>,
        spending: Spending,
        prices: Map<Manifestation, Price>,
    ): Map<Phenomenon, Double> = buildMap {
        for (claim in written) Phenomenon.named(claim.value)?.let { put(it, claim.density) }
        for (phenomenon in Phenomenon.entries) {
            if (furyOf(spending, prices, phenomenon) <= NOTHING_INFLICTED) continue
            putIfAbsent(phenomenon, Rung.ORDINARY)
        }
    }

    /**
     * How far the Age's instability reached into [phenomenon], from nothing to everything it could buy.
     *
     * Zero for a phenomenon instability has no manifestation for, which is every one of them but the
     * sandfall so far.
     */
    fun furyOf(spending: Spending, prices: Map<Manifestation, Price>, phenomenon: Phenomenon): Double {
        val manifestation = phenomenon.inflictedBy ?: return NOTHING_INFLICTED
        return spending.reach(manifestation, prices)
    }

    private const val NOTHING_INFLICTED = 0.0

    /** What the Age says befalls it, as claims — empty for one that says nothing. */
    private fun claimsIn(composition: AgeComposition): List<Claim> =
        Phenomena.claimsIn(composition.optionsFor(Aspect.PHENOMENA, 0))

    /**
     * The weather an Age is asking for: its own parameters, raised by anything befalling it that needs more.
     *
     * **A floor and never a setting**, so the two can be written together without one silently erasing the
     * other — a tempest in an Age already written as drenched is exactly as wet as the wetter of the two.
     */
    private fun wanted(
        composition: AgeComposition,
        befalls: Map<Phenomenon, Double>,
        spending: Spending,
        prices: Map<Manifestation, Price>,
    ): AgeWeather.Conditions {
        val air = composition.optionsFor(Aspect.WEATHER, 0)
        fun asked(parameter: Parameter) =
            air.steer(parameter, WEATHER_SALT)?.let(Span.NATURAL::fractionOf) ?: AgeWeather.ORDINARY_SHARE
        val dialled = AgeWeather.Conditions(asked(Atmosphere.RAINFALL), asked(Atmosphere.THUNDER))
        return befalls.entries.fold(dialled) { wants, (phenomenon, density) ->
            val howOften = Blizzard.howOftenOf(density, furyOf(spending, prices, phenomenon))
            wants.atLeast(phenomenon.insistsAt(howOften))
        }
    }

    /**
     * One phenomenon, once.
     *
     * The claim's rung is how *hard* it comes, which is the populative machinery already doing its job:
     * `teeming tempest` and `scarce tempest` are the same phenomenon at different strengths, and no
     * phenomenon needs a parameter of its own to be dialled.
     */
    private fun befall(level: ServerLevel, phenomenon: Phenomenon, density: Double, fury: Double) {
        when (phenomenon) {
            Phenomenon.TEMPEST -> Tempest.strike(level, density)
            Phenomenon.INFERNO -> Inferno.burn(level, density)
            // **Nothing, deliberately.** An aurora is seen rather than done: it is drawn on the client from
            // arithmetic every client does for itself, so there is no tick of it to run here and no state
            // for one to keep. See [Phenomenon.AURORA].
            Phenomenon.AURORA -> Unit
            // Nothing either, and for the same reason — with one part done already: what a bow needs of
            // the weather is `Phenomenon.RAINBOW.insistsOn`, which the Age was built with rather than
            // something to arrange here. See [Phenomenon.RAINBOW].
            Phenomenon.RAINBOW -> Unit
            Phenomenon.SANDFALL -> Sandfall.wander(level, density, fury)
            Phenomenon.TECTONICS -> CaveIns.stir(level, density)
            Phenomenon.BLIZZARD -> Blizzard.blow(level, density, fury)
            Phenomenon.METEORS -> Meteors.fall(level, density, fury)
            // **The rise is not here**, and that is the one thing to know about this phenomenon's shape:
            // the sea's level is a counted number advanced in [tick] whether or not a player is looking,
            // where these two are the near-player block work that makes it visible. See [Deluge].
            // **The pooling rain is PARKED, not deleted** (Jonah, 2026-09-13). It places sources on sky-lit
            // ground above the waterline, and while the rise itself is still being refined that reads as
            // random blocks appearing everywhere and drowns out the thing being judged. `Deluge.pool` is
            // left whole and unreferenced; put this call back when the sea is settled.
            Phenomenon.DELUGE -> Deluge.raise(level)
        }
    }

    /** How often a client is reminded what the weather here is, in ticks. */
    private const val TELLING_THE_CLIENT = 20

    /**
     * Fixed, so an Age's weather does not wander about inside the span a word bounded it to.
     *
     * Everywhere else a span is steered by the Age's seed, which spreads two Ages bounded alike. Weather is
     * read every tick, and a value that moved with the tick would be a different Age every time.
     */
    private const val WEATHER_SALT = 0L

    /** How many times over an ordinary claim asks for something. A rung multiplies it. */
    fun timesFor(density: Double, ordinary: Int): Int =
        (ordinary * (density / Rung.ORDINARY)).roundToInt().coerceAtLeast(1)
}
