package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Population
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
            val id = level.dimension().identifier()
            if (id !in saved.ages) continue
            for (claim in claimsIn(server, level)) befall(level, claim)
        }
    }

    /** What the Age at [level] says befalls it, as claims — empty for one that says nothing. */
    private fun claimsIn(server: MinecraftServer, level: ServerLevel): List<Claim> {
        val composition = AgeSavedData.get(server).recipe(level.dimension().identifier()).composition
            ?: return emptyList()
        val options = composition.optionsFor(Aspect.PHENOMENA, 0)
        val happening = Population.of(options.allSpelled(Phenomena.HAPPENS.name).map(Claim::read))
        return happening.wanted
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
        }
    }

    /** How many times over an ordinary claim asks for something. A rung multiplies it. */
    fun timesFor(density: Double, ordinary: Int): Int =
        (ordinary * (density / Rung.ORDINARY)).roundToInt().coerceAtLeast(1)
}
