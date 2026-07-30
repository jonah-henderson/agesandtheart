package co.voik.agesandtheart.age.aspect

/**
 * How often something occasional turns up — the **absolute** emphasis knob, where [Share] is relative.
 *
 * Every column must have some biome, so more of one is necessarily less of another; structures are
 * occasional, so "more villages" needs no reference to anything else and nothing has to give ground.
 *
 * A named ladder rather than a number, per design §3.2.
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
     * How many times as many of the thing there are — the **square** of the spacing change, since spacing
     * is a distance and structures sit on a grid. Halving spacing gives four times as many, not twice, so
     * every rung above is chosen by the count it wants and converted back.
     */
    val occurrenceScale: Double get() = 1.0 / (spacingScale * spacingScale)

    /** Whether this rung asks for anything at all — the one that does not is left strictly alone. */
    val isOrdinary: Boolean get() = this == ORDINARY

    companion object {
        /** The rung called [key], or null where the word names none. */
        fun named(key: String): Density? = entries.firstOrNull { it.key == key }
    }
}
