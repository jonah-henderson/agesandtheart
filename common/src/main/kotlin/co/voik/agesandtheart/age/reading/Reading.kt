package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.LinkAnchor
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.words.Offer
import net.minecraft.resources.Identifier

/**
 * What a row of pages reads as: what became of each page, the phrases its things closed, what each `age` handed out,
 * and where a link arrives. Nothing here has been drawn; every placement is one that could land.
 */
data class Reading(
    val pages: List<PageRead>,
    val phrases: List<Phrase>,
    /** Each meaning that reached an `age`, at each part that took it, and what the Age took itself. */
    val handedOut: List<Placement>,
    val arrival: Arrival? = null,
) {
    /** The pages that placed nothing anywhere: misplaced, charged one flaw each, and drawn somewhere at random. */
    val dangling: List<PageRead> get() = pages.filter { it.fate == Fate.Dangles }

    /** Whether every page is a word and lands somewhere: the book reads as it was laid. */
    val readsAsLaid: Boolean get() = pages.none { it.fate == Fate.Unknown || it.fate == Fate.Dangles }

    /** Every placement there is, a phrase's and the Age's. */
    val placements: List<Placement> get() = phrases.flatMap(Phrase::placements) + handedOut
}

/** Where a link into the Age arrives, and the page naming it: `jungle` in `link in jungle`, which a flaw against it names. */
data class Arrival(val page: Int, val anchor: LinkAnchor)

/** One page, the sense it was read in where it has more than one, and what became of it. */
data class PageRead(val index: Int, val written: String, val sense: Int?, val fate: Fate)

sealed interface Fate {
    /** No word of that name. */
    data object Unknown : Fate

    /** A structure word that bound what it should. */
    data object Structure : Fate

    /** A thing that closed a phrase. */
    data object Closed : Fate

    /** A thing that passed what reached it, and what it offers, on to the word after it. */
    data class PassedOn(val to: Int) : Fate

    /** A word something took. */
    data object Placed : Fate

    /** A word that placed nothing anywhere, or a structure word that bound nothing. */
    data object Dangles : Fate
}

/**
 * A thing and what was said of it: its own meaning, what it took from the pages before it, and what its term carries —
 * a polarity, a biome it is sited in, whether it is everywhere, and the `and` that joined it to others.
 */
data class Phrase(
    val head: Int,
    val part: Aspect?,
    val own: List<Effect>,
    val placements: List<Placement>,
    val polarity: Polarity = Polarity.ASSERTED,
    val sitedIn: Identifier? = null,
    val everywhere: Boolean = false,
    /** The first page of the `and` joining it to others, or null where nothing joined it. */
    val joined: Int? = null,
)

/**
 * One offer taken: whose it was, the word that took it, the part it lands on, the effects it became — none, where it
 * was taken and dropped — and the chance it applies at. [carrying] are the pages of the describing words that folded
 * something into this offer on its way, or reshaped it, each placed with it.
 */
data class Placement(
    val from: Int,
    val offer: Offer,
    val at: Int,
    val part: Aspect?,
    val effects: List<Effect>,
    val carrying: List<Int> = emptyList(),
    /** The first page of the `and` that joined its offer to others, so that both values arrive together. */
    val joined: Int? = null,
    /** The offer's chance where it was said of a thing, and its chance from the whole Age, times the part's, where `age` handed it out. */
    val chance: Double = offer.chance,
)
