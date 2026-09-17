package co.voik.agesandtheart.age.aspect

import net.minecraft.resources.Identifier

/**
 * **Where an aspect's members are held** — the pool a sentence adds to, takes from and leans, and where
 * the answer it settled on is stored for the recipe to keep.
 *
 * Not a [Parameter], and the difference is who writes it. A parameter is *exposed*: a writer names it and
 * gives it a value, and it holds one. This is *resolved*: nothing sets it directly, `Resolver.weighed`
 * compiles what the sentence said about the population into it, and generation reads it back.
 *
 * It was a parameter with [Holds.WEIGHTED_SET] on it, which is a parameter saying it does not hold a
 * value — and three of `Parameter`'s fields existed for these five alone while half of its own were dead
 * on them. The forge would have needed an exception to stop offering them as settings; this is that
 * exception's absence.
 *
 * **Two senses of "pool" and both are wanted.** The *curated* pool is what `preset_tags` describes and a
 * vague word may draw from; the *settled* pool is what one Age ended up with. Callers say which.
 */
data class Pool(
    /** What the recipe calls this, and the key its claims are stored under — `lives`, `built`, `grown`. */
    val name: String,


    /** How a recipe says this population holds nothing at all, or null where it may never be emptied. */
    val emptiedBy: String? = null,

    /**
     * The least a claim may be scaled to and still be kept — nothing at all where the pool may be emptied,
     * and otherwise [Rung.ORDINARY]. Below it the member is struck out instead.
     */
    val leastKept: Double = if (emptiedBy != null) NOTHING_AT_ALL else Rung.ORDINARY,

    /**
     * Values that are **ours** rather than a registry's, which closes the pool.
     *
     * Every other population draws from the game — a creature is an entity type, a feature is a placed
     * feature — so it takes any id and complains later, where the missing content bites. A phenomenon has
     * nothing behind it in vanilla, so its values are written down and anything else is a typo rather than
     * an unloaded pack.
     */
    val named: List<String> = emptyList(),

    val help: String = "",

    /** Whether a claim on it may be confined to one biome — `spawns only slime in mushroom_fields`. */
    val confinable: Boolean = false,
) {
    /** Whether it takes an id it has never heard of, which every pool but the phenomena's does. */
    val open: Boolean get() = named.isEmpty()

    /** What a recipe may spell here: nothing changed, emptied, or one of ours. */
    val options: List<String> get() = listOfNotNull(Parameter.UNCHANGED, emptiedBy) + named

    /**
     * Whether [value] is a legal entry here — an id where the pool is open, one of ours where it is not.
     *
     * An open pool takes an id it has never heard of and complains later, where the missing content
     * bites; a closed one knows every value it can hold, so anything else is a typo.
     */
    fun accepts(value: String): Boolean =
        value in options || (open && Identifier.tryParse(value) != null)

    /** How [claims] skew this population where [biome] is the ground — see [Skew.of], told this pool's emptier. */
    fun skewOf(claims: List<Claim>, biome: Identifier? = null): Skew = Skew.of(claims, biome, emptiedBy)

    companion object {
        /** How every pool that may be emptied says so: `spawns nothing`, `structures nothing`. */
        const val NOTHING = "nothing"

        private const val NOTHING_AT_ALL = 0.0
    }
}
