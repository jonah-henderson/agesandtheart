package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.resolution.Effect

/**
 * One idea a word carries, and how likely it is to apply (`the-world-model.md` §3): `frozen` means a cold
 * climate, a white sky, ice halos, each one meaning. Two meanings of one word setting the same parameter in the same
 * place are alternatives, and one of them is drawn, [chanceToApply] being its odds against the others.
 */
data class Meaning(
    val effects: List<Effect>,
    /** The chance this applies where the word is aimed, from nought to one. */
    val chanceToApply: Double = CERTAIN,
    /** The chance this applies where the word is laid bare, or null for the bare chance the Art gives everything. */
    val unaimedChanceToApply: Double? = null,
    /** Whether the word never lands without this: aimed it applies, and laid bare it applies at least once. */
    val mustAlwaysApply: Boolean = false,
    /** Whether this lands with nothing to take it — `sunless`, a minting — rather than modifying what accepts it. */
    val asserts: Boolean = false,
) {
    val narrows: Boolean get() = effects.any(Effect::narrows)

    /** Whether laying the word bare can apply this at all. */
    val appliesUnaimed: Boolean get() = mustAlwaysApply || unaimedChanceToApply != NEVER

    companion object {
        const val CERTAIN = 1.0
        const val NEVER = 0.0

        /** The Art's own bare chance: what a meaning laid bare is drawn at, times its aimed one, where it says none. */
        const val BARE_CHANCE = 0.5
    }
}
