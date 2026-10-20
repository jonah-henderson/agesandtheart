package co.voik.agesandtheart.age.language

/**
 * The pages that are structure rather than meaning: they say how other pages go together. Named in a pack's
 * `art/grammar/` by the production each is.
 */
enum class Structure(val production: String, val price: Int) {
    /** The Age itself, which takes everything laid before it bare. Every book has one. */
    AGE("nucleus", price = 1),

    /** Binds the terms either side of it: `pillars and hills landmass`, `trees and villages`. */
    AND("conjunction", price = 2),

    /** Sites the last word that took pages, and what it took, in a biome: `beautiful plants in jungle`. */
    IN("in", price = 2),

    /** Widens the last word that took pages to every place: `cats everywhere`. */
    EVERYWHERE("everywhere", price = 2),

    /** The member after it and nothing else of its kind: `only villages`. */
    ONLY("only", price = 2),

    /** Anything of its kind but the member after it: `no villages`. */
    EXCEPT("except", price = 2),
    ;

    companion object {
        /** The structure a pack's `production` names, or null for any other. */
        fun named(production: String): Structure? = entries.firstOrNull { it.production == production }
    }
}
