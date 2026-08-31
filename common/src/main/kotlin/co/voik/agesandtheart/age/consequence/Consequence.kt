package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import net.minecraft.server.MinecraftServer

/**
 * Everything an Age's instability bought, as the generator needs it (design §5.0).
 *
 * **One object rather than four fields, because they change together or not at all.** A generator is built
 * once when its Age opens and outlives any number of commands; what the Age *is* can be rewritten under it
 * — by `/age decay`, and by book editing when that exists — and every one of these has to move at once or
 * the world generates against a mixture of two answers.
 *
 * Derived, never stored: a pure function of the recipe and the price list, so it comes out the same on
 * every open and the recipe stays a record of what was *written* rather than of what follows from it.
 */
data class Consequence(
    /** How many wounds a chunk holds from the book alone, before any time has passed (§5.1). */
    val woundsPerChunk: Double,
    /** How many more each of the Age's days adds — the Age going on tearing (§5.2.1). */
    val woundsPerDay: Double,
    /** How many tears the Age's floor is cut with, per cell — collapse (§5.3). They widen themselves. */
    val collapseTears: Int,
    /** The overworld tick the Age was written on, which the two rates are counted from. */
    val writtenAt: Long,
) {
    /** Whether anything at all is wrong, so a coherent Age can skip the whole pass. */
    val isNothing: Boolean
        get() = woundsPerChunk <= Tearing.NONE &&
            woundsPerDay <= Tearing.NONE &&
            collapseTears <= Collapse.NONE

    /** How long the Age has stood by [now], in its own days — what both rates are multiplied by. */
    fun daysBy(now: Long): Long = (now - writtenAt).coerceAtLeast(0L) / Tearing.TICKS_PER_DAY

    companion object {
        /** An Age with nothing wrong with it, which is nearly all of them. */
        val NOTHING = Consequence(Tearing.NONE, Tearing.NONE, Collapse.NONE, AgeRecipe.UNRECORDED)

        /** What [recipe] bought, at the prices [server] is running. */
        fun of(server: MinecraftServer, recipe: AgeRecipe): Consequence {
            val spending = Spending.of(server, recipe)
            return Consequence(
                woundsPerChunk = Tearing.writtenDensityAt(spending.bought(Manifestation.WOUNDS)),
                woundsPerDay = Tearing.woundsPerDayAt(spending.bought(Manifestation.WORSENING_WOUNDS)),
                collapseTears = Collapse.tearsPerCellAt(spending.bought(Manifestation.COLLAPSE)),
                writtenAt = recipe.writtenAt,
            )
        }
    }
}
