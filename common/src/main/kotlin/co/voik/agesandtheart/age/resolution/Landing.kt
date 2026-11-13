package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.aspect.Polarity
import net.minecraft.resources.Identifier

/**
 * What one page says at one place, once the reading has found where it lands — all that resolution is handed. A body
 * of a population is brought into being by a landing on it, even one that says nothing more.
 */
data class Landing(
    val effects: List<Effect>,
    val place: Place,
    /** The word that said it, which is what a flaw names. */
    val word: String,
    /** Where in the book its page was laid, counting from nought. */
    val page: Int,
    /**
     * The page of the thing it was said of, or its own where it is the thing — what tells two clauses apart that land on
     * the same place, as two kinds of obelisk do.
     */
    val saidOf: Int,
    /** The chance it had to apply, which ranks it below a likelier landing. */
    val chance: Double,
    /** The `and` joining it to others, where one does: a division the writer asked for. */
    val joined: Int? = null,
    /** The biome it is sited in: `in jungle`. */
    val sitedIn: Identifier? = null,
    /** Whether it is widened to every place: `cats everywhere`. */
    val everywhere: Boolean = false,
    val polarity: Polarity = Polarity.ASSERTED,
) {
    /** Its page and where it landed, which is what a readout says a word of. */
    val at: LandingAt get() = LandingAt(page, place)
}

/** A page and one place it landed — several landings where its meanings landed there together. */
data class LandingAt(val page: Int, val place: Place)
