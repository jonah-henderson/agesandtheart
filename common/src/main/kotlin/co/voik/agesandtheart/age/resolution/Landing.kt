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
     * The page of the word that took it, or its own page where it said something of itself — what tells two clauses
     * apart that land on the same place, as two kinds of obelisk do. Null for a page laid bare.
     */
    val clause: Int?,
    /** Whether its page was aimed here, rather than laid bare and drawn here — aimed wins where two disagree. */
    val aimed: Boolean,
    /** The chance it had to apply, which ranks it below an aimed landing and above a less likely one. */
    val chance: Double,
    /** Whether its meaning only might apply, being neither certain nor bound to land: laid bare, it yields to an aimed word. */
    val isAnOffer: Boolean = false,
    /** The `and` joining it to others, where one does: a division the writer asked for. */
    val joined: Int? = null,
    /** The biome it is sited in: `in jungle`. */
    val sitedIn: Identifier? = null,
    /** Whether it is widened to every place: `cats everywhere`. */
    val everywhere: Boolean = false,
    val polarity: Polarity = Polarity.ASSERTED,
)
