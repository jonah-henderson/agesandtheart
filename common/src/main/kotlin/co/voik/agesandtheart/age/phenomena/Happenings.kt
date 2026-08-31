package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.consequence.Blight
import co.voik.agesandtheart.age.consequence.Hostility
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Skew
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
            if (level.players().isEmpty()) continue
            val composition = saved.takeIf { level.dimension().identifier() in it.ages }
                ?.recipe(level.dimension().identifier())?.composition
                ?: continue
            val happening = claimsIn(composition)
            // The weather first: a phenomenon that needs rain has to be standing in it by the time it runs.
            AgeWeather.steer(level, wanted(composition, happening))
            for (claim in happening) befall(level, claim)
            // Not a phenomenon — a wound is what the Age could not hold rather than something it does — but
            // it wants the same walk, and the walk is the expensive part.
            Hostility.stir(level)
            // Nor is this one: blight is the Age going on tearing, and what happens here is only the part
            // somebody is present for. The bulk of it is settled as chunks load — see [Blight].
            Blight.creep(level)
            // And a dragon an Age was written with needs telling where it is, or it flies to the world
            // origin to hold its pattern — see [Dragons].
            Dragons.findTheirOwnGround(level)
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

    /** What the Age says befalls it, as claims — empty for one that says nothing. */
    private fun claimsIn(composition: AgeComposition): List<Claim> =
        Phenomena.claimsIn(composition.optionsFor(Aspect.PHENOMENA, 0))

    /**
     * The weather an Age is asking for: its own dials, raised by anything befalling it that needs more.
     *
     * **A floor and never a setting**, so the two can be written together without one silently erasing the
     * other — a tempest in an Age already written as drenched is exactly as wet as the wetter of the two.
     */
    private fun wanted(composition: AgeComposition, happening: List<Claim>): AgeWeather.Conditions {
        val air = composition.optionsFor(Aspect.WEATHER, 0)
        fun asked(parameter: Parameter) =
            air.steer(parameter, WEATHER_SALT)?.let(Span.NATURAL::fractionOf) ?: AgeWeather.ORDINARY_SHARE
        val dialled = AgeWeather.Conditions(asked(Atmosphere.RAINFALL), asked(Atmosphere.THUNDER))
        return happening.fold(dialled) { wants, claim ->
            wants.atLeast(Phenomenon.named(claim.value)?.insistsOn ?: AgeWeather.Conditions.ORDINARY)
        }
    }

    /**
     * One phenomenon, once.
     *
     * The claim's rung is how *hard* it comes, which is the populative machinery already doing its job:
     * `teeming tempest` and `scarce tempest` are the same phenomenon at different strengths, and no
     * phenomenon needs a knob of its own to be dialled.
     */
    private fun befall(level: ServerLevel, claim: Claim) {
        when (Phenomenon.named(claim.value) ?: return) {
            Phenomenon.TEMPEST -> Tempest.strike(level, claim.density)
            Phenomenon.INFERNO -> Inferno.burn(level, claim.density)
            // **Nothing, deliberately.** An aurora is seen rather than done: it is drawn on the client from
            // arithmetic every client does for itself, so there is no tick of it to run here and no state
            // for one to keep. See [Phenomenon.AURORA].
            Phenomenon.AURORA -> Unit
            // Nothing either, and for the same reason — with one part done already: what a bow needs of
            // the weather is `Phenomenon.RAINBOW.insistsOn`, which the Age was built with rather than
            // something to arrange here. See [Phenomenon.RAINBOW].
            Phenomenon.RAINBOW -> Unit
        }
    }

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
