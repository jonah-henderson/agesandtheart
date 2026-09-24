package co.voik.agesandtheart.age.aspect

/**
 * **What one property holds** — the whole of the world model, and everything else falls out of it
 * (`the-world-model.md` §2).
 *
 * The three kinds decide what a claim on the property can mean, which operators are legal there, and
 * whether it needs tagging — so nothing anywhere has to author those rules separately.
 */
enum class Holds {
    /**
     * Nothing of its own — the property is a group of other properties, and what it *is* is where they were
     * left. Only an aspect is ever this; a parameter always holds something.
     */
    NOTHING,

    /**
     * One value on a continuous axis — a temperature, a fog distance, how large a vein is.
     *
     * **Self-describing, so no range is ever tagged**: a word bounds it and nothing has to be told which
     * temperatures are hot. Two claims broaden into the span holding both, since a word carrying a span is
     * evocative about that axis, and they fracture instead where the antonym table says the two disagree —
     * without which two spans miles apart would silently average into mush.
     */
    RANGE,

    /**
     * One value drawn from a list — a terrain's shape, a colour, a block. Closed (three skies) or open
     * (every block in the game), which the property says separately.
     *
     * **The kind that needs tags** (world model §7): a vague word choosing among seventeen shapes has
     * nothing to go on, where a range answers for itself.
     */
    CATALOGUE,

    /**
     * A distribution the template ships and claims skew — the biomes, the things placed in the ground, the
     * structures, the creatures.
     *
     * Members are usually named from a registry, and may also be brought into being by description
     * (`gold block deposits`). Never counted: `teeming jungles` is a weight, since there is one jungle and the
     * world has more or less of it.
     */
    WEIGHTED_SET,

    /**
     * Individuals that exist only because somebody described them — the suns, the moons.
     *
     * Each member holds properties of its own, so this is the recursive kind. A member is minted by the
     * clause that describes it and there is no number anywhere: `a sun. a sun.` is two suns because it is
     * two clauses, which is what took counts out of the language.
     *
     * Not to be confused with [WEIGHTED_SET]: a jungle is a kind the world has more or less of, where a
     * sun is one of several individuals. Both are populations in the ordinary sense; what separates these
     * is that their members are written rather than drawn.
     */
    POPULATION,
    ;

}
