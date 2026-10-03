package co.voik.agesandtheart

import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.consequence.Hostility
import co.voik.agesandtheart.age.consequence.Worsening
import co.voik.agesandtheart.age.phenomena.Deluge
import co.voik.agesandtheart.age.phenomena.Happenings
import co.voik.agesandtheart.age.phenomena.Sampling
import co.voik.agesandtheart.age.phenomena.Tide
import co.voik.agesandtheart.content.DeepWater
import co.voik.agesandtheart.content.PlasmaField
import co.voik.agesandtheart.content.Dragons
import co.voik.agesandtheart.content.ScarabArrivals
import net.minecraft.server.MinecraftServer

/**
 * One tick of every Age, which is more than what is *happening* in one.
 *
 * Four of the things done here are not phenomena and say so where they are called: a wound stirring, an Age
 * going on tearing, a dragon told where it is, and deep water becoming an abyss. They share this walk
 * because the walk is the expensive part — the levels, their recipes and the gate on who is watching — and
 * not because they are alike.
 *
 * **Only levels that are loaded and have someone in them**, as [Sampling.watchers] counts them — a spectator
 * alone is nobody. An Age nobody is standing in has no lightning worth spending a tick on, and the check is
 * what keeps this from scaling with how many Ages have ever been written.
 */
object AgeTick {

    /** Called from [CommonSetup.serverTick]. */
    fun tick(server: MinecraftServer) {
        val saved = AgeSavedData.get(server)
        if (saved.ages.isEmpty()) return
        for (level in server.allLevels) {
            val age = level.dimension().identifier()
            val recipe = saved.recipe(age) ?: continue
            val composition = recipe.composition ?: continue
            val happening = Happenings.claimsIn(composition)
            // What the Age could not hold, and what that bought. Derived rather than stored, so it comes
            // out the same on every open — see [Spending]. Worked out here rather than inside
            // [Happenings.befallAll] because the sea and the tearing below read it too.
            val spending = Spending.of(server, recipe)
            // **The sea's level is settled before the emptiness check, and the counter after it.** Where
            // the sea *stands* has to be right whenever a chunk is made, and a chunk can be made in an Age
            // nobody is in — a forceload, a teleport arriving, a neighbouring player's view. How far it has
            // *got* may only advance while somebody is there and the rain is falling. See [Deluge].
            val rising = Deluge.risingIn(happening, spending)
            Deluge.stand(level, rising, saved.presenceIn(age))
            if (Sampling.watchers(level).isEmpty()) continue
            if (rising != null && level.isRaining) saved.spendATickIn(age)
            Happenings.befallAll(level, composition, happening, spending)
            // Not a phenomenon either: the sea following whichever moons pull it — see [Tide].
            Tide.flow(level, Tide.pullsIn(composition, recipe.seed))
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
            // Nor this: the heat over a plasma sea — see [PlasmaField].
            PlasmaField.burn(level)
            // Nor this: scarabs arriving where somebody could see them, at the rate the Age has earned.
            ScarabArrivals.arrive(level, recipe)
        }
    }
}
