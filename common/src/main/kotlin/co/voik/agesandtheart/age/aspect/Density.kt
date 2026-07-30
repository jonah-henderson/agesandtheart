package co.voik.agesandtheart.age.aspect

/**
 * How often something occasional turns up — the **absolute** emphasis knob (Jonah, 2026-07-29).
 *
 * **Why this is not [Share], and the distinction is the interesting part.** A biome's emphasis has to be
 * *relative*: every column of the world must have some biome, so more of one is necessarily less of another,
 * and the only honest question is what proportion each takes. Structures are **occasional** — most chunks have
 * none — so "more villages" needs no reference to anything else at all. Nothing has to give ground for it.
 *
 * That also makes it the *right* emphasis for structures, where the weighted roll inside a
 * [net.minecraft.world.level.levelgen.structure.StructureSet] is the wrong one: vanilla retries that roll
 * until something fits the biome, so raising a desert village's weight mostly cannot do anything the biome had
 * not already decided.
 *
 * A named ladder rather than a number, per design §3.2 — you write a word, not a slider.
 */
enum class Density(val key: String, val spacingScale: Double) {
    /** A quarter as many. */
    SCARCE("scarce", spacingScale = 2.0),

    /** However many vanilla places, untouched — and the only rung that rebuilds nothing. */
    ORDINARY("ordinary", spacingScale = 1.0),

    /** Something over twice as many. */
    PLENTIFUL("plentiful", spacingScale = 2.0 / 3.0),

    /** Four times as many. */
    TEEMING("teeming", spacingScale = 0.5),
    ;

    /**
     * How many times as many of the thing there are — **the square of the spacing change**, because spacing is
     * a distance and structures sit on a grid.
     *
     * The trap this exists to name: spacing is a *linear* knob on a *quadratic* outcome, so halving villages'
     * spacing from 34 to 17 gives four times as many, not twice. Every rung above is chosen by the count it
     * wants and converted back, never the other way round.
     */
    val occurrenceScale: Double get() = 1.0 / (spacingScale * spacingScale)

    /** Whether this rung asks for anything at all — the one that does not is left strictly alone. */
    val isOrdinary: Boolean get() = this == ORDINARY

    companion object {
        /** The rung called [key], or null where the word names none. */
        fun named(key: String): Density? = entries.firstOrNull { it.key == key }
    }
}
