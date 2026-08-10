package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Price
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.phenomena.Sampling
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * An Age that will not stop tearing (design §5.2.1) — **blight, as a property of the Age rather than of any
 * wound**.
 *
 * Nothing here spreads from anything. There are no child wounds and a sealed wound seeds nothing, because
 * there is nothing to seed: the *Age* is unstable, and a seal contains what one wound does to its
 * surroundings rather than whether the world keeps holing itself. Box in every wound in a blighted Age and
 * come back to more of them.
 *
 * **One function, asked from more than one place.** [Tearing.wantedIn] says how holed a chunk should be;
 * generation asks it as a chunk is written, and [creep] asks it while somebody is standing there. The first
 * settles the truth in bulk and the second is the animation — it opens one at a time so the Age is seen to
 * worsen rather than found worse, and it can never run past what generation would have done, so the two
 * cannot disagree. The third caller, for chunks that already exist, is described below and is not built.
 *
 * **It advances whether or not anybody is there**, which §5.4 makes a per-manifestation choice rather than
 * an inherited property. A blight that waited for an audience would be one you could outlast by leaving.
 */
object Blight {

    /**
     * How a chunk catches up on the time it spent unloaded — **which is not built, and the reason is
     * written here so it is not attempted the same way twice.**
     *
     * It was: read the clock as a chunk loads and open the difference in one pass. That is the right *idea*
     * — the count is a pure function of the clock, so arriving at it costs the same whether the Age was
     * left for a minute or a month — and the wrong *place*. Writing blocks inside the chunk-load event
     * means mutating a chunk in the middle of its own transition to full, which re-enters chunk loading and
     * lighting; measured behaviour was a generation that never finished, where the same Age at zero days
     * generated instantly.
     *
     * **Where it belongs is the tick**, beside [creep]: a pass over loaded chunks near a player that brings
     * each up to the derived count in bulk rather than one at a time, running after the chunk is fully
     * loaded and owned by nobody. That is a small piece of work and it is the next one.
     *
     * Until then blight is correct in the two places it is applied — a chunk *generated* late comes out at
     * the right density, and a chunk somebody is standing in creeps toward it — and absent in the third:
     * ground already generated, then left, then returned to.
     */
    fun creep(level: ServerLevel) {
        val creeping = creepingIn(level) ?: return
        Sampling.sweep(level, ONE_PLACE) { chunk, _ ->
            Tearing.tearInto(
                level,
                chunk,
                level.seed,
                creeping.wantedIn(chunk, level),
                atMost = ONE_AT_A_TIME,
                already = Wounds.countIn(level, chunk.pos),
            )
        }
    }

    /** What an Age is worth tearing at, or null where it is not one or was written to hold together. */
    private fun creepingIn(level: ServerLevel): Creeping? {
        val id = level.dimension().identifier()
        if (id.namespace != Constants.MOD_ID) return null
        val saved = AgeSavedData.get(level.server)
        if (id !in saved.ages) return null
        val recipe = saved.recipe(id)
        val spending = Spending.of(level.server, recipe)
        val written = Tearing.writtenDensityAt(spending.bought(Manifestation.WOUNDS))
        val perDay = Tearing.blightPerDayAt(spending.bought(Manifestation.BLIGHT))
        // A coherent Age, or one holed exactly as far as its book holed it — either way nothing to do.
        if (perDay <= Tearing.NONE) return null
        return Creeping(written, perDay, recipe)
    }

    /** How holed this Age should be by now, and what it takes to work that out for one chunk. */
    private data class Creeping(val written: Double, val perDay: Double, val recipe: AgeRecipe) {
        fun wantedIn(chunk: ChunkAccess, level: ServerLevel): Int {
            val days = recipe.ageAt(level.server) / Tearing.TICKS_PER_DAY
            return Tearing.wantedIn(chunk.pos, level.seed, Tearing.densityAt(written, perDay, days))
        }
    }

    /**
     * How many wounds one visible step opens.
     *
     * One, and the word is the design: a chunk is brought up to date in bulk when nobody is looking and a
     * block at a time when somebody is, so what a player sees is a world opening in front of them rather
     * than a chunk that changed while they blinked.
     */
    private const val ONE_AT_A_TIME = 1

    /** How many positions each loaded chunk offers per tick — vanilla's precipitation rate, once. */
    private const val ONE_PLACE = 1
}
